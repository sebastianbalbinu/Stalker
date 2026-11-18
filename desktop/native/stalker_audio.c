/*
 * Stalker para Windows — motor de audio.
 *
 * Decodifica FLAC / WAV / MP3 con miniaudio (dr_flac) y entrega el audio de dos formas:
 *   - Modo exclusivo (WASAPI propio): el DAC se pone a la frecuencia y profundidad del archivo,
 *     sin pasar por el mezclador de Windows. Es la salida bit-perfect.
 *   - Modo compartido (miniaudio): convive con el resto de apps, Windows mezcla y remuestrea.
 *
 * Reproducción sin pausas (gapless) cuando la siguiente pista tiene el mismo formato.
 */
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_ENGINE
#define MA_NO_NODE_GRAPH
#define MA_NO_RESOURCE_MANAGER
#define MA_ENABLE_ONLY_SPECIFIC_BACKENDS
#define MA_ENABLE_WASAPI
#include "miniaudio.h"

#include <string.h>
#include <stdio.h>
#include <wchar.h>

#define SA_API __declspec(dllexport)

static const GUID SA_SUBTYPE_PCM = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

/* ---------------------------------------------------------------- estado */

static ma_context  g_ctx;
static int         g_ctxOk = 0;
static ma_mutex    g_lock;

static ma_decoder* g_cur  = NULL;
static ma_decoder* g_next = NULL;

static volatile int   g_playing = 0;
static volatile int   g_ended   = 0;
static volatile int   g_changes = 0;
static volatile float g_volume  = 1.0f;
static volatile int   g_lost    = 0;

/* formato del archivo */
static int g_srcRate = 0, g_srcCh = 0, g_srcBits = 0;
/* formato de salida */
static ma_format g_fmt = ma_format_unknown;
static int g_outRate = 0, g_outCh = 0, g_outBits = 0, g_outValid = 0;
static int g_mode = 0;        /* 0 cerrado, 1 exclusivo, 2 compartido */
static int g_devIndex = -1;   /* dispositivo con el que se abrió */

static char g_err[256] = {0};
static wchar_t g_curPath[1024], g_nextPath[1024];
static int g_wantExcl = 1;

/* modo compartido */
static ma_device g_dev;

/* modo exclusivo */
static HANDLE g_thread = NULL, g_ready = NULL;
static volatile int g_stop = 0;
static volatile int g_openResult = 0;
static int g_reqRate, g_reqCh, g_reqBits;
static WCHAR g_reqId[64];
static int g_reqHasId = 0;

static void set_err(const char* s) { strncpy(g_err, s, sizeof(g_err) - 1); g_err[sizeof(g_err) - 1] = 0; }

/* ---------------------------------------------------------------- relleno */

static void fill(void* out, ma_uint32 frames)
{
    ma_uint32 bpf = ma_get_bytes_per_frame(g_fmt, (ma_uint32)g_outCh);
    ma_uint8* p = (ma_uint8*)out;
    ma_uint32 done = 0;

    ma_mutex_lock(&g_lock);
    if (g_playing && g_cur != NULL) {
        while (done < frames) {
            ma_uint64 got = 0;
            ma_decoder_read_pcm_frames(g_cur, p + (size_t)done * bpf, frames - done, &got);
            done += (ma_uint32)got;
            if (done < frames && got == 0) {
                if (g_next != NULL) {
                    ma_decoder_uninit(g_cur);
                    ma_free(g_cur, NULL);
                    g_cur = g_next;
                    g_next = NULL;
                    wcsncpy(g_curPath, g_nextPath, 1023);
                    g_changes++;
                } else {
                    g_ended = 1;
                    g_playing = 0;
                    break;
                }
            }
        }
    }
    if (done < frames) {
        ma_silence_pcm_frames(p + (size_t)done * bpf, frames - done, g_fmt, (ma_uint32)g_outCh);
    }
    if (g_volume < 0.9999f && done > 0) {
        ma_apply_volume_factor_pcm_frames(p, done, g_fmt, (ma_uint32)g_outCh, g_volume);
    }
    ma_mutex_unlock(&g_lock);
}

static void shared_cb(ma_device* d, void* out, const void* in, ma_uint32 frames)
{
    (void)d; (void)in;
    fill(out, frames);
}

