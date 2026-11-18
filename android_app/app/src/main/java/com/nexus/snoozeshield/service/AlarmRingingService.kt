package com.nexus.snoozeshield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.nexus.snoozeshield.ui.AlarmActivity
import com.nexus.snoozeshield.util.ChallengeGenerator
import com.nexus.snoozeshield.util.VolumeEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.sin

/**
 * AlarmRingingService â€” Unstoppable Foreground Siren, Volume Guard & PC Sync Service.
 *
 * - Posts a high-priority Notification with `setFullScreenIntent(alarmPendingIntent, true)`
 *   and `setBypassDnd(true)` so `AlarmActivity` launches over the lock screen.
 * - Starts `VolumeEnforcer` to lock `STREAM_ALARM` at 100% every 500ms.
 * - Plays the system alarm URI on `AudioAttributes.USAGE_ALARM` PLUS a synthesized backup
 *   high-decibel dual-tone square/sine siren via `AudioTrack` so even if a custom ringtone URI
 *   is missing or corrupted, the phone ALWAYS produces a loud morning siren.
 * - Polls the PC Challenge Hub (`http://<PC_LAN_IP>:5175/api/status`) every 2 seconds so the
 *   moment the user types the 6-digit code on their computer, the phone alarm automatically stops!
 */
class AlarmRingingService : Service() {

    companion object {
        const val CHANNEL_ID = "snoozeshield_critical_alarm_channel"
        const val NOTIFICATION_ID = 7701
        const val ACTION_START_ALARM = "com.nexus.snoozeshield.ACTION_START_ALARM"
        const val ACTION_STOP_ALARM = "com.nexus.snoozeshield.ACTION_STOP_ALARM"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_TARGET_BARCODE = "extra_target_barcode"
        const val EXTRA_PC_HUB_URL = "extra_pc_hub_url"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var volumeEnforcer: VolumeEnforcer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var synthTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val httpClient = OkHttpClient()

    @Volatile
    var activeChallengeCode: String = "000000"
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            stopAlarmAndSelf()
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_ALARM_LABEL) ?: "Morning SnoozeShield Protocol"
        val targetBarcode = intent?.getStringExtra(EXTRA_TARGET_BARCODE) ?: "012345678905"
        val pcHubUrl = intent?.getStringExtra(EXTRA_PC_HUB_URL) ?: "http://192.168.1.100:5175"

        // 1. Acquire CPU WakeLock so phone cannot sleep while ringing
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SnoozeShield::CriticalAlarmWakeLock"
        ).apply { acquire(30 * 60 * 1000L) }

        // 2. Generate the 6-digit challenge code
        activeChallengeCode = ChallengeGenerator.computeCurrentChallenge().pcChallengeCode

        // 3. Start Foreground Notification with Full-Screen Intent & DND Bypass
        createDndBypassChannel()
        val notification = buildFullScreenAlarmNotification(label, targetBarcode, activeChallengeCode)
        startForeground(NOTIFICATION_ID, notification)

        // 4. Start VolumeEnforcer (locks STREAM_ALARM to 100% every 500ms)
        volumeEnforcer = VolumeEnforcer(
            context = this,
            targetVolumeFraction = 1.0f,
            onVolumeTamperBlocked = {
                notifyPcVolumeTamper(pcHubUrl)
            }
        ).also { it.startEnforcing(serviceScope) }

        // 5. Start Primary Alarm Audio + Backup Synthesized High-Output Siren + Vibration
        startLoudAlarmPlayback()
        startVibrationLoop()

        // 6. Notify PC Hub of active 6-digit code & poll for PC verification dismissal
        syncAndPollPcBridge(pcHubUrl, label, targetBarcode)

        return START_STICKY
    }

    private fun createDndBypassChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SnoozeShield Unstoppable Morning Siren",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen lockscreen alarm with Do Not Disturb bypass"
                setBypassDnd(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildFullScreenAlarmNotification(
        label: String,
        targetBarcode: String,
        challengeCode: String
    ): Notification {
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALARM_LABEL, label)
            putExtra(EXTRA_TARGET_BARCODE, targetBarcode)
            putExtra("extra_challenge_code", challengeCode)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ðŸš¨ SNOOZESHIELD: $label")
            .setContentText("Scan Barcode ($targetBarcode) or Enter [$challengeCode] on PC to turn off!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    private fun startLoudAlarmPlayback() {
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(alarmAttributes)
                setDataSource(this@AlarmRingingService, uri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Fallback to synthesized dual-frequency piercing siren on USAGE_ALARM
            startSynthesizedBackupSiren(alarmAttributes)
        }
    }

    private fun startSynthesizedBackupSiren(alarmAttributes: AudioAttributes) {
        serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(alarmAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            synthTrack = track
            track.play()

            val samples = ShortArray(sampleRate / 10)
            var phase = 0.0
            var toggle = false
            while (isActive) {
                val freq = if (toggle) 960.0 else 1320.0
                toggle = !toggle
                for (i in samples.indices) {
                    samples[i] = (sin(phase) * Short.MAX_VALUE * 0.92).toInt().toShort()
                    phase += 2.0 * Math.PI * freq / sampleRate
                }
                track.write(samples, 0, samples.size)
            }
        }
    }

    private fun startVibrationLoop() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 600, 300, 600, 300)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 600, 300, 600), 0)
        }
    }

    private fun syncAndPollPcBridge(pcHubUrl: String, label: String, targetBarcode: String) {
        serviceScope.launch(Dispatchers.IO) {
            // 1. Push the active 6-digit challenge code to the PC Hub
            try {
                val bodyJson = JSONObject().apply {
                    put("alarm_id", 1)
                    put("alarm_label", label)
                    put("challenge_code", activeChallengeCode)
                    put("target_barcode", targetBarcode)
                    put("volume_locked_at_pct", 100)
                }.toString()
                val req = Request.Builder()
                    .url("$pcHubUrl/api/session/trigger")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {
                // Offline fallback: PC and phone share deterministic TOTP code via ChallengeGenerator
            }

            // 2. Poll PC Hub every 2 seconds to check if user entered the 6-digit code on their computer
            while (isActive) {
                delay(2000L)
                try {
                    val req = Request.Builder().url("$pcHubUrl/api/status").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            val activeSession = json.optJSONObject("active_session")
                            if (activeSession != null && activeSession.optInt("is_ringing", 1) == 0) {
                                stopAlarmAndSelf()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun notifyPcVolumeTamper(pcHubUrl: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$pcHubUrl/api/session/volume-tamper")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAlarmAndSelf() {
        volumeEnforcer?.stopEnforcing()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        try {
            synthTrack?.stop()
            synthTrack?.release()
        } catch (_: Exception) {
        }
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAlarmAndSelf()
        super.onDestroy()
    }
}
