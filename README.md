# WakeGuard — Barcode & PC 6-Digit Challenge Alarm App

An unstoppable Android morning alarm + Desktop Challenge Hub designed to force you out of bed in the morning and guarantee the alarm sound is **always loud**, even if your phone volume was accidentally turned down or muted the night before.

---

## 1. Core Architecture & Gemini Recommendations Implemented

### A. Android Barcode & Lockscreen Alarm App (`android_app/`)
1. **Google ML Kit Barcode Scanning (`MlKitBarcodeAnalyzer.kt`)**:
   - Uses `com.google.mlkit:barcode-scanning:17.3.0` paired with a CameraX `ImageAnalysis` pipeline.
   - Scans UPC-A, UPC-E, EAN-13, EAN-8, Code-128, and QR codes in real time. Place a target barcode in your bathroom (e.g., toothpaste tube) or kitchen (coffee canister) so you must physically leave bed to turn off the siren.
2. **Doze-Immune `AlarmManager.setAlarmClock()` & Full-Screen Intent (`AlarmScheduler.kt`, `AlarmRingingService.kt`, `AlarmActivity.kt`)**:
   - Uses `AlarmManager.setAlarmClock()` (which bypasses Android Doze mode throttling) to fire `AlarmReceiver`.
   - Starts `AlarmRingingService` (`foregroundServiceType="mediaPlayback"`) and posts a high-priority notification with `setFullScreenIntent(pendingIntent, true)` to wake the screen and launch `AlarmActivity` directly over the lock screen (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `FLAG_KEEP_SCREEN_ON`).
3. **Lockscreen Navigation Trap (`OnBackPressedCallback`)**:
   - Registers an enabled `OnBackPressedCallback(true)` on `AlarmActivity.onBackPressedDispatcher` so system back gestures and back buttons are blocked until either the barcode is scanned or the 6-digit computer challenge is completed.

---

### B. Bulletproof "Always-Loud" Volume Guard (`VolumeEnforcer.kt`)
To solve the problem where phone or alarm volumes change overnight and you don't hear the alarm in the morning:
1. **Dedicated `STREAM_ALARM` / `AudioAttributes.USAGE_ALARM` Routing**:
   - Plays alarm audio exclusively on the `USAGE_ALARM` stream with `FLAG_AUDIBILITY_ENFORCED` (independent of silent/vibrate ringer switches or media volume).
2. **500ms Continuous Max-Volume Watchdog + `ContentObserver`**:
   - Before playback begins—and every `500ms` while ringing—`VolumeEnforcer` checks `AudioManager.getStreamVolume(STREAM_ALARM)` against `getStreamMaxVolume(STREAM_ALARM)` and forces it back to **100% (`setStreamVolume(STREAM_ALARM, maxVol, 0)`)** and un-mutes the stream (`ADJUST_UNMUTE`).
3. **Hardware Volume Button Interceptor (`AlarmActivity.onKeyDown`)**:
   - Intercepts `KEYCODE_VOLUME_DOWN`, `KEYCODE_VOLUME_UP`, and `KEYCODE_VOLUME_MUTE` while the alarm screen is active so sleepy button presses cannot lower the volume.
4. **Do Not Disturb (DND) Bypass & Exclusive Audio Focus**:
   - Configures the `NotificationChannel` with `setBypassDnd(true)` and temporarily sets `NotificationManager.INTERRUPTION_FILTER_ALL` (when notification policy access is granted), while acquiring `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`.
5. **Synthesized High-Decibel PCM Backup Siren (`AudioTrack`)**:
   - If the default ringtone URI ever fails to load, `AlarmRingingService` automatically synthesizes a piercing dual-frequency (`960 Hz` / `1320 Hz`) waveform directly to `AudioTrack` on `USAGE_ALARM`.

---

### C. 6-Digit Computer Challenge Hub & Nexus Master OS Integration (`pc_bridge/`)
- Runs a FastAPI + Tailwind Web Hub on **Port `5175`** (`python start.py --no-browser`).
- Integrated directly into **Nexus Master OS** (`manifests/apps.json`) so it can be launched and embedded inside the Project Hub viewport (`http://localhost:5175`).
- **Dual LAN + Offline TOTP Synchronization (`ChallengeGenerator.kt` & `pc_bridge/server.py`)**:
  - When the phone alarm rings on your local Wi-Fi, it pushes its randomly generated 6-digit number to the PC Hub (`POST /api/session/trigger`) and polls every 2 seconds (`GET /api/status`). The moment you type the 6-digit number on your computer, the phone alarm automatically disarms!
  - **100% Offline Fallback**: Even if Wi-Fi is disconnected, both the phone and PC compute a deterministic HMAC-SHA256 6-digit challenge & 6-digit unlock confirmation token over 60-second epochs.

---

## 2. Installing & Auto-Updating on Your Phone via Obtainium

This repository includes an automated GitHub Actions pipeline (`.github/workflows/android-release.yml`) that compiles the Android app in the cloud on every push to `main` and publishes a GitHub Release with `wakeguard-alarm-v1.0.<build>.apk` attached—which **Obtainium** tracks directly!

### Step 1: Make the Repo Accessible to Obtainium (Choose One)
- **Option A (Easiest — Public Repo, No Token Needed)**:
  Run `gh repo edit jjk564/wakeguard-alarm --visibility public --accept-visibility-change-consequences` (or ask Antigravity to flip it to public). There are zero personal secrets or API keys in this repo.
- **Option B (Keep Repo Private)**:
  In **Obtainium → Settings → GitHub Personal Access Token**, paste a GitHub token with `repo` read access.

### Step 2: Add to Obtainium on Your Phone
1. Open **Obtainium** on your Android phone and tap **Add App**.
2. In **App Source URL**, enter:
   ```text
   https://github.com/jjk564/wakeguard-alarm
   ```
3. Tap **Add** and then **Install**. Whenever we push code changes at the end of a turn, GitHub Actions will automatically build a new release (`v1.0.x`) and Obtainium will notify you with a 1-tap update!

---

## 3. Quick Start (PC Challenge Hub)

```powershell
# Launch the WakeGuard PC Challenge Hub on http://localhost:5175
python start.py --no-browser
```