/* ---------------------------------------------------------------- WASAPI exclusivo */

typedef HANDLE (WINAPI *PFN_AvSet)(LPCWSTR, LPDWORD);
typedef BOOL   (WINAPI *PFN_AvRevert)(HANDLE);

static DWORD channel_mask(int ch)
{
    switch (ch) {
        case 1: return 0x4;
        case 2: return 0x3;
        case 4: return 0x33;
        case 6: return 0x3F;
        case 8: return 0x63F;
        default: return 0;
    }
}

static ma_format fmt_for(int container)
{
    if (container == 16) return ma_format_s16;
    if (container == 24) return ma_format_s24;
    return ma_format_s32;
}

static DWORD WINAPI excl_thread(LPVOID arg)
{
    ma_IMMDeviceEnumerator* en = NULL;
    ma_IMMDevice* dev = NULL;
    ma_IAudioClient* ac = NULL;
    ma_IAudioRenderClient* rc = NULL;
    HANDLE evt = NULL, mm = NULL;
    HMODULE avrt = NULL;
    PFN_AvRevert avRevert = NULL;
    ma_uint32 bufFrames = 0;
    HRESULT hr;
    int i, ok = 0;
    int cands[4][2];
    int nc = 0;
    (void)arg;

    CoInitializeEx(NULL, COINIT_MULTITHREADED);

    if (g_reqBits <= 16) {
        cands[0][0] = 16; cands[0][1] = 16; cands[1][0] = 24; cands[1][1] = 24;
        cands[2][0] = 32; cands[2][1] = 24; cands[3][0] = 32; cands[3][1] = 32; nc = 4;
    } else if (g_reqBits <= 24) {
        cands[0][0] = 24; cands[0][1] = 24; cands[1][0] = 32; cands[1][1] = 24;
        cands[2][0] = 32; cands[2][1] = 32; nc = 3;
    } else {
        cands[0][0] = 32; cands[0][1] = 32; cands[1][0] = 32; cands[1][1] = 24;
        cands[2][0] = 24; cands[2][1] = 24; nc = 3;
    }

    hr = CoCreateInstance(&MA_CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL, &MA_IID_IMMDeviceEnumerator, (void**)&en);
    if (FAILED(hr)) { set_err("No se pudo abrir el sistema de audio"); goto fail; }
    if (g_reqHasId) hr = ma_IMMDeviceEnumerator_GetDevice(en, g_reqId, &dev);
    else            hr = ma_IMMDeviceEnumerator_GetDefaultAudioEndpoint(en, ma_eRender, ma_eConsole, &dev);
    if (FAILED(hr)) { set_err("No hay salida de audio"); goto fail; }

    for (i = 0; i < nc && !ok; i++) {
        MA_WAVEFORMATEXTENSIBLE wf;
        MA_REFERENCE_TIME defP = 0, minP = 0, period;
        memset(&wf, 0, sizeof(wf));
        wf.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
        wf.nChannels = (WORD)g_reqCh;
        wf.nSamplesPerSec = (DWORD)g_reqRate;
        wf.wBitsPerSample = (WORD)cands[i][0];
        wf.nBlockAlign = (WORD)(wf.nChannels * wf.wBitsPerSample / 8);
        wf.nAvgBytesPerSec = wf.nSamplesPerSec * wf.nBlockAlign;
        wf.cbSize = 22;
        wf.Samples.wValidBitsPerSample = (WORD)cands[i][1];
        wf.dwChannelMask = channel_mask(g_reqCh);
        wf.SubFormat = SA_SUBTYPE_PCM;

        hr = ma_IMMDevice_Activate(dev, &MA_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&ac);
        if (FAILED(hr)) continue;
        hr = ma_IAudioClient_IsFormatSupported(ac, MA_AUDCLNT_SHAREMODE_EXCLUSIVE, (MA_WAVEFORMATEX*)&wf, NULL);
        if (hr != S_OK) { ma_IAudioClient_Release(ac); ac = NULL; continue; }

        ma_IAudioClient_GetDevicePeriod(ac, &defP, &minP);
        period = defP < 100000 ? 100000 : defP;  /* 10 ms o más: estable */
        hr = ma_IAudioClient_Initialize(ac, MA_AUDCLNT_SHAREMODE_EXCLUSIVE, MA_AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, (MA_WAVEFORMATEX*)&wf, NULL);
        if (hr == MA_AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
            ma_uint32 n = 0;
            ma_IAudioClient_GetBufferSize(ac, &n);
            ma_IAudioClient_Release(ac); ac = NULL;
            period = (MA_REFERENCE_TIME)(10000000.0 * n / g_reqRate + 0.5);
            hr = ma_IMMDevice_Activate(dev, &MA_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&ac);
            if (FAILED(hr)) continue;
            hr = ma_IAudioClient_Initialize(ac, MA_AUDCLNT_SHAREMODE_EXCLUSIVE, MA_AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, (MA_WAVEFORMATEX*)&wf, NULL);
        }
        if (FAILED(hr)) {
            if (hr == (HRESULT)0x8889000A) set_err("Otra app está usando el DAC en exclusivo");
            ma_IAudioClient_Release(ac); ac = NULL; continue;
        }
        g_fmt = fmt_for(cands[i][0]);
        g_outBits = cands[i][0];
        g_outValid = cands[i][1];
        g_outRate = g_reqRate;
        g_outCh = g_reqCh;
        ok = 1;
    }
    if (!ok) { if (!g_err[0]) set_err("El DAC no acepta este formato en exclusivo"); goto fail; }

    evt = CreateEventW(NULL, FALSE, FALSE, NULL);
    hr = ma_IAudioClient_SetEventHandle(ac, evt);
    if (FAILED(hr)) { set_err("SetEventHandle"); goto fail; }
    hr = ma_IAudioClient_GetService(ac, &MA_IID_IAudioRenderClient, (void**)&rc);
    if (FAILED(hr)) { set_err("GetService"); goto fail; }
    ma_IAudioClient_GetBufferSize(ac, &bufFrames);

    {
        BYTE* data = NULL;
        if (SUCCEEDED(ma_IAudioRenderClient_GetBuffer(rc, bufFrames, &data))) {
            ma_silence_pcm_frames(data, bufFrames, g_fmt, (ma_uint32)g_outCh);
            ma_IAudioRenderClient_ReleaseBuffer(rc, bufFrames, 0);
        }
    }

    avrt = LoadLibraryW(L"avrt.dll");
    if (avrt) {
        PFN_AvSet avSet = (PFN_AvSet)GetProcAddress(avrt, "AvSetMmThreadCharacteristicsW");
        avRevert = (PFN_AvRevert)GetProcAddress(avrt, "AvRevertMmThreadCharacteristics");
        if (avSet) { DWORD idx = 0; mm = avSet(L"Pro Audio", &idx); }
    }

    hr = ma_IAudioClient_Start(ac);
    if (FAILED(hr)) { set_err("No arrancó la salida"); goto fail; }

    g_openResult = 1;
    SetEvent(g_ready);

    while (!g_stop) {
        BYTE* data = NULL;
        DWORD w = WaitForSingleObject(evt, 2000);
        if (g_stop) break;
        if (w != WAIT_OBJECT_0) continue;
        hr = ma_IAudioRenderClient_GetBuffer(rc, bufFrames, &data);
        if (FAILED(hr)) { g_lost = 1; break; }
        fill(data, bufFrames);
        ma_IAudioRenderClient_ReleaseBuffer(rc, bufFrames, 0);
    }
    ma_IAudioClient_Stop(ac);
    goto cleanup;

fail:
    g_openResult = -1;
    SetEvent(g_ready);
cleanup:
    if (mm && avRevert) avRevert(mm);
    if (avrt) FreeLibrary(avrt);
    if (rc) ma_IAudioRenderClient_Release(rc);
    if (ac) ma_IAudioClient_Release(ac);
    if (evt) CloseHandle(evt);
    if (dev) ma_IMMDevice_Release(dev);
    if (en) ma_IMMDeviceEnumerator_Release(en);
    CoUninitialize();
    return 0;
}

