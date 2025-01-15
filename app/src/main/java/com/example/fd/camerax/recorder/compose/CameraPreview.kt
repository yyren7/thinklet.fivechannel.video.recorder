package com.example.fd.camerax.recorder.compose

import android.util.Size
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fd.camerax.recorder.RecorderState

@Composable
fun CameraPreview(
    recorderState: RecorderState,
    modifier: Modifier = Modifier,
    previewSize: Size = Size(1920, 1080)
) {
    val width = if (recorderState.isLandscapeCamera) previewSize.width else previewSize.height
    val height = if (recorderState.isLandscapeCamera) previewSize.height else previewSize.width

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context).apply {
                this.scaleType = PreviewView.ScaleType.FIT_CENTER
                this.layoutParams = ViewGroup.LayoutParams(width, height)
            }
            recorderState.registerSurfaceProvider(previewView.surfaceProvider)
            return@AndroidView previewView
        }
    )
}
