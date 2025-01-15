package com.example.fd.camerax.recorder.compose

import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fd.camerax.recorder.BuildConfig
import com.example.fd.camerax.recorder.RecorderState

@Composable
fun CameraPreview(
    recorderState: RecorderState,
    modifier: Modifier = Modifier,
    previewSize: Size = Size(1920, 1080),
    enablePreview: Boolean = BuildConfig.ENABLE_PREVIEW
) {
    val width = if (recorderState.isLandscapeCamera) previewSize.width else previewSize.height
    val height = if (recorderState.isLandscapeCamera) previewSize.height else previewSize.width

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = if (enablePreview) {
                PreviewView(context).apply {
                    this.scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.layoutParams = ViewGroup.LayoutParams(width, height)
                }
            } else {
                View(context)
            }
            val surfaceProvider = if (view is PreviewView) view.surfaceProvider else null
            recorderState.registerSurfaceProvider(surfaceProvider)
            return@AndroidView view
        }
    )
}