/* ---------------------------------------------------------------- salida */

static void close_output(void)
{
    if (g_mode == 1) {
        g_stop = 1;
        if (g_thread) {
            WaitForSingleObject(g_thread, 5000);
            CloseHandle(g_thread);
            g_thread = NULL;
        }
    } else if (g_mode == 2) {
        ma_device_uninit(&g_dev);
    }
    g_mode = 0;
}

static int find_device(int index, ma_device_info* out)
{
    ma_device_info* pb = NULL;
    ma_uint32 n = 0;
    if (index < 0 || !g_ctxOk) return 0;
    if (ma_context_get_devices(&g_ctx, &pb, &n, NULL, NULL) != MA_SUCCESS) return 0;
    if ((ma_uint32)index >= n) return 0;
    *out = pb[index];
    return 1;
}

static int open_exclusive(int rate, int ch, int bits, int devIndex)
{
    ma_device_info info;
    g_reqRate = rate; g_reqCh = ch; g_reqBits = bits;
    g_reqHasId = 0;
    if (find_device(devIndex, &info)) {
        memcpy(g_reqId, info.id.wasapi, sizeof(g_reqId));
        g_reqHasId = 1;
    }
    g_stop = 0; g_lost = 0; g_openResult = 0;
    ResetEvent(g_ready);
    g_thread = CreateThread(NULL, 0, excl_thread, NULL, 0, NULL);
    if (!g_thread) return 0;
    WaitForSingleObject(g_ready, 8000);
    if (g_openResult != 1) {
        g_stop = 1;
        WaitForSingleObject(g_thread, 3000);
        CloseHandle(g_thread);
        g_thread = NULL;
        return 0;
    }
    g_mode = 1;
    return 1;
}

