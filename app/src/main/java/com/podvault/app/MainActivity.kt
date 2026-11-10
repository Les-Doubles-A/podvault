package com.podvault.app

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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.podvault.app.navigation.PodVaultApp
import com.podvault.app.ui.theme.PodVaultTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            val app = application as PodVaultApplication
            val darkOverride by app.preferencesManager.darkThemeOverride.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColors by app.preferencesManager.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
            PodVaultTheme(darkTheme = darkOverride ?: androidx.compose.foundation.isSystemInDarkTheme(), dynamicColor = dynamicColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PodVaultApp()
                }
            }
        }
    }

    /**
     * Android 13+ (API 33) requires runtime consent to show the media playback
     * notification. Without it the lock-screen/notification controls won't appear
     * and some OEMs are more aggressive about killing a "silent" foreground service —
     * both directly affect background/locked-screen playback (spec section 31-33).
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
