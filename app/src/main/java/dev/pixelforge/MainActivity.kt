package dev.pixelforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.ui.navigation.AppShell
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.screens.OnboardingScreen
import dev.pixelforge.ui.theme.PixelForgeTheme

class MainActivity : ComponentActivity() {
    private var startRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as PixelForgeApp).container
        startRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            CompositionLocalProvider(LocalContainer provides container) {
                PixelForgeTheme(s ?: AppSettings()) {
                    when {
                        s == null -> Unit // DataStore loads in a few ms; avoid flashing onboarding.
                        !s.onboardingDone -> OnboardingScreen()
                        else -> AppShell(startRoute)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { startRoute = it }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in Settings while we were away.
        (application as PixelForgeApp).container.capabilities.refresh()
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