static int open_shared(int rate, int ch, int devIndex)
{
    ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
    ma_device_info info;
    cfg.playback.format = ma_format_f32;
    cfg.playback.channels = (ma_uint32)ch;
    cfg.sampleRate = (ma_uint32)rate;
    cfg.dataCallback = shared_cb;
    cfg.performanceProfile = ma_performance_profile_conservative;
    if (find_device(devIndex, &info)) cfg.playback.pDeviceID = &info.id;
    g_fmt = ma_format_f32;
    g_outCh = ch;
    if (ma_device_init(&g_ctx, &cfg, &g_dev) != MA_SUCCESS) { set_err("No se pudo abrir la salida de audio"); return 0; }
    g_outRate = (int)g_dev.playback.internalSampleRate;
    g_outBits = (int)ma_get_bytes_per_sample(g_dev.playback.internalFormat) * 8;
    g_outValid = g_outBits;
    if (ma_device_start(&g_dev) != MA_SUCCESS) { ma_device_uninit(&g_dev); set_err("No arrancó la salida"); return 0; }
    g_mode = 2;
    return 1;
}

static int probe(const wchar_t* path, int* rate, int* ch)
{
    ma_decoder d;
    ma_decoder_config c = ma_decoder_config_init(ma_format_unknown, 0, 0);
    ma_format f; ma_uint32 cc, r;
    if (ma_decoder_init_file_w(path, &c, &d) != MA_SUCCESS) return 0;
    ma_decoder_get_data_format(&d, &f, &cc, &r, NULL, 0);
    ma_decoder_uninit(&d);
    *rate = (int)r; *ch = (int)cc;
    return 1;
}

static ma_decoder* open_dec(const wchar_t* path)
{
    ma_decoder* d = (ma_decoder*)ma_malloc(sizeof(ma_decoder), NULL);
    ma_decoder_config c = ma_decoder_config_init(g_fmt, (ma_uint32)g_outCh, (ma_uint32)g_srcRate);
    if (!d) return NULL;
    /* en compartido miniaudio remuestrea en el dispositivo; el decodificador entrega la frecuencia original */
    if (ma_decoder_init_file_w(path, &c, d) != MA_SUCCESS) { ma_free(d, NULL); return NULL; }
    return d;
}

static void free_dec(ma_decoder** d)
{
    if (*d) { ma_decoder_uninit(*d); ma_free(*d, NULL); *d = NULL; }
}

/* ---------------------------------------------------------------- API */

SA_API int sa_init(void)
{
    ma_backend be[1] = { ma_backend_wasapi };
    ma_context_config cc = ma_context_config_init();
    if (g_ctxOk) return 1;
    if (ma_mutex_init(&g_lock) != MA_SUCCESS) return 0;
    g_ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (ma_context_init(be, 1, &cc, &g_ctx) != MA_SUCCESS) return 0;
    g_ctxOk = 1;
    return 1;
}

