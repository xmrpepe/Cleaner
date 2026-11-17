package dev.pixelforge.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pixelforge.R
import dev.pixelforge.ui.screens.AdvancedScreen
import dev.pixelforge.ui.screens.AppDetailScreen
import dev.pixelforge.ui.screens.AppsScreen
import dev.pixelforge.ui.screens.AutomationScreen
import dev.pixelforge.ui.screens.BatteryScreen
import dev.pixelforge.ui.screens.CleanerScreen
import dev.pixelforge.ui.screens.DashboardScreen
import dev.pixelforge.ui.screens.DeviceScreen
import dev.pixelforge.ui.screens.FilesScreen
import dev.pixelforge.ui.screens.GpuScreen
import dev.pixelforge.ui.screens.HistoryScreen
import dev.pixelforge.ui.screens.LabScreen
import dev.pixelforge.ui.screens.MonitorScreen
import dev.pixelforge.ui.screens.MoreScreen
import dev.pixelforge.ui.screens.NetworkScreen
import dev.pixelforge.ui.screens.OptimizeScreen
import dev.pixelforge.ui.screens.PrivacyScreen
import dev.pixelforge.ui.screens.ProfilesScreen
import dev.pixelforge.ui.screens.SettingsScreen
import dev.pixelforge.ui.screens.ThermalScreen
import dev.pixelforge.ui.screens.TrashScreen
import dev.pixelforge.ui.theme.Pf

/** Navigation helper passed to screens. */
class Navigator(private val nav: NavHostController) {
    fun go(route: String) {
        nav.navigate(route) { launchSingleTop = true }
    }
    fun go(dest: Dest) = go(dest.route)
    fun back() { nav.popBackStack() }
    fun app(pkg: String) = go("$ROUTE_APP_DETAIL/$pkg")
}

@Composable
fun AppShell(startRoute: String?) {
    val nav = rememberNavController()
    val navigator = Navigator(nav)
    val entry by nav.currentBackStackEntryAsState()
    val current = Dest.of(entry?.destination?.route)
    val windowInfo = LocalWindowInfo.current
    val wide = with(LocalDensity.current) { windowInfo.containerSize.width.toDp() } >= 840.dp

    LaunchedEffect(startRoute) {
        if (startRoute != null && Dest.of(startRoute) != null) navigator.go(startRoute)
    }

    val keyNav = Modifier.onPreviewKeyEvent { e ->
        // Desktop / external keyboard shortcuts: Ctrl+1..9 jump to sections, Esc goes back.
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (e.key == Key.Escape) { navigator.back(); return@onPreviewKeyEvent true }
        if (!e.isCtrlPressed) return@onPreviewKeyEvent false
        val idx = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(e.key)
        if (idx >= 0 && idx < Dest.sidebar.size) { navigator.go(Dest.sidebar[idx]); true } else false
    }

    val host: @Composable (Modifier) -> Unit = { m -> PfNavHost(nav, navigator, m) }

    if (wide) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).then(keyNav)) {
            Sidebar(current, navigator)
            Box(Modifier.weight(1f).fillMaxHeight()) { host(Modifier.fillMaxSize()) }
        }
    } else {
        Scaffold(
            modifier = keyNav,
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    val selectedPrimary = when {
                        current == null -> Dest.DASHBOARD
                        current in Dest.primary -> current
                        current in listOf(Dest.GPU, Dest.BATTERY, Dest.THERMAL) -> Dest.MONITOR
                        current == Dest.TRASH -> Dest.CLEANER
                        else -> Dest.MORE
                    }
                    Dest.primary.forEach { d ->
                        NavigationBarItem(
                            selected = selectedPrimary == d,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(Dest.DASHBOARD.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, d.label) },
                            label = { Text(d.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Pf.colors.accent,
                                selectedTextColor = Pf.colors.accent,
                                indicatorColor = Pf.colors.accentDim,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            host(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun Sidebar(current: Dest?, navigator: Navigator) {
    Column(
        Modifier.width(248.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp)) {
            Icon(painterResource(R.drawable.ic_stat_forge), null, tint = Pf.colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("PixelForge", style = MaterialTheme.typography.titleMedium)
                Text("Control centre", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
            }
        }
        Dest.sidebar.forEachIndexed { i, d ->
            NavigationDrawerItem(
                label = { Text(d.label) },
                selected = current == d || (current == null && d == Dest.DASHBOARD),
                onClick = { navigator.go(d) },
                icon = { Icon(d.icon, null) },
                badge = { if (i < 9) Text("^${i + 1}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Pf.colors.accentDim,
                    selectedIconColor = Pf.colors.accent,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.height(44.dp),
            )
        }
    }
}

@Composable
private fun PfNavHost(nav: NavHostController, navigator: Navigator, modifier: Modifier) {
    NavHost(nav, startDestination = Dest.DASHBOARD.route, modifier = modifier) {
        composable(Dest.DASHBOARD.route) { DashboardScreen(navigator) }
        composable(Dest.OPTIMIZE.route) { OptimizeScreen(navigator) }
        composable(Dest.CLEANER.route) { CleanerScreen(navigator) }
        composable(Dest.MONITOR.route) { MonitorScreen(navigator) }
        composable(Dest.GPU.route) { GpuScreen(navigator) }
        composable(Dest.BATTERY.route) { BatteryScreen(navigator) }
        composable(Dest.THERMAL.route) { ThermalScreen(navigator) }
        composable(Dest.LAB.route) { LabScreen(navigator) }
        composable(Dest.MORE.route) { MoreScreen(navigator) }
        composable(Dest.APPS.route) { AppsScreen(navigator) }
        composable(
            "$ROUTE_APP_DETAIL/{pkg}",
            arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
        ) { e -> AppDetailScreen(navigator, e.arguments?.getString("pkg").orEmpty()) }
        composable(Dest.FILES.route) { FilesScreen(navigator) }
        composable(Dest.PRIVACY.route) { PrivacyScreen(navigator) }
        composable(Dest.NETWORK.route) { NetworkScreen(navigator) }
        composable(Dest.DEVICE.route) { DeviceScreen(navigator) }
        composable(Dest.PROFILES.route) { ProfilesScreen(navigator) }
        composable(Dest.AUTOMATION.route) { AutomationScreen(navigator) }
        composable(Dest.ADVANCED.route) { AdvancedScreen(navigator) }
        composable(Dest.HISTORY.route) { HistoryScreen(navigator) }
        composable(Dest.TRASH.route) { TrashScreen(navigator) }
        composable(Dest.SETTINGS.route) { SettingsScreen(navigator) }
    }
}
