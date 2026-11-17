package dev.pixelforge.core.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pixelforge.MainActivity
import dev.pixelforge.R

object Notifier {
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_MAINTENANCE = "maintenance"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "System alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Low storage, high temperature and low battery advice"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MAINTENANCE, "Maintenance reports", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Results of scheduled storage analysis"
        })
    }

    fun notify(context: Context, id: Int, channel: String, title: String, text: String, route: String? = null) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_forge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
