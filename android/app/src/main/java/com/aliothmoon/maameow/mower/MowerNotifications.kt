package com.aliothmoon.maameow.mower

import android.app.*
import android.content.Context
import android.content.Intent

object MowerNotifications {
    fun update(context: Context, text: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MowerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val restore = PendingIntent.getActivity(context, 7012, Intent(context, MowerActivity::class.java).setAction("restore-screen"), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, 1, Intent(context, MowerService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(context, "mower-runtime").setContentTitle("Mower Android")
            .setContentText(text).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text)).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    setStyle(androidx.core.app.NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
                    setRequestPromotedOngoing(true)
                    setShortCriticalText("Mower")
                }
            }
            .addAction(0, "恢复屏幕", restore)
            .addAction(0, "停止服务", stop).build()
        context.getSystemService(NotificationManager::class.java).notify(7001, notification)
    }
    fun event(context: Context, message: String, error: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (error) "mower-errors" else "mower-advice"
        manager.createNotificationChannel(NotificationChannel(channel, if (error) "运行异常" else "运行建议",
            if (error) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MowerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(if (error) 7002 else 7003, Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(if (error) "Mower 运行异常" else "Mower 运行建议")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(open).setAutoCancel(true).build())
    }
}
