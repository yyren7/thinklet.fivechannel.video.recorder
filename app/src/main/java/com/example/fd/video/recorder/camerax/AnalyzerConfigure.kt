package com.example.fd.video.recorder.camerax

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AnalyzerConfigure(
    private val analyzer: ImageAnalysis.Analyzer,
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
) {
    fun build(): ImageAnalysis {
        return ImageAnalysis.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
        ).build().also {
            it.setAnalyzer(executorService, analyzer)
        }
    }
}
