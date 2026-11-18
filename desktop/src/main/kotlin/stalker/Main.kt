package stalker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Cursor
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class View { Sala, Shelf }

class AppState(val player: Player) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var albums by mutableStateOf<List<Album>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var scanCount by mutableIntStateOf(0); private set
    var folders by mutableStateOf(Prefs.folders); private set
    var favorites by mutableStateOf(Prefs.favorites); private set

    var view by mutableStateOf(View.Sala)
    var settings by mutableStateOf(false)
    var mini by mutableStateOf(false)
    var immersive by mutableStateOf(false)
    var query by mutableStateOf("")
    var shelfMode by mutableIntStateOf(0)
    var typing by mutableStateOf(false)
    /** Parte de abajo del panel: 0 pistas, 1 Jam. */
    var panelTab by mutableIntStateOf(0)
    var notice by mutableStateOf<String?>(null)
    var floatDisc by mutableStateOf(Prefs.floatDisc)
    var mainWindow: java.awt.Window? = null

    fun start() {
        Jam.attach(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var n = 0
            while (true) {
                delay(500)
                Jam.tick(this@AppState, ++n)
            }
        }
        io.launch {
            val cached = Library.loadCache()
            if (cached.isNotEmpty()) publish(cached)
            if (player.queue.isEmpty()) withContext(Dispatchers.Main) { player.restore(cached) }
            scan()
            // importación automática: cada pocos minutos revisa si llegó música nueva
            while (true) {
                delay(3 * 60_000L)
                scan()
            }
        }
    }

    private fun publish(list: List<Track>) {
        tracks = list
        albums = Library.albums(list).sortedWith(compareBy({ it.artist.lowercase() }, { it.year }, { it.title.lowercase() }))
    }

    suspend fun scan() {
        if (scanning) return
        scanning = true
        try {
            val res = Library.scan(folders, tracks) { scanCount = it }
            publish(res)
            if (player.queue.isEmpty()) withContext(Dispatchers.Main) { player.restore(res) }
        } finally {
            scanning = false
        }
    }

    fun rescan() { io.launch { scan() } }

    fun addFolder() {
        io.launch {
            val dir = withContext(Dispatchers.Main) {
                val fc = JFileChooser(System.getProperty("user.home"))
                fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                fc.dialogTitle = "Elige una carpeta con música"
                if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
            } ?: return@launch
            folders = (folders + dir.absolutePath).distinct()
            Prefs.folders = folders
            scan()
        }
    }

    /** Abre un disco compartido desde el celular (.stalker.zip) y lo guarda en Música\\Stalker. */
    fun importDisc(file: File) {
        io.launch {
            notice = "Abriendo ${file.name}…"
            val res = try { Discs.import(file) } catch (t: Throwable) { null }
            if (res == null) { notice = "No se pudo abrir ${file.name}"; return@launch }
            val root = res.parentFile.parentFile.absolutePath
            if (folders.none { root.startsWith(it, ignoreCase = true) || res.absolutePath.startsWith(it, ignoreCase = true) }) {
                folders = folders + root
                Prefs.folders = folders
            }
            scan()
            notice = "Disco «${res.name}» agregado a la estantería"
            delay(5000)
            notice = null
        }
    }

    fun pickDisc() {
        io.launch {
            val f = withContext(Dispatchers.Main) {
                val fc = JFileChooser(System.getProperty("user.home") + File.separator + "Downloads")
                fc.dialogTitle = "Abrir un disco compartido (.stalker.zip)"
                fc.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Disco de Stalker (.zip)", "zip")
                if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
            } ?: return@launch
            importDisc(f)
        }
    }

    fun removeFolder(f: String) {
        folders = folders - f
        Prefs.folders = folders
        rescan()
    }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        Prefs.favorites = favorites
    }

    fun placeOnDeck(a: Album) {
        player.setQueue(a.tracks, 0, a.title, play = false)
        view = View.Sala
    }

    fun playAlbum(a: Album) {
        player.setQueue(a.tracks, 0, a.title, play = true)
        view = View.Sala
    }
}

/* ------------------------------------------------------------------ ventana */

private interface Dwm : com.sun.jna.Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
}

/** Windows 11: esquinas redondeadas, sombra nativa y barra oscura aunque la ventana no tenga marco. */
fun polishWindow(w: java.awt.Window, round: Boolean = true) {
    try {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        val hwnd = Native.getWindowPointer(w) ?: return
        dwm.DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4)          // modo oscuro
        dwm.DwmSetWindowAttribute(hwnd, 33, IntByReference(if (round) 2 else 1), 4) // esquinas redondeadas
        dwm.DwmSetWindowAttribute(hwnd, 34, IntByReference(0x00060A0B), 4)  // borde casi negro
    } catch (_: Throwable) {}
}

