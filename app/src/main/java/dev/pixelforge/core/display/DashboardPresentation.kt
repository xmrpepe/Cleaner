package dev.pixelforge.core.display

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.di.AppContainer
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.screens.DesktopDashboard
import dev.pixelforge.ui.theme.PixelForgeTheme

/**
 * Shows a large-screen PixelForge dashboard on an external (presentation) display, e.g. a
 * monitor connected over USB-C DisplayPort, while the phone keeps its own UI.
 */
class DashboardPresentation(outer: Context, display: android.view.Display, private val container: AppContainer) :
    Presentation(outer, display), LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        savedState.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
        val view = ComposeView(context).apply {
            setContent {
                val s by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                CompositionLocalProvider(LocalContainer provides container) {
                    PixelForgeTheme(s) { DesktopDashboard() }
                }
            }
        }
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        registry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun dismiss() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.dismiss()
    }

    companion object {
        fun show(context: Context, container: AppContainer, displayId: Int): DashboardPresentation? = try {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return null
            DashboardPresentation(context, display, container).also { it.show() }
        } catch (_: Throwable) {
            null
        }
    }
}
