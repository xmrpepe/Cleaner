package dev.pixelforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.pixelforge.di.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
inline fun <reified VM : ViewModel> pfViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}
