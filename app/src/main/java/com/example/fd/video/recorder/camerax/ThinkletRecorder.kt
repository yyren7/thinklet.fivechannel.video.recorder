package com.example.fd.video.recorder.camerax

import ai.fd.thinklet.camerax.ThinkletMic
import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.example.fd.video.recorder.BuildConfig
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 状态变化监听器
 */
interface UseCaseStatusListener {
    fun onPreviewStateChanged(enabled: Boolean)
    fun onStreamingStateChanged(enabled: Boolean)
    fun onRecordingStateChanged(isRecording: Boolean)
}

/**
 * Provides video recording functionality using THINKLET camera
 *
 * For instance creation methods and parameters, refer to [create]
 */
internal class ThinkletRecorder private constructor(
    private val context: Context,
    private val micType: String,
    private val recorder: Recorder,
    private val recordEventListener: (VideoRecordEvent) -> Unit,
    private val rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner,
    private val setRebinding: (Boolean) -> Unit,
    private val onPreviewStateChanged: (Boolean) -> Unit,
    private val useCaseStatusListener: UseCaseStatusListener? = null,
    private val recorderListenerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val fileSize: Long = BuildConfig.FILE_SIZE
) {
    // 统一的状态锁
    private val cameraStateLock = Mutex()

    @GuardedBy("cameraStateLock")
    private var recording: Recording? = null
    @GuardedBy("cameraStateLock")
    private var previewEnabled: Boolean = false
    @GuardedBy("cameraStateLock")
    private var streamingEnabled: Boolean = false
    @GuardedBy("cameraStateLock")
    private var isInTransition: Boolean = false
    @GuardedBy("cameraStateLock")
    private var previewEnabledBeforeRecording: Boolean = false
    @GuardedBy("cameraStateLock")
    private var streamingEnabledBeforeRecording: Boolean = false

    private val previewUseCase: Preview = Preview.Builder().build()
    private val videoCaptureUseCase: VideoCapture<Recorder>
    private val analyzerUseCase: ImageAnalysis?
    internal var camera: Camera? = null

    init {
        videoCaptureUseCase = VideoCapture.Builder(recorder).build()
        analyzerUseCase = Companion.analyzerUseCase
    }

    suspend fun isRecording(): Boolean = cameraStateLock.withLock isRecordingLock@{ recording != null }
    
    private fun isRecordingUnsafe(): Boolean = recording != null

    suspend fun isStreamingEnabled(): Boolean = cameraStateLock.withLock isStreamingEnabledLock@{ streamingEnabled }

    fun getUseCaseStatus(): String {
        return "Preview:$previewEnabled|Streaming:$streamingEnabled|Recording:${runCatching { recording != null }.getOrElse { false }}"
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecording(outputFile: File, outputAudioFile: File): Boolean {
        return cameraStateLock.withLock startRecordingLock@{
            if (isRecordingUnsafe() || isInTransition) {
                return@startRecordingLock false
            }
            
            isInTransition = true
            try {
                // 保存当前状态用于恢复
                previewEnabledBeforeRecording = previewEnabled
                streamingEnabledBeforeRecording = streamingEnabled
                
                // 录像期间强制启用必要的功能
                streamingEnabled = true
                previewEnabled = true
                
                // 通知状态变化
                useCaseStatusListener?.onPreviewStateChanged(previewEnabled)
                useCaseStatusListener?.onStreamingStateChanged(streamingEnabled)
                
                performCameraBindingUnsafe()
                
                Logging.d("write to ${outputFile.absolutePath}")
                val pendingRecording = recorder
                    .prepareRecording(
                        context,
                        FileOutputOptions
                            .Builder(outputFile)
                            .setFileSizeLimit(minOf(fileSize, MAX_FILE_SIZE))
                            .build()
                    )
                    .withAudioEnabled()
                    
                recording = pendingRecording.start(
                    recorderListenerExecutor,
                    Consumer<VideoRecordEvent>(::handleVideoRecordEvent)
                )
                
                // 通知录制状态变化
                useCaseStatusListener?.onRecordingStateChanged(true)
                
                if (micType == "raw") {
                    rawAudioRecCaptureRepository.startRecording(outputAudioFile)
                }
                return@startRecordingLock true
            } catch (e: Exception) {
                Logging.e("Failed to start recording: $e")
                recording = null
                return@startRecordingLock false
            } finally {
                isInTransition = false
            }
        }
    }

    private fun handleVideoRecordEvent(event: VideoRecordEvent) {
        if (event is VideoRecordEvent.Finalize) {
            // 使用runBlocking来同步清理recording状态，防止状态不一致
            runBlocking {
                cameraStateLock.withLock finalizeLock@{
                    recording = null
                }
            }
            if (micType == "raw") {
                rawAudioRecCaptureRepository.stopRecording()
            }
            // 通知录制状态变化
            useCaseStatusListener?.onRecordingStateChanged(false)
        }
        recordEventListener(event)
    }

    suspend fun requestStop() {
        cameraStateLock.withLock requestStopLock@{
            recording?.close()
            recording = null
            if (micType == "raw") {
                rawAudioRecCaptureRepository.stopRecording()
            }
            // 通知录制状态变化
            useCaseStatusListener?.onRecordingStateChanged(false)
        }
    }

    suspend fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?): Boolean {
        return cameraStateLock.withLock setPreviewLock@{
            previewUseCase.setSurfaceProvider(surfaceProvider)
            
            val enabled = surfaceProvider != null
            if (previewEnabled == enabled) {
                return@setPreviewLock true
            }
            
            if (isRecordingUnsafe()) {
                showToast("Settings are frozen during recording")
                return@setPreviewLock false
            }
            
            previewEnabled = enabled
            // 通知状态变化
            useCaseStatusListener?.onPreviewStateChanged(previewEnabled)
            performCameraBindingUnsafe()
            return@setPreviewLock true
        }
    }

    suspend fun setStreamingEnabled(enabled: Boolean): Boolean {
        return cameraStateLock.withLock setStreamingLock@{
            if (streamingEnabled == enabled) {
                return@setStreamingLock true
            }
            
            if (isRecordingUnsafe()) {
                showToast("Settings are frozen during recording")
                return@setStreamingLock false
            }
            
            streamingEnabled = enabled
            // 通知状态变化
            useCaseStatusListener?.onStreamingStateChanged(streamingEnabled)
            performCameraBindingUnsafe()
            return@setStreamingLock true
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun performCameraBindingUnsafe() {
        setRebinding(true)
        delay(500L)
        withContext(Dispatchers.Main) {
            val useCaseGroup = buildUseCaseGroup()
            camera = runCatching {
                analyzerUseCase?.clearAnalyzer()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCaseGroup
                )
            }.onFailure {
                Logging.e("Use case binding failed: $it")
            }.getOrNull()
        }
        setRebinding(false)
    }

    private fun buildUseCaseGroup(): UseCaseGroup {
        return UseCaseGroup.Builder()
            .addUseCase(videoCaptureUseCase)
            .addUseCaseIfPresent(if (streamingEnabled) analyzerUseCase else null)
            .addUseCaseIfPresent(if (previewEnabled) previewUseCase else null)
            .build()
    }

    internal suspend fun restoreStateAndRebind() {
        cameraStateLock.withLock restoreStateLock@{
            previewEnabled = previewEnabledBeforeRecording
            streamingEnabled = streamingEnabledBeforeRecording
            // 通知状态变化
            useCaseStatusListener?.onPreviewStateChanged(previewEnabled)
            useCaseStatusListener?.onStreamingStateChanged(streamingEnabled)
            performCameraBindingUnsafe()
        }
        // Notify RecorderState to sync preview state (outside lock)
        onPreviewStateChanged(previewEnabled)
    }


    companion object {

        const val MAX_FILE_SIZE = 4L * 1000 * 1000 * 1000
        private var analyzerUseCase: ImageAnalysis? = null

        /**
         * Creates an instance of [ThinkletRecorder]
         *
         * @param lifecycleOwner [LifecycleOwner] to bind camera lifecycle
         * @param mic THINKLET proprietary microphone functionality to use
         * @param analyzer Camera analyzer for streaming functionality
         * @param recordEventListener Listener to receive [VideoRecordEvent] events from CameraX
         * @param rawAudioRecCaptureRepository [RawAudioRecCaptureRepository] for 5-channel audio recording
         * @param setRebinding Callback to notify when camera rebinding status changes
         * @param onPreviewStateChanged Callback to notify when preview state changes
         * @param recorderExecutor [ExecutorService] to specify execution thread for [recordEventListener]
         */
        suspend fun create(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            mic: ThinkletMic?,
            micType: String,
            analyzer: ImageAnalysis.Analyzer?,
            recordEventListener: (VideoRecordEvent) -> Unit = {},
            rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
            setRebinding: (Boolean) -> Unit,
            onPreviewStateChanged: (Boolean) -> Unit,
            useCaseStatusListener: UseCaseStatusListener? = null,
            recorderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        ): ThinkletRecorder? {
            CameraXPatch.apply()

            val recorder = Recorder.Builder()
                .setExecutor(recorderExecutor)
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .setThinkletMicIfPresent(mic)
                .build()

            // Analyzer for Vision functionality
            analyzerUseCase = if (analyzer != null) {
                AnalyzerConfigure(analyzer).build()
            } else {
                null
            }

            val cameraProvider = ProcessCameraProvider.getInstance(context).await()
            return ThinkletRecorder(
                context,
                micType,
                recorder,
                recordEventListener,
                rawAudioRecCaptureRepository,
                cameraProvider,
                lifecycleOwner,
                setRebinding,
                onPreviewStateChanged,
                useCaseStatusListener
            )
        }


        private fun Recorder.Builder.setThinkletMicIfPresent(mic: ThinkletMic?): Recorder.Builder =
            if (mic == null) this else setThinkletMic(mic)

        private fun UseCaseGroup.Builder.addUseCaseIfPresent(
            useCase: UseCase?
        ): UseCaseGroup.Builder = if (useCase == null) this else addUseCase(useCase)
    }
}
