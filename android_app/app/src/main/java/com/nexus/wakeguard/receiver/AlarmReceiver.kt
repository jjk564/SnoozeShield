package com.nexus.wakeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nexus.wakeguard.service.AlarmRingingService

/**
 * AlarmReceiver — Receives exact `AlarmManager.setAlarmClock` broadcasts & `BOOT_COMPLETED`.
 *
 * Immediately starts `AlarmRingingService` as a Foreground Service (`mediaPlayback`) and
 * triggers the Full-Screen Intent to launch `AlarmActivity` over the lock screen.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-schedule saved active alarms from SharedPreferences on device reboot
                val prefs = context.getSharedPreferences("wakeguard_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("alarm_enabled", true)) {
                    val hour = prefs.getInt("alarm_hour", 6)
                    val min = prefs.getInt("alarm_minute", 30)
                    val label = prefs.getString("alarm_label", "Morning Get-Out-Of-Bed Protocol") ?: "Morning Alarm"
                    val barcode = prefs.getString("target_barcode", "012345678905") ?: "012345678905"
                    val pcUrl = prefs.getString("pc_hub_url", "http://192.168.1.100:5175") ?: "http://192.168.1.100:5175"
                    AlarmScheduler.scheduleDailyAlarm(context, 1, hour, min, label, barcode, pcUrl)
                }
            }

            "com.nexus.wakeguard.ACTION_TRIGGER_ALARM" -> {
                val label = intent.getStringExtra("extra_alarm_label") ?: "Morning Get-Out-Of-Bed Protocol"
                val barcode = intent.getStringExtra("extra_target_barcode") ?: "012345678905"
                val pcUrl = intent.getStringExtra("extra_pc_hub_url") ?: "http://192.168.1.100:5175"

                val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                    action = AlarmRingingService.ACTION_START_ALARM
                    putExtra(AlarmRingingService.EXTRA_ALARM_LABEL, label)
                    putExtra(AlarmRingingService.EXTRA_TARGET_BARCODE, barcode)
                    putExtra(AlarmRingingService.EXTRA_PC_HUB_URL, pcUrl)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
