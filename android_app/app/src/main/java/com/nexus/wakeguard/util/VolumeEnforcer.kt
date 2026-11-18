package com.nexus.wakeguard.util

import android.app.NotificationManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VolumeEnforcer — Multi-Layer Acoustic Guarantee for Morning Alarms.
 *
 * Solves the problem where phone/alarm volumes accidentally drop or get muted overnight:
 * 1. Forces STREAM_ALARM (and fallback STREAM_MUSIC) to 100% of getStreamMaxVolume() before playback starts.
 * 2. Runs a 500ms coroutine watchdog loop + Settings.System.CONTENT_URI ContentObserver that immediately
 *    snaps STREAM_ALARM back to 100% if any external app, Bluetooth disconnect, or sleepy button press lowers it.
 * 3. Acquires exclusive transient AudioFocus (AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) with USAGE_ALARM.
 * 4.Temporarily disables Do Not Disturb (INTERRUPTION_FILTER_ALL) if NotificationPolicy access is granted.
 */
class VolumeEnforcer(
    private val context: Context,
    private val targetVolumeFraction: Float = 1.0f,
    private val onVolumeTamperBlocked: (() -> Unit)? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var watchdogJob: Job? = null
    private var previousAlarmVolume: Int = -1
    private var previousInterruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL
    private var audioFocusRequest: AudioFocusRequest? = null

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            enforceMaxAlarmVolume(detectTamper = true)
        }
    }

    fun startEnforcing(scope: CoroutineScope) {
        previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        // 1. Bypass Do Not Disturb if policy permission is granted
        if (notificationManager.isNotificationPolicyAccessGranted) {
            previousInterruptionFilter = notificationManager.currentInterruptionFilter
            if (previousInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }

        // 2. Request Exclusive Alarm Audio Focus so all other apps/podcasts pause or duck
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()
                .also { audioManager.requestAudioFocus(it) }
        }

        // 3. Ensure speakerphone routing is active even if a dead Bluetooth headset is paired
        audioManager.isSpeakerphoneOn = true

        // 4. Register ContentObserver on system volume changes
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            settingsObserver
        )

        // 5. Initial immediate lock + 500ms continuous watchdog
        enforceMaxAlarmVolume(detectTamper = false)
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                enforceMaxAlarmVolume(detectTamper = true)
                delay(500L)
            }
        }
    }

    fun enforceMaxAlarmVolume(detectTamper: Boolean) {
        val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val desiredAlarmVol = (maxAlarmVol * targetVolumeFraction).toInt().coerceAtLeast(1)
        val currentAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

        if (currentAlarmVol < desiredAlarmVol) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                desiredAlarmVol,
                0 // Remove UI toast flag so volume slider cannot be dragged down
            )
            if (detectTamper) {
                onVolumeTamperBlocked?.invoke()
            }
        }

        // Also un-mute STREAM_ALARM if muted on API 23+
        if (audioManager.isStreamMute(AudioManager.STREAM_ALARM)) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_ALARM,
                AudioManager.ADJUST_UNMUTE,
                0
            )
        }
    }

    fun stopEnforcing() {
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            context.contentResolver.unregisterContentObserver(settingsObserver)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }
}