/* Abre un archivo. bits = profundidad del archivo (16/24/32, 0 si no se sabe).
   exclusive = 1 intenta bit-perfect. Devuelve 1 exclusivo, 2 compartido, 0 error. */
SA_API int sa_load(const wchar_t* path, int bits, int exclusive, int devIndex)
{
    int rate = 0, ch = 0;
    ma_decoder* d;
    g_err[0] = 0;
    g_wantExcl = exclusive;
    if (!g_ctxOk) return 0;
    if (!probe(path, &rate, &ch)) { set_err("No se pudo leer el archivo"); return 0; }
    if (bits <= 0) bits = 16;

    g_playing = 0;
    ma_mutex_lock(&g_lock);
    free_dec(&g_next);
    free_dec(&g_cur);
    ma_mutex_unlock(&g_lock);

    /* misma salida y mismo formato: solo cambiamos el decodificador, sin cortes */
    if (!(g_mode != 0 && !g_lost && rate == g_srcRate && ch == g_srcCh && bits == g_srcBits
          && devIndex == g_devIndex && ((g_mode == 1) == (exclusive != 0)))) {
        close_output();
        g_srcRate = rate; g_srcCh = ch; g_srcBits = bits;
        g_devIndex = devIndex;
        if (!(exclusive && open_exclusive(rate, ch, bits, devIndex))) {
            if (!open_shared(rate, ch, devIndex)) return 0;
        }
    }

    d = open_dec(path);
    if (!d) { set_err("No se pudo decodificar el archivo"); return 0; }
    ma_mutex_lock(&g_lock);
    g_cur = d;
    wcsncpy(g_curPath, path, 1023); g_curPath[1023] = 0;
    g_ended = 0;
    ma_mutex_unlock(&g_lock);
    return g_mode;
}

/* Prepara la siguiente pista para que suene sin pausa. 1 si quedó en cola. */
SA_API int sa_queue(const wchar_t* path, int bits)
{
    int rate = 0, ch = 0;
    ma_decoder* d;
    if (g_mode == 0 || !probe(path, &rate, &ch)) return 0;
    if (bits <= 0) bits = 16;
    if (rate != g_srcRate || ch != g_srcCh || bits != g_srcBits) return 0;
    d = open_dec(path);
    if (!d) return 0;
    ma_mutex_lock(&g_lock);
    free_dec(&g_next);
    g_next = d;
    wcsncpy(g_nextPath, path, 1023); g_nextPath[1023] = 0;
    ma_mutex_unlock(&g_lock);
    return 1;
}

SA_API void sa_clear_queue(void)
{
    ma_mutex_lock(&g_lock);
    free_dec(&g_next);
    ma_mutex_unlock(&g_lock);
}

/* Reabre la salida si estaba suspendida. Si el formato del DAC cambió, reabre los decodificadores. */
static int reopen(void)
{
    ma_format oldFmt = g_fmt;
    int oldCh = g_outCh;
    ma_uint64 pos = 0;
    if (g_mode != 0) return 1;
    if (!(g_wantExcl && open_exclusive(g_srcRate, g_srcCh, g_srcBits, g_devIndex))) {
        if (!open_shared(g_srcRate, g_srcCh, g_devIndex)) return 0;
    }
    if (g_fmt != oldFmt || g_outCh != oldCh) {
        ma_decoder* d;
        ma_mutex_lock(&g_lock);
        if (g_cur) ma_decoder_get_cursor_in_pcm_frames(g_cur, &pos);
        free_dec(&g_cur);
        free_dec(&g_next);
        ma_mutex_unlock(&g_lock);
        d = open_dec(g_curPath);
        if (!d) return 0;
        ma_decoder_seek_to_pcm_frame(d, pos);
        ma_mutex_lock(&g_lock);
        g_cur = d;
        ma_mutex_unlock(&g_lock);
    }
    return 1;
}

SA_API int sa_play(void)
{
    if (!g_cur) return 0;
    if (g_mode == 0 && !reopen()) return 0;
    g_ended = 0;
    g_playing = 1;
    return g_mode;
}

SA_API void sa_pause(void) { g_playing = 0; }

