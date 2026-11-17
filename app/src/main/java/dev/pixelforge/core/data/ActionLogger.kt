package dev.pixelforge.core.data

/** Optimisation tiers used throughout the app. */
enum class Tier(val label: String, val description: String) {
    SAFE("Safe", "Ordinary app-level operation within Android's permission model."),
    ADVANCED("Advanced", "Needs explicit approval and extra privileges (Shizuku, ADB-granted permission or special access)."),
    UNAVAILABLE("Unavailable", "Cannot be performed within the current Android security model."),
}

class ActionLogger(private val dao: ActionLogDao) {
    suspend fun log(category: String, title: String, detail: String, tier: Tier, success: Boolean) {
        dao.insert(
            ActionLogEntry(
                time = System.currentTimeMillis(),
                category = category,
                title = title,
                detail = detail.take(4000),
                tier = tier.name,
                success = success,
            ),
        )
    }
}
