package com.example.fd.video.recorder

import android.app.Application
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

class TestViewModel(application: Application, lifecycle: Lifecycle) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val ledClient = LedClient(application)
    private var isLedOn = false
    private var isBlinking = false
    private val handler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(application, this)

    private val blinkRunnable: Runnable = object : Runnable {
        override fun run() {
            isLedOn = !isLedOn
            ledClient.updateCameraLed(isLedOn)
            handler.postDelayed(this, 500)
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
    private val lifecycle: Lifecycle
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TestViewModel(application, lifecycle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
