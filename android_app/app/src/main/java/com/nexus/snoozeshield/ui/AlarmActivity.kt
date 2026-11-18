package com.nexus.snoozeshield.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nexus.snoozeshield.service.AlarmRingingService
import com.nexus.snoozeshield.util.ChallengeGenerator
import com.nexus.snoozeshield.util.MlKitBarcodeAnalyzer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * AlarmActivity â€” Full-Screen Lockscreen Challenge Activity.
 *
 * Implements all core user requirements:
 * 1. Full-Screen Intent Lockscreen Wake (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `FLAG_KEEP_SCREEN_ON`).
 * 2. `OnBackPressedCallback(true)` registered with `onBackPressedDispatcher` so the user CANNOT
 *    swipe back or exit the alarm until the challenge is completed.
 * 3. Hardware Volume Button Interceptor (`onKeyDown`): swallows `KEYCODE_VOLUME_DOWN` and `KEYCODE_VOLUME_MUTE`
 *    and immediately forces `STREAM_ALARM` back to 100% max volume.
 * 4. Dual Disarm Modes:
 *    - **ML Kit Barcode Scanning**: Live CameraX viewfinder scanning for the target barcode (e.g. bathroom toothpaste).
 *    - **6-Digit Computer Challenge**: Displays the live randomly generated 6-digit number that must be entered
 *      into the SnoozeShield PC Challenge Terminal (or accepts the 6-digit Offline PC Unlock Confirmation Token).
 */
class AlarmActivity : ComponentActivity() {

    private var challengeResolved = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Camera permission required for ML Kit Barcode scan â€” use 6-Digit PC Challenge!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockscreenWakeFlags()

        // Block system Back button / gesture using OnBackPressedCallback
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@AlarmActivity,
                    "ðŸš¨ SnoozeShield Locked! Scan your barcode or enter the 6-digit code on your computer.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val alarmLabel = intent.getStringExtra(AlarmRingingService.EXTRA_ALARM_LABEL) ?: "Morning Get-Out-Of-Bed Protocol"
        val targetBarcode = intent.getStringExtra(AlarmRingingService.EXTRA_TARGET_BARCODE) ?: "012345678905"

        setContent {
            SnoozeShieldChallengeScreen(
                alarmLabel = alarmLabel,
                targetBarcode = targetBarcode,
                onDisarmSuccess = { method ->
                    disarmAndFinish(method)
                }
            )
        }
    }

    private fun configureLockscreenWakeFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    /**
     * Swallow hardware Volume Down, Volume Up, and Mute keys so the alarm cannot be silenced by buttons.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        ) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            Toast.makeText(
                this,
                "ðŸ”Š Volume Guard Active: Alarm volume locked at 100%!",
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun disarmAndFinish(method: String) {
        if (challengeResolved) return
        challengeResolved = true

        val stopIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = AlarmRingingService.ACTION_STOP_ALARM
        }
        startService(stopIntent)

        Toast.makeText(this, "âœ“ Alarm Disarmed via $method! Good morning!", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

@Composable
fun SnoozeShieldChallengeScreen(
    alarmLabel: String,
    targetBarcode: String,
    onDisarmSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var challengePair by remember { mutableStateOf(ChallengeGenerator.computeCurrentChallenge()) }
    var offlineAckInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCameraScanner by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            challengePair = ChallengeGenerator.computeCurrentChallenge()
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // TOP ALARM HEADER
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ðŸš¨ SNOOZESHIELD SIREN ACTIVE â€¢ 100% VOLUME LOCK",
                color = Color(0xFFF43F5E),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = alarmLabel,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // OPTION 1: 6-DIGIT COMPUTER CHALLENGE BOX
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111827), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF06B6D4), RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OPTION 1: TYPE THIS 6-DIGIT CODE ON YOUR COMPUTER",
                color = Color(0xFF22D3EE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = challengePair.pcChallengeCode,
                color = Color(0xFFFBBF24),
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp
            )
            Text(
                text = "Rotates in ${challengePair.secondsRemaining}s â€¢ Synced with Nexus Master OS / PC Hub",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = offlineAckInput,
                onValueChange = {
                    offlineAckInput = it.filter { ch -> ch.isDigit() }.take(6)
                    if (ChallengeGenerator.verifyOfflineUnlockToken(offlineAckInput)) {
                        onDisarmSuccess("PC_OFFLINE_UNLOCK_TOKEN")
                    }
                },
                label = { Text("Or enter 6-digit PC Offline Unlock Token", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // OPTION 2: ML KIT BARCODE SCANNER VIEWPORT
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
                .background(Color(0xFF111827), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFA855F7), RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OPTION 2: ML KIT BARCODE SCANNER (TARGET: $targetBarcode)",
                color = Color(0xFFC084FC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (showCameraScanner) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(
                                        ContextCompat.getMainExecutor(ctx),
                                        MlKitBarcodeAnalyzer(targetBarcode) { scannedCode ->
                                            onDisarmSuccess("MLKIT_BARCODE ($scannedCode)")
                                        }
                                    )
                                }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analyzer
                                )
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        errorMessage?.let {
            Text(text = it, color = Color(0xFFF43F5E), fontSize = 12.sp)
        }
    }
}