val version: String get() = System.getProperty("stalker.version") ?: "1.0"

fun main(args: Array<String>) {
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Throwable) {}
    Library.quiet()
    val player = Player()
    val app = AppState(player)
    app.start()
    args.firstOrNull { it.endsWith(".zip", true) }?.let { app.importDisc(File(it)) }
    Runtime.getRuntime().addShutdownHook(Thread { player.shutdown() })

    application {
        val icon = remember { BitmapPainter(Res.logo) }
        val mainState = rememberWindowState(size = DpSize(1320.dp, 840.dp), position = WindowPosition(Alignment.Center))

        Window(
            onCloseRequest = { player.shutdown(); exitApplication() },
            state = mainState,
            visible = !app.mini,
            title = "Stalker",
            icon = icon,
            undecorated = true,
            resizable = true,
            onPreviewKeyEvent = { e -> handleKey(app, e, mainState) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(980, 640)
                polishWindow(window)
                app.mainWindow = window
                // sin aceleración gráfica (PC modesto o sin drivers): modo ligero automático, una sola vez
                if (!Prefs.liteAsked) {
                    Prefs.liteAsked = true
                    if (window.renderApi.name.startsWith("SOFTWARE")) Prefs.lite = true
                }
            }
            MainWindow(app, this, mainState)
        }

        LaunchedEffect(mainState.isMinimized, app.mini) { player.background = mainState.isMinimized || app.mini }
        if (app.mini) MiniWindow(app, icon)
        if (mainState.isMinimized && !app.mini && app.floatDisc && player.queue.isNotEmpty()) {
            FloatingDisc(app, icon) {
                mainState.isMinimized = false
                app.mainWindow?.let { it.isVisible = true; it.toFront(); it.requestFocus() }
            }
        }
    }
}

private fun handleKey(app: AppState, e: androidx.compose.ui.input.key.KeyEvent, st: androidx.compose.ui.window.WindowState): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (app.typing && e.key != Key.Escape) return false
    val p = app.player
    when (e.key) {
        Key.Spacebar -> p.toggle()
        Key.DirectionRight -> if (e.isCtrlPressed) p.next() else p.seekBy(if (e.isShiftPressed) 1000 else 5000)
        Key.DirectionLeft -> if (e.isCtrlPressed) p.prev() else p.seekBy(if (e.isShiftPressed) -1000 else -5000)
        Key.DirectionUp -> p.changeVolume(p.volume + 0.05f)
        Key.DirectionDown -> p.changeVolume(p.volume - 0.05f)
        Key.F -> {
            app.immersive = !app.immersive
            app.view = View.Sala
            st.placement = if (app.immersive) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        Key.Escape -> {
            if (app.settings) app.settings = false
            else if (app.immersive) { app.immersive = false; st.placement = WindowPlacement.Floating }
            else return false
        }
        Key.M -> app.mini = true
        Key.One -> app.view = View.Sala
        Key.Two -> app.view = View.Shelf
        else -> return false
    }
    return true
}

