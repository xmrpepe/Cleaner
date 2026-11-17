package dev.pixelforge.core.optimize

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import dev.pixelforge.core.apps.AppActions
import dev.pixelforge.core.apps.AppRepository
import dev.pixelforge.core.apps.ChangeKind
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.ReversibleChange
import dev.pixelforge.core.data.ReversibleChangeDao
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.settings.SettingsRepository
import dev.pixelforge.core.shizuku.Capabilities
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.shizuku.SettingsNamespace

/** One concrete, verifiable step of a performance profile. */
sealed class ProfileAction(val title: String, val explanation: String, val tier: Tier) {
    /** A system setting written via WRITE_SECURE_SETTINGS (ADB-granted) or Shizuku, recorded for restore. */
    class SettingValue(title: String, explanation: String, val ns: SettingsNamespace, val key: String, val value: String) :
        ProfileAction(title, explanation, Tier.ADVANCED)

    /** Battery Saver via `cmd power set-mode`. */
    class PowerSaver(val enable: Boolean) : ProfileAction(
        if (enable) "Turn on Battery Saver" else "Turn off Battery Saver",
        if (enable) "Android's own Battery Saver: limits background work, location and visual effects." else "Restores full background activity and performance.",
        Tier.ADVANCED,
    )

    /** `cmd power set-fixed-performance-mode-enabled` — locks clocks at a sustainable level. */
    class FixedPerformance(val enable: Boolean) : ProfileAction(
        if (enable) "Fixed performance mode on" else "Fixed performance mode off",
        "Development mode that pins CPU/GPU at a sustainable level for reproducible benchmarks. It lowers peak speed; it is not a boost.",
        Tier.ADVANCED,
    )

    /** Android Game Mode for installed games (only games that support it accept the change). */
    class GameModeForGames(val mode: String) : ProfileAction(
        "Game Mode: $mode for installed games",
        "Uses Android's GameManager. Games that opt in apply the mode; others are left unchanged.",
        Tier.ADVANCED,
    )

    /** PixelForge's own behaviour (always available). */
    class Internal(title: String, explanation: String, val monitorIntervalMs: Long?, val adaptive: Boolean?, val developerDiagnostics: Boolean?) :
        ProfileAction(title, explanation, Tier.SAFE)

    /** Opens a system screen where the user makes the change themselves. */
    class OpenSettings(title: String, explanation: String, val action: String) : ProfileAction(title, explanation, Tier.SAFE)
}

data class PerformanceProfile(val id: String, val name: String, val summary: String, val actions: List<ProfileAction>)

data class ActionOutcome(val action: ProfileAction, val status: Status, val detail: String) {
    enum class Status { APPLIED, VERIFIED, SKIPPED_UNAVAILABLE, FAILED, OPENED }
}

object ProfileCatalog {
    private fun anim(scale: String) = listOf(
        ProfileAction.SettingValue("Window animation scale $scale×", "Developer setting. Shorter animations make transitions complete sooner; it does not change CPU speed.", SettingsNamespace.GLOBAL, Settings.Global.WINDOW_ANIMATION_SCALE, scale),
        ProfileAction.SettingValue("Transition animation scale $scale×", "Developer setting for activity transitions.", SettingsNamespace.GLOBAL, Settings.Global.TRANSITION_ANIMATION_SCALE, scale),
        ProfileAction.SettingValue("Animator duration scale $scale×", "Developer setting for in-app animations.", SettingsNamespace.GLOBAL, Settings.Global.ANIMATOR_DURATION_SCALE, scale),
    )

