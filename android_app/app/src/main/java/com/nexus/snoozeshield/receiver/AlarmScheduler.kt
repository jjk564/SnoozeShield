package com.nexus.snoozeshield.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nexus.snoozeshield.ui.MainActivity
import java.util.Calendar

/**
 * AlarmScheduler â€” Schedules Exact Doze-Immune Alarms via `AlarmManager.setAlarmClock()`.
 *
 * Why `setAlarmClock()` is critical:
 * - Unlike `setExactAndAllowWhileIdle()` (which Android Doze mode can throttle to once per 9 minutes),
 *   `setAlarmClock()` is treated by the Android OS as a true user-facing alarm clock: it wakes the
 *   device out of deep Doze slightly early, displays the alarm icon in the status bar, and grants
 *   immediate background ForegroundService launch privileges.
 */
object AlarmScheduler {

    fun scheduleDailyAlarm(
        context: Context,
        alarmId: Int,
        hourOfDay: Int,
        minute: Int,
        label: String,
        targetBarcode: String,
        pcHubUrl: String
    ): Long {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                return -1L
            }
        }

        val triggerCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.nexus.snoozeshield.ACTION_TRIGGER_ALARM"
            putExtra("extra_alarm_id", alarmId)
            putExtra("extra_alarm_label", label)
            putExtra("extra_target_barcode", targetBarcode)
            putExtra("extra_pc_hub_url", pcHubUrl)
        }

        val operationPi = PendingIntent.getBroadcast(
            context,
            alarmId,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showAppIntent = Intent(context, MainActivity::class.java)
        val showAppPi = PendingIntent.getActivity(
            context,
            alarmId,
            showAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerCalendar.timeInMillis, showAppPi)
        alarmManager.setAlarmClock(alarmClockInfo, operationPi)

        return triggerCalendar.timeInMillis
    }

    fun cancelAlarm(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.nexus.snoozeshield.ACTION_TRIGGER_ALARM"
        }
        val operationPi = PendingIntent.getBroadcast(
            context,
            alarmId,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(operationPi)
    }
}
