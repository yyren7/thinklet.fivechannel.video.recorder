package com.example.fd.video.recorder.state

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.example.fd.video.recorder.util.Logging
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech = TextToSpeech(context, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
        }
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    private fun getBatteryPercentage(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @SuppressLint("MissingPermission")
    private fun getNetworkStatus(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (!wifiManager.isWifiEnabled) {
            return "wifi is disabled"
        }

        val activeNetwork = connectivityManager.activeNetwork ?: return "not connected to wifi"
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isWifiConnected = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false

        if (!isWifiConnected) {
            return "not connected to wifi"
        }

        val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = networkCapabilities?.transportInfo as? android.net.wifi.WifiInfo
            wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "unknown network"
        } else {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid.removePrefix("\"").removeSuffix("\"")
        }

        return "connected to wifi ${spellOutWifiName(currentSsid)}"
    }

    private fun spellOutWifiName(wifiName: String): String {
        return wifiName.map { char ->
            when {
                char.isLetter() -> char.toString()
                char.isDigit() -> char.toString()
                char == '-' -> "dash"
                char == '_' -> "underscore"
                char == '.' -> "dot"
                char == ' ' -> "space"
                else -> char.toString()
            }
        }.joinToString(" ")
    }

    fun getBatteryAndNetworkStatusMessage(): String {
        val batteryPercentage = getBatteryPercentage()
        val networkStatus = getNetworkStatus()
        return "battery status: ${batteryPercentage}% remaining, network status: $networkStatus"
    }

    fun speak(message: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, utteranceId: String? = null) {
        Logging.d("TTS speak: $message")
        tts.speak(message, queueMode, null, utteranceId)
    }

    fun speakBatteryStatus() {
        val batteryPercentage = getBatteryPercentage()
        val message = "battery status: ${batteryPercentage} percentage remaining"
        speak(message, utteranceId = "battery_status")
    }

    fun speakNetworkStatus() {
        val networkStatus = getNetworkStatus()
        val message = "network status: $networkStatus"
        speak(message, TextToSpeech.QUEUE_ADD, "network_status")
    }

    fun speakBatteryAndNetworkStatus() {
        val message = getBatteryAndNetworkStatusMessage()
        speak(message, utteranceId = "battery_network_status")
    }

    fun speakPowerDown() {
        val message = "power down"
        speak(message, utteranceId = "power_down")
    }

    fun speakApplicationPrepared() {
        val message = "application prepared"
        speak(message, utteranceId = "app_prepared")
    }
}
