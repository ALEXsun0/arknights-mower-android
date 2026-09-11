package com.aliothmoon.maameow.mower

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class MowerWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("runtime-wake", 0)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (boot && !AndroidSystemSettings(context).enabled("restart_on_boot")) return
        if (!boot && !prefs.getBoolean("requested", false)) return
        runCatching { context.startForegroundService(Intent(context, MowerService::class.java)) }
    }
    companion object {
        private fun pending(context: Context) = PendingIntent.getBroadcast(context, 7010,
            Intent(context, MowerWakeReceiver::class.java).setAction("mower-wake"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun schedule(context: Context, delaySeconds: Long) {
            if (delaySeconds <= 0) return
            val alarms = context.getSystemService(AlarmManager::class.java)
            val time = android.os.SystemClock.elapsedRealtime() + delaySeconds.coerceAtMost(7 * 86400) * 1000
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, pending(context))
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, pending(context))
        }
        fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
    }
}
