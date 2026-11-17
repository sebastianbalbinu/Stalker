package com.sebas.stalker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.sebas.stalker.ui.StalkerApp

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

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    fun requestPermissions() {
        val perms = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        launcher.launch(perms.toTypedArray())
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
            StalkerApp(
                vm = vm,
                hasPermission = hasPermission.value,
                onRequestPermission = { requestPermissions() },
            )
        }
    }
}
