package com.example.fd.video.recorder

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ai.fd.thinklet.sdk.led.LedClient
import java.util.Locale
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification

class TestViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val ledClient = LedClient(application)
    private var isLedOn = false
    private var isBlinking = false
    private val handler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(application, this)
    private val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var isPowerLedOn = false

    private val blinkRunnable: Runnable = object : Runnable {
        override fun run() {
            isLedOn = !isLedOn
            ledClient.updateCameraLed(isLedOn)
            handler.postDelayed(this, 500)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("power_led_channel", "Power LED", NotificationManager.IMPORTANCE_DEFAULT)
            channel.enableLights(true)
            channel.lightColor = Color.GREEN
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            }
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    fun toggleLed() {
        isBlinking = !isBlinking
        if (isBlinking) {
            handler.post(blinkRunnable)
        } else {
            handler.removeCallbacks(blinkRunnable)
            isLedOn = false
            ledClient.updateCameraLed(false)
        }
    }

    fun togglePowerLed() {
        isPowerLedOn = !isPowerLedOn
        if (isPowerLedOn) {
            val notification = NotificationCompat.Builder(getApplication(), "power_led_channel")
                .setContentTitle("Power LED")
                .setContentText("Power LED is ON")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setLights(Color.GREEN, 1000, 1000)
                .build()
            notificationManager.notify(1, notification)
        } else {
            notificationManager.cancel(1)
        }
    }

    fun playTtsMessage() {
        tts.speak("recording finished", TextToSpeech.QUEUE_FLUSH, null, "")
    }

    override fun onCleared() {
        handler.removeCallbacks(blinkRunnable)
        ledClient.updateCameraLed(false)
        tts.stop()
        tts.shutdown()
        super.onCleared()
    }
}

class TestViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TestViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