/* Suelta el DAC (para que otras apps suenen) sin perder la posición. */
SA_API void sa_suspend(void)
{
    g_playing = 0;
    close_output();
}

SA_API void sa_seek(long long frame)
{
    ma_mutex_lock(&g_lock);
    if (g_cur) {
        if (frame < 0) frame = 0;
        ma_decoder_seek_to_pcm_frame(g_cur, (ma_uint64)frame);
        g_ended = 0;
    }
    ma_mutex_unlock(&g_lock);
}

SA_API long long sa_cursor(void)
{
    ma_uint64 c = 0;
    ma_mutex_lock(&g_lock);
    if (g_cur) ma_decoder_get_cursor_in_pcm_frames(g_cur, &c);
    ma_mutex_unlock(&g_lock);
    return (long long)c;
}

SA_API long long sa_length(void)
{
    ma_uint64 c = 0;
    ma_mutex_lock(&g_lock);
    if (g_cur) ma_decoder_get_length_in_pcm_frames(g_cur, &c);
    ma_mutex_unlock(&g_lock);
    return (long long)c;
}

SA_API void sa_volume(float v)
{
    if (v < 0) v = 0;
    if (v > 1) v = 1;
    g_volume = v;
}

/* info[0..11]: srcRate srcCh srcBits outRate outCh outBits outValid modo cambios fin sonando perdido */
SA_API void sa_info(int* info)
{
    info[0] = g_srcRate; info[1] = g_srcCh; info[2] = g_srcBits;
    info[3] = g_outRate; info[4] = g_outCh; info[5] = g_outBits; info[6] = g_outValid;
    info[7] = g_mode; info[8] = g_changes; info[9] = g_ended; info[10] = g_playing; info[11] = g_lost;
}

SA_API void sa_error(char* buf, int len)
{
    if (len <= 0) return;
    strncpy(buf, g_err, (size_t)len - 1);
    buf[len - 1] = 0;
}

SA_API int sa_device_count(void)
{
    ma_device_info* pb = NULL;
    ma_uint32 n = 0;
    if (!g_ctxOk) return 0;
    if (ma_context_get_devices(&g_ctx, &pb, &n, NULL, NULL) != MA_SUCCESS) return 0;
    return (int)n;
}

SA_API int sa_device_name(int index, char* buf, int len)
{
    ma_device_info info;
    if (len <= 0) return 0;
    buf[0] = 0;
    if (!find_device(index, &info)) return 0;
    strncpy(buf, info.name, (size_t)len - 1);
    buf[len - 1] = 0;
    return info.isDefault ? 2 : 1;
}

/* Forma de onda: count picos (0..1) de todo el archivo. */
SA_API int sa_peaks(const wchar_t* path, int count, float* out)
{
    ma_decoder d;
    ma_decoder_config c = ma_decoder_config_init(ma_format_f32, 1, 0);
    ma_uint64 len = 0, pos = 0, got = 0;
    float buf[8192];
    int i;
    if (count <= 0) return 0;
    for (i = 0; i < count; i++) out[i] = 0;
    if (ma_decoder_init_file_w(path, &c, &d) != MA_SUCCESS) return 0;
    ma_decoder_get_length_in_pcm_frames(&d, &len);
    if (len == 0) { ma_decoder_uninit(&d); return 0; }
    for (;;) {
        ma_uint64 k;
        if (ma_decoder_read_pcm_frames(&d, buf, 8192, &got) != MA_SUCCESS && got == 0) break;
        if (got == 0) break;
        for (k = 0; k < got; k++) {
            int b = (int)((pos + k) * (ma_uint64)count / len);
            float v = buf[k] < 0 ? -buf[k] : buf[k];
            if (b >= count) b = count - 1;
            if (v > out[b]) out[b] = v;
        }
        pos += got;
    }
    ma_decoder_uninit(&d);
    return 1;
}

SA_API void sa_shutdown(void)
{
    g_playing = 0;
    close_output();
    ma_mutex_lock(&g_lock);
    free_dec(&g_next);
    free_dec(&g_cur);
    ma_mutex_unlock(&g_lock);
    if (g_ctxOk) { ma_context_uninit(&g_ctx); g_ctxOk = 0; }
}
