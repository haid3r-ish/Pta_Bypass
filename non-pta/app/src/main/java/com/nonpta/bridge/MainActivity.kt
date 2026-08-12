package com.nonpta.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.nonpta.bridge.ui.navigation.AppNavigation
import com.nonpta.bridge.ui.theme.P2PBridgeTheme
import com.nonpta.bridge.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import android.content.Intent

object DeepLinkHandler {
    val pendingNavigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingUnminimize = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

class MainActivity : ComponentActivity() {

    private lateinit var settingsVM: SettingsViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Auto-connect once permissions are granted (or already granted)
        if (results.values.all { it }) {
            settingsVM.autoConnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val app = application as BridgeApp
        if (!app.serviceConnection.isBound()) {
            app.serviceConnection.bind(app)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        handleIntent(intent)

        settingsVM = ViewModelProvider(this)[SettingsViewModel::class.java]

        // Request all runtime permissions at launch
        requestPermissions()

        setContent {
            P2PBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }

    private fun requestPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)

            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.READ_CONTACTS)
        }

        if (needed.isEmpty()) {
            settingsVM.autoConnect()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val extraInfo = intent?.getBooleanExtra("open_active_call", false) ?: false
        if (extraInfo) {
            DeepLinkHandler.pendingUnminimize.tryEmit(Unit)
        }
        val chatTarget = intent?.getStringExtra("open_chat")
        if (chatTarget != null) {
            DeepLinkHandler.pendingNavigate.tryEmit("chat/$chatTarget")
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            (application as BridgeApp).serviceConnection.unbind(application)
        }
        super.onDestroy()
    }
}
