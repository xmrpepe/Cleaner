package dev.pixelforge.core.privacy

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import dev.pixelforge.core.apps.AppEntry
import dev.pixelforge.core.util.SysFs
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class PermissionGroupSummary(val id: String, val label: String, val permissions: Set<String>, val apps: List<AppEntry>)

data class SpecialAccessApp(val packageName: String, val label: String, val detail: String)

enum class Posture { GOOD, WARN, BAD, INFO }

data class SecurityCheck(val title: String, val value: String, val posture: Posture, val advice: String?, val settingsAction: String?)

data class PrivacyReport(
    val groups: List<PermissionGroupSummary>,
    val accessibility: List<SpecialAccessApp>,
    val notificationListeners: List<SpecialAccessApp>,
    val deviceAdmins: List<SpecialAccessApp>,
    val installSources: List<SpecialAccessApp>,
    val vpnApps: List<SpecialAccessApp>,
    val checks: List<SecurityCheck>,
    val vpnActive: Boolean,
)

/**
 * Builds a privacy/security report strictly from information Android exposes. It makes no
 * malware-detection claims: it surfaces powerful access so the user can review it.
 */
class PrivacyAnalyzer(private val context: Context) {
    private val pm = context.packageManager

    fun analyze(apps: List<AppEntry>): PrivacyReport {
        val user = apps.filter { !it.isSystem || it.isUpdatedSystem }
        val groups = GROUPS.map { (id, label, perms) ->
            PermissionGroupSummary(id, label, perms, user.filter { a -> a.permissions.any { it.granted && it.name in perms } })
        }
        val labelOf: (String) -> String = { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg }

        val am = context.getSystemService(AccessibilityManager::class.java)
        val accessibility = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map {
            val pkg = it.resolveInfo.serviceInfo.packageName
            SpecialAccessApp(pkg, labelOf(pkg), it.resolveInfo.serviceInfo.name.substringAfterLast('.'))
        }.distinctBy { it.packageName + it.detail }

        val listeners = NotificationManagerCompat.getEnabledListenerPackages(context).map {
            SpecialAccessApp(it, labelOf(it), "Can read all notifications")
        }

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admins = dpm.activeAdmins.orEmpty().map { SpecialAccessApp(it.packageName, labelOf(it.packageName), it.className.substringAfterLast('.')) }

        val installers = user.filter { a -> a.permissions.any { it.name == Manifest.permission.REQUEST_INSTALL_PACKAGES } }
            .map { SpecialAccessApp(it.packageName, it.label, "Requests permission to install apps (check “Install unknown apps”)") }

        val vpnApps = try {
            pm.queryIntentServices(Intent("android.net.VpnService"), PackageManager.ResolveInfoFlags.of(0))
                .map { SpecialAccessApp(it.serviceInfo.packageName, labelOf(it.serviceInfo.packageName), "Provides a VPN service") }
                .distinctBy { it.packageName }
        } catch (_: Throwable) {
            emptyList()
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpnActive = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        return PrivacyReport(groups, accessibility, listeners, admins, installers, vpnApps, securityChecks(vpnActive), vpnActive)
    }

    private fun securityChecks(vpnActive: Boolean): List<SecurityCheck> {
        val checks = mutableListOf<SecurityCheck>()
        val patch = Build.VERSION.SECURITY_PATCH
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        checks += SecurityCheck(
            "Security patch level", "$patch${age?.let { " ($it days old)" } ?: ""}",
            when { age == null -> Posture.INFO; age <= 45 -> Posture.GOOD; age <= 100 -> Posture.WARN; else -> Posture.BAD },
            if ((age ?: 0) > 45) "Check for a system update." else null,
            Settings.ACTION_SETTINGS,
        )
        val mainline = runCatching { pm.getPackageInfo("com.google.android.modulemetadata", 0).versionName }.getOrNull()
        if (mainline != null) checks += SecurityCheck("Google Play system update", mainline, Posture.INFO, null, null)

        val km = context.getSystemService(KeyguardManager::class.java)
        checks += SecurityCheck(
            "Screen lock", if (km.isDeviceSecure) "PIN / pattern / password set" else "No secure lock",
            if (km.isDeviceSecure) Posture.GOOD else Posture.BAD,
            if (!km.isDeviceSecure) "Set a screen lock — it also protects the encryption keys." else null,
            Settings.ACTION_SECURITY_SETTINGS,
        )
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val enc = when (dpm.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "File-based encryption (per user)" to Posture.GOOD
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "Encrypted" to Posture.GOOD
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "Encrypted with default key (no lock set)" to Posture.WARN
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "Not encrypted" to Posture.BAD
            else -> "Unknown" to Posture.INFO
        }
        checks += SecurityCheck("Storage encryption", enc.first, enc.second, null, null)

        val cr = context.contentResolver
        val devOptions = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val adbWifi = runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        checks += SecurityCheck("Developer options", if (devOptions) "Enabled" else "Disabled", if (devOptions) Posture.INFO else Posture.GOOD, null, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        checks += SecurityCheck(
            "USB debugging", if (adb) "Enabled" else "Disabled", if (adb) Posture.WARN else Posture.GOOD,
            if (adb) "Disable USB debugging when not developing; anyone with physical access and your unlock can use it." else null,
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        )
        if (adbWifi) checks += SecurityCheck("Wireless debugging", "Enabled", Posture.WARN, "Turn it off when Shizuku/ADB sessions are not needed.", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

        val vbState = SysFs.getprop("ro.boot.verifiedbootstate")
        if (vbState != null) {
            checks += SecurityCheck(
                "Verified boot", vbState,
                when (vbState) { "green" -> Posture.GOOD; "yellow" -> Posture.WARN; "orange" -> Posture.BAD; else -> Posture.INFO },
                if (vbState == "orange") "Bootloader is unlocked: verified boot cannot guarantee OS integrity." else null, null,
            )
        }
        checks += SecurityCheck("VPN", if (vpnActive) "Active on current network" else "Not active", Posture.INFO, null, Settings.ACTION_VPN_SETTINGS)
        val privateDns = runCatching { Settings.Global.getString(cr, "private_dns_mode") }.getOrNull()
        if (privateDns != null) {
            checks += SecurityCheck(
                "Private DNS", when (privateDns) { "off" -> "Off"; "opportunistic" -> "Automatic"; "hostname" -> "Provider hostname"; else -> privateDns },
                if (privateDns == "off") Posture.WARN else Posture.GOOD, null, null,
            )
        }
        return checks
    }

    companion object {
        val GROUPS: List<Triple<String, String, Set<String>>> = listOf(
            Triple("camera", "Camera", setOf(Manifest.permission.CAMERA)),
            Triple("microphone", "Microphone", setOf(Manifest.permission.RECORD_AUDIO)),
            Triple("location", "Location", setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            Triple("bg_location", "Background location", setOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            Triple("contacts", "Contacts", setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)),
            Triple("sms", "SMS", setOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)),
            Triple("calls", "Phone & call logs", setOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_NUMBERS)),
            Triple("calendar", "Calendar", setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
            Triple("media", "Photos & videos", setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)),
            Triple("sensors", "Body sensors", setOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)),
            Triple("nearby", "Nearby devices", setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.UWB_RANGING)),
        )
    }
}
