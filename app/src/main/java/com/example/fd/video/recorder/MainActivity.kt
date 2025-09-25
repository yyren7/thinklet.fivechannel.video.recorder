package com.example.fd.video.recorder

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.fd.video.recorder.compose.MainScreen
import com.example.fd.video.recorder.compose.TestScreen
import com.example.fd.video.recorder.ui.theme.MultiMicVideoRecorderTheme
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import ai.fd.thinklet.sdk.maintenance.launcher.Extension
import ai.fd.thinklet.sdk.maintenance.power.PowerController
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import android.os.VibratorManager
import androidx.core.content.ContextCompat

/**
 * This is a sample recording application using CameraX for THINKLET.
 * Press the second button (Camera key) to start and stop recording.
 * The output file is saved in mp4 format under `/sdcard/Android/data/com.example.fd.video.recorder/files/`.
 */
class MainActivity : ComponentActivity() {
    private val recorderState: RecorderState by lazy(LazyThreadSafetyMode.NONE) {
        RecorderState(this, this)
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var longPressRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        longPressRunnable = Runnable {
            handlePowerKeyPress()
        }

        // THINKLET auto-start setting
        try {
            val ext = Extension()
            val (pkg, cls) = ext.configure()
            val myPkg = this.packageName
            val myCls = this::class.java.name
            if (pkg != myPkg || cls != myCls) {
                ext.configure(myPkg, myCls)
            }
            if (!ext.isAutoLaunchMode()) {
                ext.enableAutoLaunchMode()
            }
        } catch (e: Exception) {
            Log.e("ThinkletExtension", "Failed to configure auto-launch", e)
        }

        enableEdgeToEdge()
        window.addFlags(FLAG_KEEP_SCREEN_ON)
        vibrate(200)
        recorderState.speakApplicationPrepared()
        setContent {
            var showTestScreen by remember { mutableStateOf(false) }

            MultiMicVideoRecorderTheme {
                if (showTestScreen) {
                    TestScreen(onNavigateBack = { showTestScreen = false })
                } else {
                    MainScreen(
                        modifier = Modifier.fillMaxSize(),
                        recorderState = recorderState,
                        onNavigateToTest = { showTestScreen = true }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorderState.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_POWER -> handlePowerKeyDown(event)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeKeyDown()
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handlePowerKeyDown(event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount == 0) {
            handler.postDelayed(longPressRunnable, 2000)
        }
        return true // Consume the event to prevent default behavior like screen off
    }

    private fun handleVolumeKeyDown(): Boolean {
        // Consume the volume key press event to prevent the system volume adjustment UI from being displayed
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ThinkletKeyEvent", "Key pressed - KeyCode: $keyCode, Event: ${event?.toString()}")
        return when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> handleCameraKeyPress()
            KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeUpKeyPress()
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDownKeyUp()
            KeyEvent.KEYCODE_POWER -> handlePowerKeyUp()
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun handlePowerKeyUp(): Boolean {
        handler.removeCallbacks(longPressRunnable)
        return true
    }

    private fun handleVolumeDownKeyUp(): Boolean {
        // Do not assign a function to this key, just consume the event
        return true
    }

    private fun handleCameraKeyPress(): Boolean {
        recorderState.toggleRecordState()
        return true
    }

    private fun handleVolumeUpKeyPress(): Boolean {
        recorderState.speakBatteryAndNetworkStatus()
        return true
    }

    private fun handlePowerKeyPress(): Boolean {
        Log.d("PowerKey", "Long press on power button detected. Testing vibration.")
        val timings = longArrayOf(0, 200, 200, 200)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrate(timings, amplitudes)
        recorderState.speakPowerDown()
        try {
            // Wait for TTS to finish speaking before shutting down
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Log.e("PowerKey", "TTS wait interrupted", e)
        }
        PowerController().shutdown(this, wait = 1000 /* max wait 1s */)
        return true
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                ContextCompat.getSystemService(this, VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(this, Vibrator::class.java)
        }
    }

    private fun vibrate(pattern: LongArray, amplitudes: IntArray) {
        try {
            val vibrator = getVibrator()
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, -1)
                }
                Log.d("Vibration", "Vibration pattern command sent.")
            }
        } catch (e: Exception) {
            Log.e("Vibration", "Failed to initiate vibration.", e)
        }
    }

    private fun vibrate(durationMillis: Long) {
        try {
            val vibrator = getVibrator()
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(durationMillis)
                }
                Log.d("Vibration", "Vibration command sent for ${durationMillis}ms.")
            }
        } catch (e: Exception) {
            Log.e("Vibration", "Failed to initiate vibration.", e)
        }
    }
}
