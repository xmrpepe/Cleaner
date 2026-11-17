package dev.pixelforge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.core.settings.Density
import dev.pixelforge.core.settings.ThemeMode

@Immutable
data class PfColors(
    val accent: Color,
    val accentDim: Color,
    val cyan: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val grid: Color,
    val hairline: Color,
    val muted: Color,
    val panel: Color,
    val panelHigh: Color,
    val isDark: Boolean,
)

@Immutable
data class PfDims(val gap: androidx.compose.ui.unit.Dp, val pad: androidx.compose.ui.unit.Dp, val animations: Boolean)

val LocalPfColors = staticCompositionLocalOf {
    PfColors(Color(0xFFFF6B1A), Color(0x33FF6B1A), Color(0xFF22D3EE), Color(0xFF3DDC84), Color(0xFFFFB020), Color(0xFFFF4D4F),
        Color(0xFF1C2026), Color(0xFF262B32), Color(0xFF8E959F), Color(0xFF111316), Color(0xFF171A1E), true)
}
val LocalPfDims = staticCompositionLocalOf { PfDims(12.dp, 16.dp, true) }

object Pf {
    val colors: PfColors @Composable get() = LocalPfColors.current
    val dims: PfDims @Composable get() = LocalPfDims.current
}

val Mono = FontFamily.Monospace

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.4.sp),
    )
}

val Typography.readout: TextStyle get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.5).sp)
val Typography.readoutSmall: TextStyle get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp)

@Composable
fun PixelForgeTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val amoled = settings.themeMode == ThemeMode.AMOLED
    val accent = Color(settings.accent.argb)
    val scheme: ColorScheme = if (dark) {
        val bg = if (amoled) Color.Black else Color(0xFF0A0B0D)
        val surface = if (amoled) Color(0xFF060708) else Color(0xFF111316)
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF140A04),
            primaryContainer = lerp(surface, accent, 0.22f),
            onPrimaryContainer = lerp(Color.White, accent, 0.25f),
            secondary = Color(0xFF22D3EE),
            onSecondary = Color(0xFF00141A),
            secondaryContainer = Color(0xFF0E2A31),
            onSecondaryContainer = Color(0xFFBFF4FF),
            tertiary = Color(0xFFA78BFA),
            background = bg,
            onBackground = Color(0xFFE7E9EC),
            surface = surface,
            onSurface = Color(0xFFE7E9EC),
            surfaceVariant = Color(0xFF1B1E23),
            onSurfaceVariant = Color(0xFF9AA1AB),
            surfaceContainerLowest = bg,
            surfaceContainerLow = if (amoled) Color(0xFF08090B) else Color(0xFF0F1114),
            surfaceContainer = if (amoled) Color(0xFF0B0C0F) else Color(0xFF14171A),
            surfaceContainerHigh = if (amoled) Color(0xFF101216) else Color(0xFF1A1D22),
            surfaceContainerHighest = if (amoled) Color(0xFF15181C) else Color(0xFF20242A),
            outline = Color(0xFF2E333B),
            outlineVariant = Color(0xFF22262C),
            error = Color(0xFFFF5A5F),
            onError = Color.Black,
            inverseSurface = Color(0xFFE7E9EC),
            inverseOnSurface = Color(0xFF111316),
        )
    } else {
        lightColorScheme(
            primary = lerp(accent, Color.Black, 0.12f),
            onPrimary = Color.White,
            primaryContainer = lerp(Color.White, accent, 0.18f),
            onPrimaryContainer = lerp(Color.Black, accent, 0.35f),
            secondary = Color(0xFF0E8EA6),
            background = Color(0xFFF3F4F6),
            onBackground = Color(0xFF14171A),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF14171A),
            surfaceVariant = Color(0xFFE9EBEE),
            onSurfaceVariant = Color(0xFF5B626C),
            surfaceContainerLow = Color(0xFFF7F8FA),
            surfaceContainer = Color(0xFFF1F2F4),
            surfaceContainerHigh = Color(0xFFEBEDF0),
            surfaceContainerHighest = Color(0xFFE4E6EA),
            outline = Color(0xFFD3D7DD),
            outlineVariant = Color(0xFFE2E5E9),
        )
    }
    val pf = PfColors(
        accent = scheme.primary,
        accentDim = scheme.primary.copy(alpha = 0.18f),
        cyan = if (dark) Color(0xFF22D3EE) else Color(0xFF0E8EA6),
        success = if (dark) Color(0xFF3DDC84) else Color(0xFF1E9E5A),
        warning = if (dark) Color(0xFFFFB020) else Color(0xFFB7791F),
        danger = if (dark) Color(0xFFFF5A5F) else Color(0xFFD03A3F),
        grid = if (dark) Color(0xFF1C2026) else Color(0xFFE6E8EC),
        hairline = scheme.outlineVariant,
        muted = scheme.onSurfaceVariant,
        panel = scheme.surfaceContainer,
        panelHigh = scheme.surfaceContainerHigh,
        isDark = dark,
    )
    val dims = if (settings.density == Density.COMPACT) PfDims(8.dp, 12.dp, settings.animations) else PfDims(12.dp, 16.dp, settings.animations)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalPfColors provides pf, LocalPfDims provides dims) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(26.dp),
            ),
            content = content,
        )
    }
}
