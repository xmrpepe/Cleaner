package dev.pixelforge.core.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalDisplay(val id: Int, val name: String, val width: Int, val height: Int, val refreshHz: Float)

/** Tracks presentation-capable external displays (USB-C DisplayPort on Pixel 9). Event-driven. */
class ExternalDisplays(context: Context) {
    private val dm = context.getSystemService(DisplayManager::class.java)
    private val _displays = MutableStateFlow<List<ExternalDisplay>>(emptyList())
    val displays: StateFlow<List<ExternalDisplay>> = _displays.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun display(id: Int): Display? = dm.getDisplay(id)

    private fun refresh() {
        _displays.value = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { d -> ExternalDisplay(d.displayId, d.name, d.mode.physicalWidth, d.mode.physicalHeight, d.refreshRate) }
    }
}
