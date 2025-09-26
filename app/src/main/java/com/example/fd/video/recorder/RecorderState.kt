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
import android.os.Build
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
import com.example.fd.video.recorder.camerax.ThinkletRecorder
import com.example.fd.video.recorder.camerax.impl.ThinkletAudioRecordWrapperRepositoryImpl
import com.example.fd.video.recorder.device.audio.RawAudioRecCaptureRepository
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
import com.example.fd.video.recorder.camerax.UseCaseStatusListener
import com.example.fd.video.recorder.state.LedController
import com.example.fd.video.recorder.state.TTSManager

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
) : UseCaseStatusListener {
    val isLandscapeCamera: Boolean = isLandscape(context)

    private val _isRecording: MutableState<Boolean> = mutableStateOf(false)
    val isRecording: Boolean
        get() = _isRecording.value

    private val _isPreviewEnabled: MutableState<Boolean> = mutableStateOf(false)
    val isPreviewEnabled: Boolean
        get() = _isPreviewEnabled.value

    private val _isStreamingEnabled: MutableState<Boolean> = mutableStateOf(false)
    val isStreamingEnabled: Boolean
        get() = _isStreamingEnabled.value

    private val _isRebinding: MutableState<Boolean> = mutableStateOf(false)
    val isRebinding: Boolean
        get() = _isRebinding.value

    private val _rebindingCount: MutableState<Int> = mutableStateOf(0)
    val rebindingCount: Int
        get() = _rebindingCount.value

    private val ledController = LedController(context)
    val ttsManager = TTSManager(context)

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
                                val success = recorder?.setStreamingEnabled(true) ?: false
                                if (success) {
                                    _isStreamingEnabled.value = true
                                }
                                // 录像期间失败是正常的，会在录像结束后自动处理
                            }
                        }
                    }

                    override fun onClientDisconnected() {
                        lifecycleOwner.lifecycleScope.launch {
                            recorderMutex.withLock {
                                val success = recorder?.setStreamingEnabled(false) ?: false
                                if (success) {
                                    _isStreamingEnabled.value = false
                                }
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

    fun release() {
        vision?.stop()
        ttsManager.release()
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun setRebinding(rebinding: Boolean) {
        _isRebinding.value = rebinding
        if (rebinding) {
            _rebindingCount.value += 1
        }
    }

    fun releaseRecorder() {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                recorder?.requestStop()
                recorder = null
            }
        }
    }

    fun getDebugUseCaseStatus(): String {
        return try {
            recorder?.getUseCaseStatus() ?: "Camera not initialized"
        } catch (e: Exception) {
            "Failed to get status"
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
                        micType = BuildConfig.MIC_TYPE,
                        analyzer = vision,
                        rawAudioRecCaptureRepository = rawAudioRecCaptureRepository,
                        recordEventListener = ::handleRecordEvent,
                        setRebinding = ::setRebinding,
                        useCaseStatusListener = this@RecorderState,
                        showToast = ::showToast
                    )
                    recorder?.camera?.cameraInfo?.cameraState?.observe(lifecycleOwner) { cameraState ->
                        cameraState.error?.let { error ->
                            val errorCode = when (error.code) {
                                CameraState.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                                CameraState.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                                CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "ERROR_OTHER_RECOVERABLE_ERROR"
                                CameraState.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                                CameraState.ERROR_CAMERA_FATAL_ERROR -> "ERROR_CAMERA_FATAL_ERROR"
                                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "ERROR_DO_NOT_DISTURB_MODE_ENABLED"
                                else -> "UNKNOWN_ERROR"
                            }
                            Logging.e("Camera state error: $errorCode, cause: ${error.cause?.message}", error.cause)
                        }
                    }
                }
                recorder?.setPreviewSurfaceProvider(surfaceProvider)
            }
        }
    }


    fun toggleRecordState() {
        if (isRebinding) {
            Logging.d("toggleRecordState ignored due to rebinding.")
            return
        }
        lifecycleOwner.lifecycleScope.launch {
            toggleRecordStateInternal()
        }
    }

    fun togglePreviewState() {
        _isPreviewEnabled.value = !_isPreviewEnabled.value
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
        showToast("StartRecord: ${file.absoluteFile}")
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
                val message = "recording started"
                ttsManager.speak(message)
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    _isRecording.value = true
                    ledController.startLedBlinking()
                }
            }

            is VideoRecordEvent.Finalize -> {
                // Make sure audio recording is stopped
                rawAudioRecCaptureRepository.stopRecording()
                
                playMediaActionSound(MediaActionSound.STOP_VIDEO_RECORDING)
                val message = "recording finished"
                ttsManager.speak(message)
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    _isRecording.value = false
                    ledController.stopLedBlinking()
                }
                lifecycleOwner.lifecycleScope.launch {
                    recorderMutex.withLock {
                        recorder?.restoreStateAndRebuild()
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
            "raw" -> null // raw模式下，CameraX不使用ThinkletMic，避免与RawAudioRecCaptureRepository竞争
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

    // 实现 UseCaseStatusListener 接口
    override fun onPreviewStateChanged(enabled: Boolean) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            _isPreviewEnabled.value = enabled
        }
    }

    override fun onStreamingStateChanged(enabled: Boolean) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            _isStreamingEnabled.value = enabled
        }
    }

    override fun onRecordingStateChanged(isRecording: Boolean) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            _isRecording.value = isRecording
        }
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
