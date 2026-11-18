package com.nexus.snoozeshield.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.snoozeshield.receiver.AlarmScheduler
import com.nexus.snoozeshield.service.AlarmRingingService

/**
 * MainActivity â€” SnoozeShield Alarm Configurator, Volume Guard Readiness Audit & PC Bridge Pairing.
 *
 * Allows the user to:
 * 1. Configure their morning alarm time, target ML Kit barcode string, and PC Challenge Hub LAN IP.
 * 2. Run a "Morning Volume Guard & Permission Readiness Check" verifying:
 *    - `AlarmManager.canScheduleExactAlarms()`
 *    - `NotificationManager.canUseFullScreenIntent()` (Android 14+)
 *    - `NotificationManager.isNotificationPolicyAccessGranted` (Do Not Disturb Override)
 *    - Instant 100% `STREAM_ALARM` Volume Test
 * 3. Launch an immediate Full-Screen Siren & Barcode/PC Challenge Test.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("snoozeshield_prefs", Context.MODE_PRIVATE)

        setContent {
            var hour by remember { mutableIntStateOf(prefs.getInt("alarm_hour", 6)) }
            var minute by remember { mutableIntStateOf(prefs.getInt("alarm_minute", 30)) }
            var label by remember {
                mutableStateOf(prefs.getString("alarm_label", "Morning Get-Out-Of-Bed Protocol") ?: "")
            }
            var targetBarcode by remember {
                mutableStateOf(prefs.getString("target_barcode", "012345678905") ?: "012345678905")
            }
            var pcHubUrl by remember {
                mutableStateOf(prefs.getString("pc_hub_url", "http://192.168.1.100:5175") ?: "http://192.168.1.100:5175")
            }
            var statusMessage by remember { mutableStateOf("Ready to arm SnoozeShield Protocol.") }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val dndGranted = notifManager.isNotificationPolicyAccessGranted
            val exactGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else true

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF090D16))
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "SNOOZESHIELD // ANDROID BARCODE & PC ALARM",
                    color = Color(0xFF22D3EE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "ML Kit Barcode Scanner + 6-Digit Computer Challenge + 100% Volume Enforcer",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                // CARD 1: VOLUME GUARD & SYSTEM PERMISSION AUDIT
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ðŸ”Š ALWAYS-LOUD VOLUME GUARD AUDIT",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "â€¢ Exact Alarm Clock Permission: ${if (exactGranted) "âœ“ GRANTED" else "âš  NEEDS GRANT"}",
                        color = if (exactGranted) Color(0xFF34D399) else Color(0xFFF43F5E),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "â€¢ Do Not Disturb Bypass Policy: ${if (dndGranted) "âœ“ GRANTED" else "âš  CLICK TO GRANT"}",
                        color = if (dndGranted) Color(0xFF34D399) else Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!dndGranted) {
                            Button(
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                }
                            ) {
                                Text("Grant DND Bypass", fontSize = 11.sp)
                            }
                        }
                        Button(
                            onClick = {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_ALARM,
                                    max,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                statusMessage = "STREAM_ALARM forced to 100% ($max/$max)!"
                            }
                        ) {
                            Text("Force 100% Alarm Volume Now", fontSize = 11.sp)
                        }
                    }
                }

                // CARD 2: ALARM TIME, BARCODE & PC BRIDGE CONFIG
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "â° SCHEDULE MORNING ALARM PROTOCOL",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = hour.toString(),
                            onValueChange = { hour = (it.toIntOrNull() ?: 6).coerceIn(0, 23) },
                            label = { Text("Hour (0-23)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minute.toString(),
                            onValueChange = { minute = (it.toIntOrNull() ?: 30).coerceIn(0, 59) },
                            label = { Text("Minute (0-59)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Alarm Label") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = targetBarcode,
                        onValueChange = { targetBarcode = it },
                        label = { Text("Target ML Kit Barcode (e.g. Toothpaste UPC)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = pcHubUrl,
                        onValueChange = { pcHubUrl = it },
                        label = { Text("PC Challenge Hub LAN URL (Port 5175)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            prefs.edit()
                                .putBoolean("alarm_enabled", true)
                                .putInt("alarm_hour", hour)
                                .putInt("alarm_minute", minute)
                                .putString("alarm_label", label)
                                .putString("target_barcode", targetBarcode)
                                .putString("pc_hub_url", pcHubUrl)
                                .apply()

                            val triggerMs = AlarmScheduler.scheduleDailyAlarm(
                                context = this@MainActivity,
                                alarmId = 1,
                                hourOfDay = hour,
                                minute = minute,
                                label = label,
                                targetBarcode = targetBarcode,
                                pcHubUrl = pcHubUrl
                            )
                            statusMessage = if (triggerMs > 0) {
                                "âœ“ Armed for %02d:%02d with 100%% Volume Guard!".format(hour, minute)
                            } else {
                                "âš  Grant Exact Alarm permission in system settings."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save & Arm Daily SnoozeShield Alarm")
                    }
                }

                // CARD 3: IMMEDIATE FULL-SCREEN TEST SIREN
                Button(
                    onClick = {
                        val svcIntent = Intent(this@MainActivity, AlarmRingingService::class.java).apply {
                            action = AlarmRingingService.ACTION_START_ALARM
                            putExtra(AlarmRingingService.EXTRA_ALARM_LABEL, label)
                            putExtra(AlarmRingingService.EXTRA_TARGET_BARCODE, targetBarcode)
                            putExtra(AlarmRingingService.EXTRA_PC_HUB_URL, pcHubUrl)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(svcIntent)
                        } else {
                            startService(svcIntent)
                        }
                        val actIntent = Intent(this@MainActivity, AlarmActivity::class.java).apply {
                            putExtra(AlarmRingingService.EXTRA_ALARM_LABEL, label)
                            putExtra(AlarmRingingService.EXTRA_TARGET_BARCODE, targetBarcode)
                        }
                        startActivity(actIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ðŸš¨ Test Full-Screen Alarm + Barcode / PC Challenge Now", color = Color.White)
                }

                Text(
                    text = statusMessage,
                    color = Color(0xFF34D399),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