    val all: List<PerformanceProfile> = listOf(
        PerformanceProfile(
            "MAX_PERFORMANCE", "Maximum Performance",
            "Highest responsiveness Android allows: Battery Saver off, peak refresh rate locked, snappier animations.",
            listOf(
                ProfileAction.PowerSaver(false),
                ProfileAction.FixedPerformance(false),
                ProfileAction.SettingValue("Lock 120 Hz (min refresh)", "Keeps the display at its peak rate instead of dropping to save power. Costs battery.", SettingsNamespace.SYSTEM, "min_refresh_rate", "120.0"),
                ProfileAction.SettingValue("Allow 120 Hz (peak refresh)", "Equivalent to Smooth Display on.", SettingsNamespace.SYSTEM, "peak_refresh_rate", "120.0"),
            ) + anim("0.5") + ProfileAction.Internal("Fast live monitoring", "PixelForge samples every 1 s while visible.", 1000, true, null),
        ),
        PerformanceProfile(
            "BALANCED", "Balanced",
            "Android defaults: adaptive refresh, standard animations, Battery Saver off.",
            listOf(
                ProfileAction.PowerSaver(false),
                ProfileAction.FixedPerformance(false),
                ProfileAction.SettingValue("Adaptive refresh (min 0)", "Lets the display drop its refresh rate when content is static.", SettingsNamespace.SYSTEM, "min_refresh_rate", "0.0"),
                ProfileAction.SettingValue("Allow 120 Hz (peak refresh)", "Smooth Display on.", SettingsNamespace.SYSTEM, "peak_refresh_rate", "120.0"),
            ) + anim("1.0") + ProfileAction.Internal("Standard monitoring", "1.5 s sampling, adaptive back-off.", 1500, true, null),
        ),
        PerformanceProfile(
            "BATTERY_SAVER", "Battery Saver",
            "Maximises endurance: Battery Saver on, 60 Hz cap, slower PixelForge sampling.",
            listOf(
                ProfileAction.PowerSaver(true),
                ProfileAction.SettingValue("Cap at 60 Hz (peak refresh)", "Smooth Display off — a significant display power saving.", SettingsNamespace.SYSTEM, "peak_refresh_rate", "60.0"),
                ProfileAction.SettingValue("Adaptive refresh (min 0)", "Allows the lowest refresh rate.", SettingsNamespace.SYSTEM, "min_refresh_rate", "0.0"),
                ProfileAction.Internal("Low-power monitoring", "PixelForge samples every 4 s.", 4000, true, null),
                ProfileAction.OpenSettings("Review Adaptive Battery", "Google's adaptive battery learns app usage; keep it on.", "android.settings.BATTERY_SAVER_SETTINGS"),
            ),
        ),
        PerformanceProfile(
            "GAMING", "Gaming",
            "Peak refresh rate locked, Battery Saver off and Android Game Mode set to performance for supporting games.",
            listOf(
                ProfileAction.PowerSaver(false),
                ProfileAction.FixedPerformance(false),
                ProfileAction.SettingValue("Lock 120 Hz (min refresh)", "Stable high refresh for games that render above 60 fps.", SettingsNamespace.SYSTEM, "min_refresh_rate", "120.0"),
                ProfileAction.SettingValue("Allow 120 Hz (peak refresh)", "Smooth Display on.", SettingsNamespace.SYSTEM, "peak_refresh_rate", "120.0"),
                ProfileAction.GameModeForGames("performance"),
                ProfileAction.Internal("Quiet monitoring", "PixelForge backs off to 3 s sampling so it never competes with the game.", 3000, true, null),
            ),
        ),
        PerformanceProfile(
            "DEVELOPMENT", "Development",
            "Reproducible measurements: fixed performance mode on, animations off, developer diagnostics visible.",
            listOf(
                ProfileAction.FixedPerformance(true),
                ProfileAction.PowerSaver(false),
            ) + anim("0") + listOf(
                ProfileAction.SettingValue("Stay awake while charging", "Screen stays on when plugged in (USB/AC/wireless).", SettingsNamespace.GLOBAL, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7"),
                ProfileAction.Internal("Developer diagnostics", "Shows per-core, PSI and self-overhead details on the dashboard.", 1000, false, true),
                ProfileAction.OpenSettings("Developer options", "Open Android's developer options.", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            ),
        ),
        PerformanceProfile(
            "DESKTOP", "Desktop Mode",
            "For external displays: desktop windowing on external displays (developer option, reboot required), stay awake while charging, wide dashboard.",
            listOf(
                ProfileAction.SettingValue("Desktop mode on external displays", "Developer option 'Force desktop mode'. Takes effect after a reboot.", SettingsNamespace.GLOBAL, "force_desktop_mode_on_external_displays", "1"),
                ProfileAction.SettingValue("Enable freeform windows", "Developer option for resizable windows.", SettingsNamespace.GLOBAL, "enable_freeform_support", "1"),
                ProfileAction.SettingValue("Stay awake while charging", "Keeps the phone awake when docked.", SettingsNamespace.GLOBAL, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7"),
                ProfileAction.Internal("Desktop monitoring", "Fast sampling for the large-screen dashboard.", 1000, true, null),
                ProfileAction.OpenSettings("Display settings", "Configure the external display.", Settings.ACTION_DISPLAY_SETTINGS),
            ),
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Applies profiles action-by-action, verifying each change and recording it for restore. */
class ProfileEngine(
    private val context: Context,
    private val exec: PrivilegedExec,
    private val capabilities: Capabilities,
    private val changes: ReversibleChangeDao,
    private val settings: SettingsRepository,
    private val apps: AppRepository,
    private val appActions: AppActions,
    private val logger: ActionLogger,
) {
    private val pm = context.getSystemService(PowerManager::class.java)

    fun availability(action: ProfileAction): Tier {
        val caps = capabilities.current()
        return when (action) {
            is ProfileAction.Internal, is ProfileAction.OpenSettings -> Tier.SAFE
            is ProfileAction.SettingValue -> if (action.ns == SettingsNamespace.SYSTEM) {
                if (caps.shizukuReady) Tier.ADVANCED else Tier.UNAVAILABLE
            } else if (caps.canWriteSecureSettings) Tier.ADVANCED else Tier.UNAVAILABLE
            is ProfileAction.PowerSaver, is ProfileAction.FixedPerformance, is ProfileAction.GameModeForGames ->
                if (caps.shizukuReady) Tier.ADVANCED else Tier.UNAVAILABLE
        }
    }

    suspend fun apply(profile: PerformanceProfile, onOpenSettings: (Intent) -> Unit): List<ActionOutcome> {
        val out = mutableListOf<ActionOutcome>()
        for (a in profile.actions) {
            if (availability(a) == Tier.UNAVAILABLE) {
                out += ActionOutcome(a, ActionOutcome.Status.SKIPPED_UNAVAILABLE, "Needs Shizuku or an ADB-granted permission")
                continue
            }
            out += when (a) {
                is ProfileAction.SettingValue -> applySetting(profile, a)
                is ProfileAction.PowerSaver -> {
                    val r = exec.shell("cmd power set-mode ${if (a.enable) 1 else 0}", a.title)
                    val verified = pm.isPowerSaveMode == a.enable
                    ActionOutcome(a, if (r?.ok == true && verified) ActionOutcome.Status.VERIFIED else if (r?.ok == true) ActionOutcome.Status.APPLIED else ActionOutcome.Status.FAILED, r?.output?.trim().orEmpty().ifEmpty { if (verified) "PowerManager confirms the state" else "" })
                }
                is ProfileAction.FixedPerformance -> {
                    val r = exec.shell("cmd power set-fixed-performance-mode-enabled ${a.enable}", a.title)
                    ActionOutcome(a, if (r?.ok == true) ActionOutcome.Status.APPLIED else ActionOutcome.Status.FAILED, r?.output?.trim().orEmpty())
                }
                is ProfileAction.GameModeForGames -> {
                    val games = apps.refresh().filter { it.isGame && it.enabled }
                    var applied = 0
                    games.forEach { g -> if (appActions.setGameMode(g.packageName, g.label, a.mode).isSuccess) applied++ }
                    ActionOutcome(a, if (games.isEmpty() || applied > 0) ActionOutcome.Status.APPLIED else ActionOutcome.Status.FAILED, "${games.size} game(s) found, $applied accepted the mode")
                }
                is ProfileAction.Internal -> {
                    settings.update { s ->
                        s.copy(
                            monitorIntervalMs = a.monitorIntervalMs ?: s.monitorIntervalMs,
                            adaptiveMonitoring = a.adaptive ?: s.adaptiveMonitoring,
                            developerDiagnostics = a.developerDiagnostics ?: s.developerDiagnostics,
                        )
                    }
                    ActionOutcome(a, ActionOutcome.Status.VERIFIED, "PixelForge preference updated")
                }
                is ProfileAction.OpenSettings -> {
                    onOpenSettings(Intent(a.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionOutcome(a, ActionOutcome.Status.OPENED, "Opened for you to review")
                }
            }
        }
        settings.update { it.copy(activeProfile = profile.id) }
        logger.log(
            "profile", "Applied profile ${profile.name}",
            out.joinToString("\n") { "• ${it.action.title}: ${it.status} ${it.detail}".trim() }, Tier.ADVANCED,
            out.none { it.status == ActionOutcome.Status.FAILED },
        )
        return out
    }

    private suspend fun applySetting(profile: PerformanceProfile, a: ProfileAction.SettingValue): ActionOutcome {
        val before = exec.readSettingPrivileged(a.ns, a.key)
        if (before == a.value) return ActionOutcome(a, ActionOutcome.Status.VERIFIED, "Already ${a.value}")
        val ok = exec.writeSetting(a.ns, a.key, a.value, "${profile.name}: ${a.title}")
        if (!ok) return ActionOutcome(a, ActionOutcome.Status.FAILED, "Write rejected by the system")
        changes.insert(
            ReversibleChange(
                time = System.currentTimeMillis(), kind = ChangeKind.SETTING.name, target = a.ns.name, key = a.key,
                previousValue = before, newValue = a.value, label = a.title, source = "profile:${profile.id}",
            ),
        )
        val after = exec.readSettingPrivileged(a.ns, a.key)
        return ActionOutcome(
            a, if (after == a.value) ActionOutcome.Status.VERIFIED else ActionOutcome.Status.APPLIED,
            "${before ?: "unset"} → ${after ?: a.value}",
        )
    }
}
