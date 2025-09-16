package com.example.fd.video.recorder.compose

import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fd.video.recorder.BuildConfig
import com.example.fd.video.recorder.RecorderState

@Composable
fun CameraPreview(
    recorderState: RecorderState,
    modifier: Modifier = Modifier,
    previewSize: Size = Size(1920, 1080),
    enablePreview: Boolean = BuildConfig.ENABLE_PREVIEW
) {
    val width = if (recorderState.isLandscapeCamera) previewSize.width else previewSize.height
    val height = if (recorderState.isLandscapeCamera) previewSize.height else previewSize.width

    DisposableEffect(recorderState) {
        onDispose {
            recorderState.releaseRecorder()
        }
    }

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
            return@AndroidView view
        },
        update = { view ->
            val surfaceProvider = if (view is PreviewView) view.surfaceProvider else null
            recorderState.registerSurfaceProvider(surfaceProvider)
        }
    )
}
