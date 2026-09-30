package com.dupati.scrollsense

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Fires once a day at 4 AM to silently push settings and usage history to Firestore
 *  for a signed-in user, so the cloud copy stays fresh even if the user never opens
 *  the app's Sync card. Also reschedules itself after every fire and after a reboot,
 *  since AlarmManager alarms don't survive either on their own. */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        scheduleNext(context)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return

        val syncManager = SyncManager(context)
        if (!syncManager.isSignedIn) return

        val pendingResult = goAsync()
        syncManager.pushSettings {
            syncManager.pushHistory {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ALARM_HOUR = 4
        private const val REQUEST_CODE = 4001

        /** Schedules (or re-schedules) the next 4 AM fire. Safe to call any time,
         *  signed in or not — the receiver itself checks sign-in state before doing
         *  any network work. */
        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, ALARM_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }.timeInMillis

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, SyncAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }
}
