package com.example.fd.video.recorder.camerax

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import com.example.fd.video.recorder.util.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UseCaseManager(
    private val recorder: Recorder,
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner,
    analyzer: ImageAnalysis.Analyzer?
) {
    val previewUseCase: Preview = Preview.Builder().build()
    val videoCaptureUseCase: VideoCapture<Recorder> = VideoCapture.Builder(recorder).build()
    private val analyzerUseCase: ImageAnalysis? = if (analyzer != null) {
        AnalyzerConfigure(analyzer).build()
    } else {
        null
    }

    private fun buildUseCaseGroup(previewEnabled: Boolean, streamingEnabled: Boolean): UseCaseGroup {
        return UseCaseGroup.Builder()
            .addUseCase(videoCaptureUseCase)
            .addUseCaseIfPresent(if (streamingEnabled) analyzerUseCase else null)
            .addUseCaseIfPresent(if (previewEnabled) previewUseCase else null)
            .build()
    }

    suspend fun bindUseCases(previewEnabled: Boolean, streamingEnabled: Boolean): Camera? {
        return withContext(Dispatchers.Main) {
            val useCaseGroup = buildUseCaseGroup(previewEnabled, streamingEnabled)
            runCatching {
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
    }

    private fun UseCaseGroup.Builder.addUseCaseIfPresent(useCase: UseCase?): UseCaseGroup.Builder =
        if (useCase == null) this else addUseCase(useCase)
}