@Composable
private fun MainWindow(app: AppState, scope: FrameWindowScope, st: androidx.compose.ui.window.WindowState) {
    Jam.version
    var restoreBounds by remember { mutableStateOf<Rectangle?>(null) }
    val window = scope.window
    fun toggleMax() {
        val r = restoreBounds
        if (r != null) {
            window.bounds = r
            restoreBounds = null
        } else {
            restoreBounds = window.bounds
            val gc = window.graphicsConfiguration
            val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val b = gc.bounds
            window.bounds = Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
        }
    }

    Box(Modifier.fillMaxSize().background(C.Sombra)) {
        // las dos vistas quedan vivas: cambiar es solo un fundido entre imágenes ya pintadas
        // una sola sala y una cámara: va del tocadiscos a la estantería con un desenfoque de foco
        val cam = androidx.compose.animation.core.animateFloatAsState(
            if (app.view == View.Shelf) 1f else 0f,
            tween(620, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
        KeptView(cam, side = -1f, active = app.view == View.Sala) { SalaScreen(app) }
        KeptView(cam, side = 1f, active = app.view == View.Shelf) { ShelfScreen(app) }

        // barra superior
        if (!app.immersive) {
            Box(Modifier.fillMaxWidth().height(52.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))) {
                scope.WindowDraggableArea(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            var last = 0L
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                    val now = System.currentTimeMillis()
                                    if (now - last < 350) toggleMax()
                                    last = now
                                }
                            }
                        }
                    })
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(BitmapPainter(Res.logo), null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    T("S T A L K E R", 12, FontWeight.SemiBold, C.Crema, spacing = 1.5f)
                    Spacer(Modifier.weight(1f))
                    // selector central estilo Mac
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0x1AF1DFC4)).border(1.dp, C.Linea, RoundedCornerShape(50)).padding(3.dp),
                    ) {
                        Seg("Sala", app.view == View.Sala) { app.view = View.Sala }
                        Seg("Estantería", app.view == View.Shelf) { app.view = View.Shelf }
                    }
                    Spacer(Modifier.weight(1f))
                    RoundButton(Ico.Jam, {
                        app.view = View.Sala; app.panelTab = 1
                    }, 30.dp, 15.dp, tint = if (Jam.core.active) C.RojoVivo else C.Tenue)
                    Spacer(Modifier.width(4.dp))
                    RoundButton(Ico.Gear, { app.settings = true }, 30.dp, 15.dp, tint = C.Tenue)
                    Spacer(Modifier.width(4.dp))
                    RoundButton(Ico.Expand, {
                        app.immersive = true; app.view = View.Sala; st.placement = WindowPlacement.Fullscreen
                    }, 30.dp, 14.dp, tint = C.Tenue)
                    Spacer(Modifier.width(14.dp))
                    WinButton(Ico.Mini, "Modo mini") { app.mini = true }
                    WinButton(Ico.Minimize, "Minimizar") { st.isMinimized = true }
                    WinButton(if (restoreBounds != null) Ico.Restore else Ico.Maximize, "Maximizar") { toggleMax() }
                    WinButton(Ico.Close, "Cerrar", danger = true) { window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
                }
            }
        } else {
            // en la sala inmersiva solo un botón discreto para volver
            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.TopEnd) {
                RoundButton(Ico.Close, { app.immersive = false; st.placement = WindowPlacement.Floating }, 32.dp, 13.dp, tint = C.Tenue)
            }
        }

        // agarre para cambiar el tamaño (la ventana no tiene marco)
        if (!app.immersive && restoreBounds == null) {
            ResizeGrip(window, Modifier.align(Alignment.BottomEnd))
        }

        app.notice?.let { n ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp).clip(RoundedCornerShape(50)).background(C.Crema).padding(horizontal = 18.dp, vertical = 10.dp)) {
                T(n, 12, FontWeight.Medium, C.Sombra)
            }
        }
        AnimatedVisibility(app.settings, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            SettingsSheet(app)
        }
    }
}

/**
 * Una vista de la sala vista por la cámara. [side] -1 = tocadiscos (a la izquierda), 1 = estantería.
 * Mientras la cámara viaja, la vista que se va se desenfoca y la que llega entra en foco.
 * Solo hay desenfoque durante el viaje (medio segundo); quietas, son imágenes ya pintadas.
 */
@Composable
private fun KeptView(cam: androidx.compose.runtime.State<Float>, side: Float, active: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().zIndex(if (active) 1f else 0f).graphicsLayer {
        val c = cam.value
        // 0 cuando esta vista está en foco, 1 cuando la cámara mira la otra
        val away = if (side < 0) c else 1f - c
        translationX = side * away * size.width * 0.32f
        val sc = 1f + away * 0.10f
        scaleX = sc; scaleY = sc
        alpha = if (side < 0) 1f else (1f - away)
        val blur = away * 22f
        renderEffect = if (!Prefs.lite && blur > 0.5f && blur < 21.5f) androidx.compose.ui.graphics.BlurEffect(blur, blur, androidx.compose.ui.graphics.TileMode.Clamp) else null
    }) {
        content()
        // la vista que no está en foco no recibe clics ni el paso del mouse
        if (!active) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })
    }
}

@Composable
private fun Seg(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) C.Crema else Color.Transparent)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) { T(label, 12, FontWeight.Medium, if (active) C.Sombra else C.Crema) }
}

@Composable
private fun WinButton(ico: Ico, @Suppress("UNUSED_PARAMETER") hint: String, danger: Boolean = false, onClick: () -> Unit) {
    RoundButton(ico, onClick, 32.dp, 13.dp, tint = C.Crema.copy(alpha = 0.85f), hoverBg = if (danger) Color(0xFFB3261E) else Color(0x26F1DFC4))
}

