package com.example.fd.video.recorder

import ai.fd.thinklet.camerax.ThinkletAudioRecordWrapperFactory
import ai.fd.thinklet.camerax.ThinkletAudioSettingsPatcher
import ai.fd.thinklet.camerax.ThinkletMic
import ai.fd.thinklet.camerax.mic.ThinkletMics
import ai.fd.thinklet.camerax.mic.multichannel.FiveCh
import ai.fd.thinklet.camerax.mic.xfe.Xfe
import ai.fd.thinklet.camerax.vision.Vision
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaActionSound
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresPermission
import androidx.annotation.WorkerThread
import androidx.camera.core.Preview
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.fd.video.recorder.camerax.RawAudioRecCaptureRepository
import com.example.fd.video.recorder.camerax.ThinkletRecorder
import com.example.fd.video.recorder.camerax.impl.ThinkletAudioRecordWrapperRepositoryImpl
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ThinkletRecorder]と連携し、UI用のデータの提供やUIからのイベントを処理するクラス
 */
@SuppressLint("MissingPermission")
@Stable
class RecorderState(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    enableVision: Boolean = BuildConfig.ENABLE_VISION,
    visionPort: Int = BuildConfig.VISION_PORT
) : TextToSpeech.OnInitListener {
    val isLandscapeCamera: Boolean = isLandscape(context)

    private val _isRecording: MutableState<Boolean> = mutableStateOf(false)
    val isRecording: Boolean
        get() = _isRecording.value

    // true == 次の動画を撮影する
    private val isKeepRecording = AtomicBoolean(false)

    @GuardedBy("mediaActionSoundMutex")
    private var mediaActionSound: MediaActionSound? = null
    private val mediaActionSoundMutex: Mutex = Mutex()

    @GuardedBy("recorderMutex")
    private var recorder: ThinkletRecorder? = null
    private val recorderMutex: Mutex = Mutex()

    private val vision: Vision? = if (enableVision) Vision() else null

    private val thinkletAudioRecordWrapperRepository = ThinkletAudioRecordWrapperRepositoryImpl()
    private val rawAudioRecCaptureRepository = RawAudioRecCaptureRepository(
        coroutineScope = lifecycleOwner.lifecycleScope,
        audioRecordWrapperRepository = thinkletAudioRecordWrapperRepository,
    )

    private val tts: TextToSpeech = TextToSpeech(context, this)

    init {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                mediaActionSoundMutex.withLock {
                    mediaActionSound = loadMediaActionSound()
                }
                try {
                    awaitCancellation()
                } finally {
                    mediaActionSoundMutex.withLock {
                        mediaActionSound?.release()
                    }
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    awaitCancellation()
                } finally {
                    recorderMutex.withLock {
                        isKeepRecording.set(false)
                        recorder?.requestStop()
                    }
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vision?.start(port = visionPort)
                try {
                    awaitCancellation()
                } finally {
                    vision?.stop()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINESE
        }
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    fun releaseRecorder() {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                recorder?.requestStop()
                recorder = null
            }
        }
    }

    fun registerSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                recorder = ThinkletRecorder.create(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    mic = micType(),
                    analyzer = vision,
                    previewSurfaceProvider = surfaceProvider,
                    rawAudioRecCaptureRepository = rawAudioRecCaptureRepository,
                    recordEventListener = ::handleRecordEvent,
                )
            }
        }
    }

    fun toggleRecordState() {
        lifecycleOwner.lifecycleScope.launch {
            toggleRecordStateInternal()
        }
    }

    private suspend fun toggleRecordStateInternal() = recorderMutex.withLock {
        val localRecorder = recorder ?: return@withLock

        val keep = isKeepRecording.get()
        isKeepRecording.set(!keep)

        if (_isRecording.value) {
            localRecorder.requestStop()
        } else {
            localRecorder.requestStart()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA])
    private suspend fun ThinkletRecorder.requestStart() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
        val file = File(
            context.getExternalFilesDir(null),
            "${timeStamp}.mp4"
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "StartRecord: ${file.absoluteFile}", Toast.LENGTH_LONG).show()
        }
        val audioFile = File(
            context.getExternalFilesDir(null),
            "${timeStamp}.raw"
        )
        this.startRecording(file, audioFile)
    }

    @WorkerThread
    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                playMediaActionSound(MediaActionSound.START_VIDEO_RECORDING)
                tts.speak("recording started", TextToSpeech.QUEUE_FLUSH, null, "")
                _isRecording.value = true
            }

            is VideoRecordEvent.Finalize -> {
                playMediaActionSound(MediaActionSound.STOP_VIDEO_RECORDING)
                tts.speak("recording finished", TextToSpeech.QUEUE_FLUSH, null, "")
                _isRecording.value = false
                if (isKeepRecording.get()) {
                    // 次の動画を撮影
                    lifecycleOwner.lifecycleScope.launch {
                        recorderMutex.withLock {
                            recorder?.requestStart()
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadMediaActionSound(): MediaActionSound = withContext(Dispatchers.IO) {
        MediaActionSound().apply {
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    private fun playMediaActionSound(sound: Int) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            mediaActionSoundMutex.withLock {
                mediaActionSound?.play(sound)
            }
        }
    }

    private fun micType(mic: String = BuildConfig.MIC_TYPE): ThinkletMic? {
        return when (mic) {
            "5ch" -> ThinkletMics.FiveCh
            "xfe" -> ThinkletMics.Xfe(checkNotNull(context.getSystemService<AudioManager>()))
            "raw" -> buildThinkletMic(thinkletAudioRecordWrapperRepository)
            else -> null
        }
    }

    private fun buildThinkletMic(
        thinkletAudioRecordWrapperRepository: ThinkletAudioRecordWrapperFactory,
    ): ThinkletMic {
        Logging.d("RawAudioEnabled!")
        return object : ThinkletMic {
            override fun getAudioSettingsPatcher(): ThinkletAudioSettingsPatcher? = null

            override fun getAudioRecordWrapperFactory(): ThinkletAudioRecordWrapperFactory? =
                thinkletAudioRecordWrapperRepository
        }
    }

    /**
     * Get current battery percentage
     */
    private fun getBatteryPercentage(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Get current network status information
     */
    @SuppressLint("MissingPermission")
    private fun getNetworkStatus(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Check if WiFi is enabled
        if (!wifiManager.isWifiEnabled) {
            return "wifi is disabled"
        }
        
        // Check if connected to WiFi network
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isWifiConnected = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        
        if (!isWifiConnected) {
            return "not connected to wifi"
        }
        
        // Get current connected WiFi information
        val connectionInfo = wifiManager.connectionInfo
        val currentSsid = connectionInfo.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "unknown network"
        
        return currentSsid
    }

    /**
     * Speak battery status via TTS
     */
    fun speakBatteryStatus() {
        val batteryPercentage = getBatteryPercentage()
        val message = "battery status: ${batteryPercentage} percentage remaining"
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "battery_status")
    }

    /**
     * Speak network status via TTS
     */
    fun speakNetworkStatus() {
        val networkStatus = getNetworkStatus()
        val message = "network status: $networkStatus"
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, "network_status")
    }

    /**
     * Speak both battery and network status via TTS
     */
    fun speakBatteryAndNetworkStatus() {
        val batteryPercentage = getBatteryPercentage()
        val networkStatus = getNetworkStatus()
        val message = "battery status: ${batteryPercentage} percentage remaining, network status: $networkStatus"
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "battery_network_status")
    }

    companion object {
        /**
         * カメラが横向きかどうかを判定する
         */
        fun isLandscape(context: Context): Boolean {
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val cid = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cid)
            val angle = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            return arrayOf(0, 180).contains(angle)
        }
    }
}
