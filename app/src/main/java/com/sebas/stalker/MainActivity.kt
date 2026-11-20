package com.sebas.stalker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first
import com.sebas.stalker.ui.IntroScreen
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.sebas.stalker.ui.C
import com.sebas.stalker.ui.StalkerApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private val hasPermission = mutableStateOf(false)

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission.value = result[audioPermission] == true || granted()
        if (hasPermission.value) vm.rescan()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(Engine.exportJson().toByteArray()) }
            toast(tr("Respaldo guardado"))
        } catch (e: Exception) {
            toast(tr("No se pudo guardar el respaldo"))
        }
    }

    private val discLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importDisc(uri) }

    private fun importDisc(uri: Uri) {
        lifecycleScope.launch {
            toast(Sharing.importDisc(this@MainActivity, uri))
            vm.rescan()
        }
    }

    /**
     * Abrieron algo con Stalker desde otra app (WhatsApp, Archivos, Drive…):
     * un disco (.stalker.zip, aunque venga como archivo genérico) se importa;
     * una o varias canciones se guardan en la biblioteca y empiezan a sonar.
     */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val declared = intent.type
        intent.action = null
        lifecycleScope.launch {
            val kinds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.map { it to Sharing.kindOf(this@MainActivity, it, declared) }
            }
            val discs = kinds.filter { it.second == Sharing.Kind.DISC }.map { it.first }
            val songs = kinds.filter { it.second == Sharing.Kind.SONG }.map { it.first }
            for (d in discs) toast(Sharing.importDisc(this@MainActivity, d))
            if (songs.isNotEmpty()) playIncoming(songs)
            else if (discs.isEmpty()) toast(tr("Ese archivo no es un disco ni una canción"))
            if (discs.isNotEmpty() && songs.isEmpty()) vm.rescan()
        }
    }

    /** Guarda las canciones, espera a que Android las registre y las pone a sonar. */
    private suspend fun playIncoming(uris: List<Uri>) {
        val ids = Sharing.importSongs(this, uris)
        if (ids.isEmpty()) {
            toast(tr("No se pudo abrir el archivo"))
            return
        }
        var tracks: List<Track> = emptyList()
        for (attempt in 0 until 8) {
            Engine.setTracks(Library.scan(this))
            tracks = ids.mapNotNull { Engine.track(it.toString()) }
            if (tracks.size == ids.size) break
            kotlinx.coroutines.delay(500)
        }
        vm.awaitReady()
        if (tracks.isEmpty()) {
            toast(tr("La canción se guardó en Música/Stalker/Recibidos"))
            return
        }
        vm.playList(tracks, 0, if (tracks.size == 1) tracks.first().album else tr("Recibidos"))
        toast(if (tracks.size == 1) tr("Guardada en tu biblioteca · Recibidos") else tr("%s canciones guardadas en tu biblioteca", tracks.size))
        vm.rescan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@registerForActivityResult
            toast(Engine.importJson(text))
        } catch (e: Exception) {
            toast(tr("Ese archivo no es un respaldo de Stalker"))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        launcher.launch(perms.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        Updates.foreground.value = true
        vm.checkUpdatesQuiet()
        // Al volver a la app, revisa si hay música nueva.
        if (Engine.scanned.value && granted()) vm.rescan()
    }

    override fun onStop() {
        Updates.foreground.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Engine.init(this)
        hasPermission.value = granted()
        if (hasPermission.value) {
            if (!Engine.scanned.value) vm.rescan()
        } else {
            requestPermissions()
        }
        handleIncoming(intent)
        setContent {
            val night by Engine.nightMode.collectAsState()
            val system = isSystemInDarkTheme()
            val dark = when (night) {
                "Noche" -> true
                "Claro" -> false
                else -> system
            }
            // Pantalla de carga: el disco gira mientras la biblioteca se prepara y se despide con un fundido suave.
            var intro by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
            val leave = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (intro) 0f else 1f) }
            LaunchedEffect(Unit) {
                if (!intro) return@LaunchedEffect
                kotlinx.coroutines.delay(900)
                kotlinx.coroutines.withTimeoutOrNull(2600) { Engine.scanned.first { it } }
                leave.animateTo(1f, androidx.compose.animation.core.tween(820, easing = com.sebas.stalker.ui.AppleEase))
                intro = false
            }
            LaunchedEffect(dark, intro) {
                C.dark = dark
                val style = if (dark || intro) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // Al cambiar de idioma se redibuja toda la interfaz.
            val language by I18n.choice.collectAsState()
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            // La app aparece con el fundido: sube la opacidad y se asienta desde un 97 %.
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                val p = leave.value
                alpha = p
                val sc = 0.97f + 0.03f * p
                scaleX = sc; scaleY = sc
            }) {
            androidx.compose.runtime.key(language) {
            StalkerApp(
                vm = vm,
                hasPermission = hasPermission.value,
                onRequestPermission = { requestPermissions() },
                onExport = {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("stalker-respaldo-$day.json")
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onImportDisc = { discLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            }
            }
            if (intro) IntroScreen { leave.value }
            }
        }
    }
}
