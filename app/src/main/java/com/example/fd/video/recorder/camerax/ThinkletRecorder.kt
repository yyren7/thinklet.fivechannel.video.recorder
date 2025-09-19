package com.example.fd.video.recorder.camerax

import ai.fd.thinklet.camerax.ThinkletMic
import android.Manifest
import android.content.Context
import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
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
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.example.fd.video.recorder.BuildConfig
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.guava.await
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * THINKLETのカメラを用いた録画機能を提供するクラス
 *
 * インスタンスの作成方法や引数に関しては[create]を参照してください。
 */
internal class ThinkletRecorder private constructor(
    private val context: Context,
    private val recorder: Recorder,
    private val recordEventListener: (VideoRecordEvent) -> Unit,
    private val rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
    private val recorderListenerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val fileSize: Long = BuildConfig.FILE_SIZE
) {
    private val recordingLock: Lock = ReentrantLock()

    @GuardedBy("recordingLock")
    private var recording: Recording? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(outputFile: File, outputAudioFile: File): Boolean = recordingLock.withLock {
        if (recording != null) {
            Logging.w("already recording")
            return false
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
            return false
        }
        rawAudioRecCaptureRepository.startRecording(outputAudioFile)
        return true
    }

    private fun handleVideoRecordEvent(event: VideoRecordEvent) {
        if (event is VideoRecordEvent.Finalize) {
            recordingLock.withLock {
                recording = null
            }
        }
        recordEventListener(event)
    }

    fun requestStop() {
        recordingLock.withLock {
            recording?.close()
            rawAudioRecCaptureRepository.stopRecording()
        }
    }

    companion object {

        const val MAX_FILE_SIZE = 4L * 1000 * 1000 * 1000

        /**
         * [ThinkletRecorder]のインスタンスを作成します
         *
         * メインスレッドで起動されているコルーチンから呼び出す必要があります。
         *
         * @param lifecycleOwner カメラのライフサイクルと紐付ける[LifecycleOwner]
         * @param mic 使用するTHINKLET独自のマイク機能
         * @param analyzer カメラAnalyzer
         * @param previewSurfaceProvider プレビューを表示する[PreviewView]から取得した[Preview.SurfaceProvider]
         * @param recordEventListener CameraX側からの[VideoRecordEvent]イベントを受け取るリスナー
         * @param rawAudioRecCaptureRepository 5ch音声の録音を行う[RawAudioRecCaptureRepository]
         * @param recorderExecutor [recordEventListener]の実行スレッドを指定する[ExecutorService]
         */
        @MainThread
        suspend fun create(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            mic: ThinkletMic?,
            analyzer: ImageAnalysis.Analyzer?,
            previewSurfaceProvider: Preview.SurfaceProvider? = null,
            recordEventListener: (VideoRecordEvent) -> Unit = {},
            rawAudioRecCaptureRepository: RawAudioRecCaptureRepository,
            recorderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        ): ThinkletRecorder? {
            CameraXPatch.apply()

            val recorder = Recorder.Builder()
                .setExecutor(recorderExecutor)
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .setThinkletMicIfPresent(mic)
                .build()
            val videoCaptureUseCase = VideoCapture.Builder(recorder).build()

            // Vision機能用のAnalyzer
            val analyzerUseCase = if (analyzer != null) {
                AnalyzerConfigure(analyzer).build()
            } else {
                null
            }
            val previewUseCase = if (previewSurfaceProvider != null) {
                Preview.Builder().build().apply {
                    surfaceProvider = previewSurfaceProvider
                }
            } else {
                null
            }

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(videoCaptureUseCase)
                .addUseCaseIfPresent(analyzerUseCase)
                .addUseCaseIfPresent(previewUseCase)
                .build()

            val cameraProvider = ProcessCameraProvider.getInstance(context).await()
            bind(cameraProvider, lifecycleOwner, useCaseGroup)
            return ThinkletRecorder(
                context,
                recorder,
                recordEventListener,
                rawAudioRecCaptureRepository
            )
        }

        @MainThread
        private fun bind(
            cameraProvider: ProcessCameraProvider,
            lifecycleOwner: LifecycleOwner,
            useCaseGroup: UseCaseGroup
        ) {
            runCatching {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCaseGroup
                )
            }.onFailure {
                Logging.e("Use case binding failed")
            }
        }

        private fun Recorder.Builder.setThinkletMicIfPresent(mic: ThinkletMic?): Recorder.Builder =
            if (mic == null) this else setThinkletMic(mic)

        private fun UseCaseGroup.Builder.addUseCaseIfPresent(
            useCase: UseCase?
        ): UseCaseGroup.Builder = if (useCase == null) this else addUseCase(useCase)
    }
}
