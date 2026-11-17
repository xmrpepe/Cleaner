package dev.pixelforge.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.graphics.vector.ImageVector

enum class Dest(val route: String, val label: String, val icon: ImageVector, val description: String = "") {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Speed),
    CLEANER("cleaner", "Cleaner", Icons.Filled.CleaningServices, "Storage analysis, junk & duplicates"),
    MONITOR("monitor", "Monitor", Icons.Filled.Memory, "CPU, RAM, GPU, battery, thermal"),
    LAB("lab", "Lab", Icons.Filled.Science, "Benchmarks & sustained tests"),
    MORE("more", "More", Icons.Filled.GridView),
    OPTIMIZE("optimize", "Smart Optimize", Icons.Filled.AutoMode, "Analyse, act, measure"),
    GPU("gpu", "GPU & Vulkan", Icons.Filled.ViewInAr, "Driver, Vulkan & GLES capabilities"),
    BATTERY("battery", "Battery", Icons.Filled.BatteryChargingFull, "Charge, health, discharge trends"),
    THERMAL("thermal", "Thermal", Icons.Filled.Thermostat, "Status, headroom, sensors"),
    APPS("apps", "Apps", Icons.Filled.Apps, "Inventory, usage & management"),
    FILES("files", "Files", Icons.Filled.Folder, "File manager & archives"),
    PRIVACY("privacy", "Privacy", Icons.Filled.PrivacyTip, "Permissions & security posture"),
    NETWORK("network", "Network", Icons.Filled.NetworkCheck, "Latency, DNS, speed, usage"),
    DEVICE("device", "Device", Icons.Filled.PhoneAndroid, "Pixel 9 hardware diagnostics"),
    PROFILES("profiles", "Profiles", Icons.Filled.Tune, "Performance profiles"),
    AUTOMATION("automation", "Automation", Icons.Filled.DeveloperBoard, "Scheduled checks & alerts"),
    ADVANCED("advanced", "Advanced", Icons.Filled.Terminal, "Shizuku, ADB & reversible changes"),
    HISTORY("history", "History", Icons.Filled.History, "Reports, audit log, changes"),
    TRASH("trash", "Recycle bin", Icons.Filled.DeleteSweep, "Restore deleted files"),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, "Appearance, monitoring, privacy"),
    ;

    companion object {
        val primary = listOf(DASHBOARD, CLEANER, MONITOR, LAB, MORE)
        val moreItems = listOf(OPTIMIZE, APPS, FILES, PRIVACY, NETWORK, GPU, BATTERY, THERMAL, DEVICE, PROFILES, AUTOMATION, ADVANCED, HISTORY, TRASH, SETTINGS)
        val sidebar = listOf(DASHBOARD, OPTIMIZE, CLEANER, MONITOR, GPU, BATTERY, THERMAL, LAB, APPS, FILES, PRIVACY, NETWORK, DEVICE, PROFILES, AUTOMATION, ADVANCED, HISTORY, TRASH, SETTINGS)
        fun of(route: String?): Dest? = entries.firstOrNull { route?.substringBefore('/')?.substringBefore('?') == it.route }
    }
}

const val ROUTE_APP_DETAIL = "app"
