package dev.pixelforge.core.apps

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PermissionGrant(val name: String, val granted: Boolean, val dangerous: Boolean)

data class AppSizes(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    val total: Long get() = appBytes + dataBytes
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstall: Long,
    val lastUpdate: Long,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val enabled: Boolean,
    val isGame: Boolean,
    val targetSdk: Int,
    val minSdk: Int,
    val uid: Int,
    val installer: String?,
    val permissions: List<PermissionGrant>,
    val sizes: AppSizes?,
    val lastUsed: Long?,
    val foregroundMs30d: Long?,
    val batteryUnrestricted: Boolean,
    val stopped: Boolean,
    val hasLauncher: Boolean,
    val apkPath: String?,
) {
    val grantedDangerous: List<PermissionGrant> get() = permissions.filter { it.granted && it.dangerous }
}

/**
 * Installed-app inventory built only from public APIs. Sizes need Usage Access
 * (StorageStatsManager), usage times need Usage Access (UsageStatsManager).
 */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val mutex = Mutex()
    private val iconCache = LruCache<String, Bitmap>(256)
    @Volatile private var loadedAt = 0L

    suspend fun refresh(force: Boolean = false): List<AppEntry> = mutex.withLock {
        if (!force && _apps.value.isNotEmpty() && System.currentTimeMillis() - loadedAt < 60_000) return _apps.value
        _loading.value = true
        try {
            val list = withContext(Dispatchers.IO) { load() }
            _apps.value = list
            loadedAt = System.currentTimeMillis()
            list
        } finally {
            _loading.value = false
        }
    }

    fun find(pkg: String): AppEntry? = _apps.value.firstOrNull { it.packageName == pkg }

    private fun load(): List<AppEntry> {
        val flags = PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
        )
        val packages: List<PackageInfo> = pm.getInstalledPackages(flags)
        val power = context.getSystemService(PowerManager::class.java)
        val usage = usageMap()
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).map { it.activityInfo.packageName }.toHashSet()
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user: UserHandle = Process.myUserHandle()

        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val perms = pi.requestedPermissions?.mapIndexed { i, name ->
                val granted = (pi.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                PermissionGrant(name, granted, isDangerous(name))
            } ?: emptyList()
            val sizes = try {
                val s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pi.packageName, user)
                AppSizes(s.appBytes, s.dataBytes, s.cacheBytes)
            } catch (_: Throwable) {
                null
            }
            val installer = try { pm.getInstallSourceInfo(pi.packageName).installingPackageName } catch (_: Throwable) { null }
            val u = usage[pi.packageName]
            AppEntry(
                packageName = pi.packageName,
                label = ai.loadLabel(pm).toString(),
                versionName = pi.versionName,
                versionCode = pi.longVersionCode,
                firstInstall = pi.firstInstallTime,
                lastUpdate = pi.lastUpdateTime,
                isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                enabled = ai.enabled,
                isGame = ai.category == ApplicationInfo.CATEGORY_GAME,
                targetSdk = ai.targetSdkVersion,
                minSdk = ai.minSdkVersion,
                uid = ai.uid,
                installer = installer,
                permissions = perms,
                sizes = sizes,
                lastUsed = u?.first,
                foregroundMs30d = u?.second,
                batteryUnrestricted = power.isIgnoringBatteryOptimizations(pi.packageName),
                stopped = ai.flags and ApplicationInfo.FLAG_STOPPED != 0,
                hasLauncher = pi.packageName in launchable,
                apkPath = ai.sourceDir,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** package → (last time used, total foreground ms over 30 days). Empty without Usage Access. */
    private fun usageMap(): Map<String, Pair<Long, Long>> = try {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val start = end - 30L * 86_400_000L
        usm.queryAndAggregateUsageStats(start, end).mapValues { (_, s) -> s.lastTimeUsed to s.totalTimeInForeground }
    } catch (_: Throwable) {
        emptyMap()
    }

    fun icon(pkg: String, sizePx: Int): Bitmap? {
        iconCache.get(pkg)?.let { return it }
        return try {
            val d = pm.getApplicationIcon(pkg)
            val bmp = createBitmap(sizePx, sizePx)
            val c = Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(c)
            iconCache.put(pkg, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDangerous(name: String): Boolean = try {
        val info = pm.getPermissionInfo(name, 0)
        info.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    } catch (_: Throwable) {
        false
    }

    companion object {
        /** Packages whose disablement would break the device. Never offered for disable/uninstall. */
        val CRITICAL_PACKAGES = setOf(
            "android", "com.android.systemui", "com.android.phone", "com.android.settings",
            "com.android.providers.settings", "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.apps.nexuslauncher", "com.android.launcher3",
            "com.android.shell", "com.android.bluetooth", "com.android.networkstack",
            "com.google.android.networkstack", "com.android.server.telecom", "com.android.nfc",
            "com.android.se", "com.android.keychain", "com.android.location.fused",
            "com.google.android.ext.services", "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin", "com.android.externalstorage",
            "com.android.providers.downloads", "com.android.providers.contacts",
            "com.android.providers.telephony", "com.google.android.apps.pixel.agent",
            "dev.pixelforge", "moe.shizuku.privileged.api",
        )

        fun isCritical(pkg: String): Boolean = pkg in CRITICAL_PACKAGES || pkg.startsWith("com.android.providers.")
    }
}
