package com.sebas.stalker

import android.Manifest
import android.content.pm.PackageManager
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
            toast("Respaldo guardado")
        } catch (e: Exception) {
            toast("No se pudo guardar el respaldo")
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@registerForActivityResult
            toast(Engine.importJson(text))
        } catch (e: Exception) {
            toast("Ese archivo no es un respaldo de Stalker")
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
        // Al volver a la app, revisa si hay música nueva.
        if (Engine.scanned.value && granted()) vm.rescan()
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
        setContent {
            val night by Engine.nightMode.collectAsState()
            val system = isSystemInDarkTheme()
            val dark = when (night) {
                "Noche" -> true
                "Claro" -> false
                else -> system
            }
            LaunchedEffect(dark) {
                C.dark = dark
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            StalkerApp(
                vm = vm,
                hasPermission = hasPermission.value,
                onRequestPermission = { requestPermissions() },
                onExport = {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("stalker-respaldo-$day.json")
                },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
        }
    }
}