@Composable
private fun ResizeGrip(window: java.awt.Window, modifier: Modifier) {
    Canvas(
        modifier.size(18.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { ch, d ->
                    ch.consume()
                    val s = window.size
                    val k = density
                    window.setSize(
                        (s.width + d.x / k).toInt().coerceAtLeast(window.minimumSize.width),
                        (s.height + d.y / k).toInt().coerceAtLeast(window.minimumSize.height),
                    )
                }
            }
    ) {
        for (i in 1..3) {
            val o = i * size.width / 4
            drawLine(C.Tenue.copy(alpha = 0.45f), Offset(size.width - 3, o), Offset(o, size.height - 3), 1.2f)
        }
    }
}

/* ------------------------------------------------------------------ ajustes */

@Composable
fun SettingsSheet(app: AppState) {
    val p = app.player
    val devices = remember(app.settings) { Audio.devices() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { app.settings = false },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(600.dp).heightIn(max = 720.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("Ajustes", 24, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RoundButton(Ico.Close, { app.settings = false }, 32.dp, 13.dp)
                }
                Spacer(Modifier.height(22.dp))

                Section("SALIDA DE AUDIO")
                Toggle(
                    "Modo exclusivo · bit-perfect",
                    "Stalker toma el DAC solo para sí y lo pone a la frecuencia exacta de cada archivo. Al pausar, lo suelta para que el resto del PC suene.",
                    p.exclusive,
                ) { p.changeExclusive(it) }
                Spacer(Modifier.height(14.dp))
                T("Dispositivo", 12, FontWeight.Medium, C.Tenue)
                Spacer(Modifier.height(8.dp))
                DeviceOption("Predeterminado de Windows", p.device == -1) { p.changeDevice(-1) }
                devices.forEachIndexed { i, (name, def) ->
                    DeviceOption(name + if (def) "  ·  actual" else "", p.device == i) { p.changeDevice(i) }
                }
                Spacer(Modifier.height(10.dp))
                SignalChain(p)

                Spacer(Modifier.height(26.dp))
                Section("VENTANA Y RENDIMIENTO")
                Toggle(
                    "Disco flotante al minimizar",
                    "Al minimizar queda un vinilo girando sobre las demás ventanas. Pasa el mouse para pausar o cambiar de canción; doble clic para volver.",
                    app.floatDisc,
                ) { app.floatDisc = it; Prefs.floatDisc = it }
                Spacer(Modifier.height(14.dp))
                Toggle(
                    "Modo ligero",
                    "Para PCs modestos: el telón deja de moverse y el plato gira a 30 cuadros por segundo. El sonido no cambia.",
                    Prefs.lite,
                ) { Prefs.lite = it }

                Spacer(Modifier.height(26.dp))
                Section("CARPETAS DE MÚSICA")
                T("Stalker revisa estas carpetas cada pocos minutos y agrega la música nueva sola.", 11, color = C.Apagado, maxLines = 2)
                Spacer(Modifier.height(10.dp))
                app.folders.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x10F1DFC4)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ico.Folder, C.Tenue, 15.dp)
                        Spacer(Modifier.width(10.dp))
                        T(f, 12, modifier = Modifier.weight(1f))
                        if (!File(f).isDirectory) T("no existe", 10, color = C.RojoVivo)
                        RoundButton(Ico.Trash, { app.removeFolder(f) }, 28.dp, 13.dp, tint = C.Tenue)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Agregar carpeta", { app.addFolder() }, ico = Ico.Plus)
                    Pill(if (app.scanning) "Buscando…" else "Buscar música nueva", { app.rescan() }, ico = Ico.Search)
                }
                Spacer(Modifier.height(10.dp))
                Pill("Abrir disco compartido (.stalker.zip)", { app.pickDisc() }, ico = Ico.Shelf)
                T("Los discos que te mandan desde Stalker en el celular se guardan en Música\\Stalker.", 10, color = C.Apagado, maxLines = 2)

                Spacer(Modifier.height(26.dp))
                Section("ATAJOS")
                Shortcut("Espacio", "Reproducir / pausar")
                Shortcut("Flechas izq. / der.", "Adelantar o retroceder 5 s (Shift: 1 s)")
                Shortcut("Ctrl + flechas", "Canción anterior / siguiente")
                Shortcut("Flechas arriba / abajo", "Volumen")
                Shortcut("F", "Sala a pantalla completa")
                Shortcut("M", "Modo mini flotante")
                Shortcut("Rueda sobre el disco", "Mover la aguja")

                Spacer(Modifier.height(26.dp))
                Section("ACERCA DE")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(BitmapPainter(Res.logo), null, Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T("Stalker para Windows $version", 14, FontWeight.SemiBold)
                        T("Libre, gratis y sin anuncios. Para los que escuchan de verdad.", 11, color = C.Tenue, maxLines = 2)
                    }
                    Pill("GitHub", { open("https://github.com/sebastianbalbinu/Stalker") })
                }
            }
        }
    }
}

