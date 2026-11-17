package dev.pixelforge.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.pixelforge.R
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    val caps = rememberCaps()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { c.capabilities.refresh() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(96.dp))
            Text("PixelForge", style = MaterialTheme.typography.displaySmall)
            Text("A control centre for your Pixel: real measurements, honest optimisations, nothing faked.", style = MaterialTheme.typography.bodyLarge, color = Pf.colors.muted)
            PfCard(Modifier.fillMaxWidth()) {
                Text("Principles", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "Every action is real and measurable — no fake RAM boosts or cosmetic scores.",
                    "Nothing is deleted or changed without your confirmation; deletions go to a recycle bin.",
                    "Each operation is tagged Safe, Advanced (needs extra access) or Unavailable.",
                    "No root, no accounts, no ads, no analytics. Data stays on the device.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            Text("Optional access", style = MaterialTheme.typography.titleMedium)
            Step("All files access", "Storage analysis, cleaning and the file manager.", caps.allFilesAccess) {
                ctx.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")), Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Step("Usage access", "App sizes, last-used times, data usage.", caps.usageAccess) { ctx.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            Step("Notifications", "Low storage, heat and battery alerts.", caps.notifications) { notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
            Text("You can grant these later. Advanced (Shizuku/ADB) access is configured from More → Advanced.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
            Button(onClick = { scope.launch { c.settings.update { it.copy(onboardingDone = true) } } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Enter PixelForge") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(title: String, text: String, granted: Boolean, onGrant: () -> Unit) {
    PfCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) Pill("Granted", Pf.colors.success) else OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}
