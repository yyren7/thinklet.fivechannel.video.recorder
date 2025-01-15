package com.example.fd.camerax.recorder

import ai.fd.thinklet.camerax.mic.ThinkletMics
import ai.fd.thinklet.camerax.mic.xfe.Xfe
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaActionSound
import android.widget.Toast
import androidx.annotation.GuardedBy
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
import com.example.fd.camerax.recorder.camerax.ThinkletRecorder
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

/**
 * [ThinkletRecorder]と連携し、UI用のデータの提供やUIからのイベントを処理するクラス
 */
@Stable
class RecorderState(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    val isLandscapeCamera: Boolean = isLandscape(context)

    private val _isRecording: MutableState<Boolean> = mutableStateOf(false)
    val isRecording: Boolean
        get() = _isRecording.value

    @GuardedBy("mediaActionSoundMutex")
    private var mediaActionSound: MediaActionSound? = null
    private val mediaActionSoundMutex: Mutex = Mutex()

    @GuardedBy("recorderMutex")
    private var recorder: ThinkletRecorder? = null
    private val recorderMutex: Mutex = Mutex()

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
    }

    fun registerSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        lifecycleOwner.lifecycleScope.launch {
            recorderMutex.withLock {
                if (recorder != null) {
                    return@launch
                }
                recorder = ThinkletRecorder.create(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    mic = ThinkletMics.Xfe(checkNotNull(context.getSystemService<AudioManager>())),
                    previewSurfaceProvider = surfaceProvider,
                    recordEventListener = ::handleRecordEvent
                )
            }
        }
    }

    fun toggleRecordState() {
        lifecycleOwner.lifecycleScope.launch {
            toggleRecordStateInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun toggleRecordStateInternal() = recorderMutex.withLock {
        val localRecorder = recorder ?: return@withLock
        if (_isRecording.value) {
            localRecorder.requestStop()
        } else {
            val file = File(
                context.getExternalFilesDir(null),
                "${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())}.mp4"
            )
            Toast.makeText(context, "StartRecord: ${file.absoluteFile}", Toast.LENGTH_LONG).show()
            localRecorder.startRecording(file)
        }
    }

    @WorkerThread
    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                playMediaActionSound(MediaActionSound.START_VIDEO_RECORDING)
                _isRecording.value = true
            }

            is VideoRecordEvent.Finalize -> {
                playMediaActionSound(MediaActionSound.STOP_VIDEO_RECORDING)
                _isRecording.value = false
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