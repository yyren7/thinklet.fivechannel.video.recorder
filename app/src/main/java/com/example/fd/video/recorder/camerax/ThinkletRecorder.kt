package com.example.fd.video.recorder.camerax

import ai.fd.thinklet.camerax.ThinkletMic
import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.example.fd.video.recorder.BuildConfig
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provides video recording functionality using THINKLET camera
 *
 * For instance creation methods and parameters, refer to [create]
 */
internal class ThinkletRecorder private constructor(
    private val context: Context,
    private val recorder: Recorder,
    private val recordEventListener: (VideoRecordEvent) -> Unit,
    private val rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner,
    private val setRebinding: (Boolean) -> Unit,
    private val onPreviewStateChanged: (Boolean) -> Unit,
    private val recorderListenerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val fileSize: Long = BuildConfig.FILE_SIZE
) {
    private val recordingLock: Lock = ReentrantLock()
    private val cameraBindingLock = Mutex()

    @GuardedBy("recordingLock")
    private var recording: Recording? = null
    private var isPreparing: Boolean = false
    private val previewUseCase: Preview = Preview.Builder().build()
    private val videoCaptureUseCase: VideoCapture<Recorder>
    private val analyzerUseCase: ImageAnalysis?
    internal var camera: Camera? = null
    private var visionUseCaseEnabled: Boolean
    private var previewEnabled: Boolean
    private var visionUseCaseEnabledBeforeRecording: Boolean = false
    private var previewEnabledBeforeRecording: Boolean = false

    init {
        videoCaptureUseCase = VideoCapture.Builder(recorder).build()
        analyzerUseCase = Companion.analyzerUseCase
        visionUseCaseEnabled = false
        previewEnabled = false
        rebindUseCasesAsync()
    }

    fun prepareToRecord(enableVision: Boolean, enablePreview: Boolean): Pair<Boolean, Boolean> {
        if (isPreparing) {
            return previewEnabledBeforeRecording to visionUseCaseEnabledBeforeRecording
        }
        isPreparing = true
        // Force enable all UseCases during recording (outside of lock)
        previewEnabledBeforeRecording = previewEnabled
        visionUseCaseEnabledBeforeRecording = visionUseCaseEnabled
        visionUseCaseEnabled = enableVision
        // Only force enable preview when Surface is available to avoid crashes
        previewEnabled = enablePreview
        rebindUseCasesAsync()
        return previewEnabledBeforeRecording to visionUseCaseEnabledBeforeRecording
    }

    fun isRecording(): Boolean = recordingLock.withLock { recording != null }

    fun isVisionUseCaseEnabled(): Boolean {
        return visionUseCaseEnabled
    }

    fun getUseCaseStatus(): String {
        return "Preview:$previewEnabled|Vision:$visionUseCaseEnabled|Recording:${isRecording()}"
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecording(outputFile: File, outputAudioFile: File): Boolean {
        // Safely rebind all use cases synchronously (in suspend context)
        return cameraBindingLock.withLock {
            recordingLock.withLock {
                if (recording != null) {
                    Logging.w("already recording")
                    return@withLock false
                }

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
                recording = try {
                    pendingRecording.start(
                        recorderListenerExecutor,
                        Consumer<VideoRecordEvent>(::handleVideoRecordEvent)
                    )
                } catch (e: IllegalArgumentException) {
                    Logging.e("Failed to start recording. $e")
                    e.printStackTrace()
                    return@withLock false
                }
                rawAudioRecCaptureRepository.startRecording(outputAudioFile)
                return@withLock true
            }
        }
    }

    private fun handleVideoRecordEvent(event: VideoRecordEvent) {
        if (event is VideoRecordEvent.Finalize) {
            recordingLock.withLock {
                recording = null
            }
            // Ensure audio recording is stopped
            rawAudioRecCaptureRepository.stopRecording()
        }
        recordEventListener(event)
    }

    fun requestStop() {
        recordingLock.withLock {
            recording?.close()
            rawAudioRecCaptureRepository.stopRecording()
        }
    }

    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        previewUseCase.setSurfaceProvider(surfaceProvider)
        val enabled = surfaceProvider != null
        if (previewEnabled == enabled) {
            return
        }
        if (isRecording()) {
            Toast.makeText(context, "Settings are frozen during recording", Toast.LENGTH_SHORT).show()
            return
        }
        previewEnabled = enabled
        rebindUseCasesAsync()
    }

    fun enableVisionUseCase(enabled: Boolean) {
        if (visionUseCaseEnabled == enabled) {
            return
        }
        if (isRecording()) {
            Toast.makeText(context, "Settings are frozen during recording", Toast.LENGTH_SHORT).show()
            return
        }
        visionUseCaseEnabled = enabled
        rebindUseCasesAsync()
    }

    @MainThread
    internal fun rebindUseCasesAsync() {
        lifecycleOwner.lifecycleScope.launch {
            performCameraBinding()
        }
    }

    private suspend fun performCameraBinding() {
        cameraBindingLock.withLock {
            setRebinding(true)
            delay(1000L)
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
                    Logging.e("Use case binding failed")
                }.getOrNull()
            }
            setRebinding(false)
        }
    }

    private fun buildUseCaseGroup(): UseCaseGroup {
        return UseCaseGroup.Builder()
            .addUseCase(videoCaptureUseCase)
            .addUseCaseIfPresent(if (visionUseCaseEnabled) analyzerUseCase else null)
            .addUseCaseIfPresent(if (previewEnabled) previewUseCase else null)
            .build()
    }

    internal suspend fun restoreStateAndRebind() = withContext(Dispatchers.Main) {
        previewEnabled = previewEnabledBeforeRecording
        visionUseCaseEnabled = visionUseCaseEnabledBeforeRecording
        // Notify RecorderState to sync preview state
        onPreviewStateChanged(previewEnabled)
        performCameraBinding()
        isPreparing = false
    }


    companion object {

        const val MAX_FILE_SIZE = 4L * 1000 * 1000 * 1000
        private var analyzerUseCase: ImageAnalysis? = null

        /**
         * Creates an instance of [ThinkletRecorder]
         *
         * Must be called from a coroutine running on the main thread.
         *
         * @param lifecycleOwner [LifecycleOwner] to bind camera lifecycle
         * @param mic THINKLET proprietary microphone functionality to use
         * @param analyzer Camera analyzer
         * @param recordEventListener Listener to receive [VideoRecordEvent] events from CameraX
         * @param rawAudioRecCaptureRepository [RawAudioRecCaptureRepository] for 5-channel audio recording
         * @param setRebinding Callback to notify when camera rebinding status changes
         * @param onPreviewStateChanged Callback to notify when preview state changes
         * @param recorderExecutor [ExecutorService] to specify execution thread for [recordEventListener]
         */
        @MainThread
        suspend fun create(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            mic: ThinkletMic?,
            analyzer: ImageAnalysis.Analyzer?,
            recordEventListener: (VideoRecordEvent) -> Unit = {},
            rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
            setRebinding: (Boolean) -> Unit,
            onPreviewStateChanged: (Boolean) -> Unit,
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
                recorder,
                recordEventListener,
                rawAudioRecCaptureRepository,
                cameraProvider,
                lifecycleOwner,
                setRebinding,
                onPreviewStateChanged
            )
        }


        private fun Recorder.Builder.setThinkletMicIfPresent(mic: ThinkletMic?): Recorder.Builder =
            if (mic == null) this else setThinkletMic(mic)

        private fun UseCaseGroup.Builder.addUseCaseIfPresent(
            useCase: UseCase?
        ): UseCaseGroup.Builder = if (useCase == null) this else addUseCase(useCase)
    }
}
