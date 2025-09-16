package com.example.fd.video.recorder

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

class AudioTestViewModel(application: Application, lifecycle: Lifecycle) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tts: TextToSpeech = TextToSpeech(application, this)
    private var ringtone: android.media.Ringtone? = null


    init {
        // 初期化リスナーを設定
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

    fun increaseVolume(streamType: Int) {
        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }

    fun decreaseVolume(streamType: Int) {
        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    fun playNotification(context: Context) {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playRingtone(context: Context) {
        try {
            if (ringtone == null) {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
            }
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRingtone() {
        ringtone?.stop()
        ringtone = null
    }

    fun playTtsMessage() {
        tts.speak("recording finished", TextToSpeech.QUEUE_FLUSH, null, "")
    }

    override fun onCleared() {
        tts.stop()
        tts.shutdown()
        ringtone?.stop()
        ringtone = null
        super.onCleared()
    }
}

class AudioTestViewModelFactory(
    private val application: Application,
    private val lifecycle: Lifecycle
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AudioTestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AudioTestViewModel(application, lifecycle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
