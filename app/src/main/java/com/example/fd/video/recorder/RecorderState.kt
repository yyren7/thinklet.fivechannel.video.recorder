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
import ai.fd.thinklet.camerax.vision.ClientConnectionListener
import ai.fd.thinklet.camerax.vision.httpserver.VisionRepository
import ai.fd.thinklet.sdk.led.LedClient
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraState
import androidx.compose.runtime.mutableStateListOf

/**
 * Class that collaborates with [ThinkletRecorder] to provide UI data and handle UI events
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

    private val _isPreviewEnabled: MutableState<Boolean> = mutableStateOf(false)
    val isPreviewEnabled: Boolean
        get() = _isPreviewEnabled.value

    private val _isRebinding: MutableState<Boolean> = mutableStateOf(false)
    val isRebinding: Boolean
        get() = _isRebinding.value

    private val ledClient = LedClient(context)
    private var isLedOn = false
    private var isBlinking = false
    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable: Runnable = object : Runnable {
        override fun run() {
            isLedOn = !isLedOn
            ledClient.updateCameraLed(isLedOn)
            handler.postDelayed(this, 500)
        }
    }
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
                        recorder?.requestStop()
                    }
                }
            }
        }
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vision?.setClientConnectionListener(object : ClientConnectionListener {
                    override fun onClientConnected() {
                        lifecycleOwner.lifecycleScope.launch {
                            recorderMutex.withLock {
                                recorder?.enableVisionUseCase(true)
                            }
                        }
                    }

                    override fun onClientDisconnected() {
                        lifecycleOwner.lifecycleScope.launch {
                            recorderMutex.withLock {
                                recorder?.enableVisionUseCase(false)
                            }
                        }
                    }
                })
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
        vision?.stop()
        tts.stop()
        tts.shutdown()
    }

    private fun startLedBlinking() {
        if (!isBlinking) {
            isBlinking = true
            handler.post(blinkRunnable)
        }
    }

    private fun stopLedBlinking() {
        if (isBlinking) {
            isBlinking = false
            handler.removeCallbacks(blinkRunnable)
            isLedOn = false
            ledClient.updateCameraLed(false)
        }
    }

    fun setRebinding(rebinding: Boolean) {
        _isRebinding.value = rebinding
    }

    fun releaseRecorder() {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                recorder?.requestStop()
                recorder = null
            }
        }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        _isPreviewEnabled.value = enabled
    }

    private fun syncPreviewState(enabled: Boolean) {
        _isPreviewEnabled.value = enabled
    }

    fun getDebugUseCaseStatus(): String {
        return try {
            recorder?.getUseCaseStatus() ?: "Camera未初始化"
        } catch (e: Exception) {
            "状态获取失败"
        }
    }

    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                if (recorder == null) {
                    recorder = ThinkletRecorder.create(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        mic = micType(),
                        analyzer = vision,
                        rawAudioRecCaptureRepository = rawAudioRecCaptureRepository,
                        recordEventListener = ::handleRecordEvent,
                        setRebinding = ::setRebinding,
                        onPreviewStateChanged = ::syncPreviewState,
                    )
                    recorder?.camera?.cameraInfo?.cameraState?.observe(lifecycleOwner) { cameraState ->
                        cameraState.error?.let { error ->
                            val cause = error.cause
                            val errorCode = when (error.code) {
                                CameraState.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                                CameraState.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                                CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "ERROR_OTHER_RECOVERABLE_ERROR"
                                CameraState.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                                CameraState.ERROR_CAMERA_FATAL_ERROR -> "ERROR_CAMERA_FATAL_ERROR"
                                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "ERROR_DO_NOT_DISTURB_MODE_ENABLED"
                                else -> "UNKNOWN_ERROR"
                            }
                            Logging.e("Camera state error: $errorCode, cause: ${cause?.message}", cause)
                        }
                    }
                }
                recorder?.setPreviewSurfaceProvider(surfaceProvider)
            }
        }
    }

    fun prepareToRecord(enableVision: Boolean, enablePreview: Boolean) {
        if (enablePreview) {
            _isPreviewEnabled.value = true
        }
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                val (previewState, visionState) = recorder?.prepareToRecord(enableVision, enablePreview) ?: (false to false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "保存的状态: Preview=$previewState, Vision=$visionState",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    _isRecording.value = true
                    startLedBlinking()
                }
            }

            is VideoRecordEvent.Finalize -> {
                // 确保音频录制已停止（双重保险）
                rawAudioRecCaptureRepository.stopRecording()
                
                playMediaActionSound(MediaActionSound.STOP_VIDEO_RECORDING)
                tts.speak("recording finished", TextToSpeech.QUEUE_FLUSH, null, "")
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    _isRecording.value = false
                    stopLedBlinking()
                }
                lifecycleOwner.lifecycleScope.launch {
                    recorderMutex.withLock {
                        recorder?.restoreStateAndRebind()
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
        val activeNetwork = connectivityManager.activeNetwork ?: return "not connected to wifi"
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isWifiConnected = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        
        if (!isWifiConnected) {
            return "not connected to wifi"
        }
        
        val wifiInfo = networkCapabilities?.transportInfo as? android.net.wifi.WifiInfo
        val currentSsid = wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "unknown network"

        // Make WiFi name more pronounceable by spelling it out
        return "connected to wifi ${spellOutWifiName(currentSsid)}"
    }

    /**
     * Convert WiFi name to spelled-out format for clearer TTS pronunciation
     */
    private fun spellOutWifiName(wifiName: String): String {
        // Spell out all WiFi names character by character for better pronunciation
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
         * Determines if the camera is in landscape orientation
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