private fun open(url: String) {
    try { Desktop.getDesktop().browse(URI(url)) } catch (_: Throwable) {}
}

@Composable
private fun Section(s: String) {
    T(s, 10, FontWeight.SemiBold, C.Tenue, spacing = 2f)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pointerHoverIcon(Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(title, 14, FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            T(sub, 11, color = C.Apagado, maxLines = 3)
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(50)).background(if (on) C.Rojo else Color(0x33F1DFC4)).padding(3.dp)) {
            Box(Modifier.size(20.dp).align(if (on) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(C.Crema))
        }
    }
}

@Composable
private fun DeviceOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x1FF1DFC4) else Color.Transparent)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, if (selected) C.RojoVivo else C.Apagado, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(7.dp).clip(CircleShape).background(C.RojoVivo))
        }
        Spacer(Modifier.width(12.dp))
        T(name, 12, if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun Shortcut(k: String, v: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(150.dp)) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1AF1DFC4)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                T(k, 11, FontWeight.Medium)
            }
        }
        T(v, 12, color = C.Tenue)
    }
}

/* ------------------------------------------------------------------ modo mini */

@Composable
private fun MiniWindow(app: AppState, icon: BitmapPainter) {
    val p = app.player
    val pos = remember {
        val b = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = Prefs.miniX.takeIf { it >= 0 } ?: (b.x + b.width - 440)
        val y = Prefs.miniY.takeIf { it >= 0 } ?: (b.y + b.height - 160)
        WindowPosition(x.dp, y.dp)
    }
    val st = rememberWindowState(size = DpSize(410.dp, 120.dp), position = pos)
    Window(
        onCloseRequest = { app.mini = false },
        state = st,
        title = "Stalker",
        icon = icon,
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Spacebar) { p.toggle(); true } else false
        },
    ) {
        LaunchedEffect(Unit) { polishWindow(window) }
        DisposableEffect(Unit) {
            onDispose {
                Prefs.miniX = window.x
                Prefs.miniY = window.y
            }
        }
        val t = p.current
        val art = rememberArt(t?.path)
        Box(Modifier.fillMaxSize().background(C.Sombra)) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2A0606), Color(0xFF140B09), Color(0xFF0E0907))))
            ) {
                // el telón asoma a la izquierda
                Canvas(Modifier.width(120.dp).fillMaxHeight()) {
                    drawCover(Res.telon, alpha = 0.35f)
                    drawRect(Brush.horizontalGradient(listOf(Color(0x00140B09), Color(0xFF140B09))))
                }
                WindowDraggableArea(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
                Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniVinyl(art, p.playing, Modifier.size(92.dp).clip(CircleShape).clickable { p.toggle() })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        T(t?.title ?: "Stalker", 14, FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        T(t?.artist ?: "Nada en el plato", 11, color = C.Tenue)
                        Spacer(Modifier.height(4.dp))
                        if (t != null) T(t.quality + if (p.bitPerfect) " · bit-perfect" else "", 10, color = if (p.bitPerfect) C.Oro else C.Apagado)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Ico.Prev, { p.prev() }, 30.dp, 13.dp)
                            RoundButton(if (p.playing) Ico.Pause else Ico.Play, { p.toggle() }, 34.dp, 14.dp, tint = C.Sombra, filled = C.Crema)
                            RoundButton(Ico.Next, { p.next() }, 30.dp, 13.dp)
                        }
                    }
                    Column(Modifier.fillMaxHeight().padding(vertical = 10.dp)) {
                        RoundButton(Ico.Expand, { app.mini = false }, 28.dp, 12.dp, tint = C.Tenue)
                    }
                }
                // progreso
                val dur = t?.durationMs ?: 0
                Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter).padding(horizontal = 22.dp)) {
                    val f = if (dur > 0) (p.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    drawRect(Color(0x22F1DFC4))
                    drawRect(C.RojoVivo, size = size.copy(width = size.width * f))
                }
            }
        }
    }
}
