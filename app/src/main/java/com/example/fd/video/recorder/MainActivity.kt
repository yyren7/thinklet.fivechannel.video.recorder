package com.example.fd.video.recorder

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.fd.video.recorder.compose.MainScreen
import com.example.fd.video.recorder.ui.theme.MultiMicVideoRecorderTheme
import android.util.Log

/**
 * このアプリは，THINKLET向けのCameraXを用いた録画サンプルアプリです．
 * 第２ボタン（Cameraキー）の押下により，録画と録画の停止を行います．
 * 書き出し先ファイルは， `/sdcard/Android/data/com.example.fd.video.recorder/files/` 以下にmp4形式で保存されます．
 */
class MainActivity : ComponentActivity() {
    private val recorderState: RecorderState by lazy(LazyThreadSafetyMode.NONE) {
        RecorderState(this, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(FLAG_KEEP_SCREEN_ON)
        setContent {
            MultiMicVideoRecorderTheme {
                MainScreen(
                    modifier = Modifier.fillMaxSize(),
                    recorderState = recorderState,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorderState.release()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ThinkletKeyEvent", "Key pressed - KeyCode: $keyCode, Event: ${event?.toString()}")
        return when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                recorderState.toggleRecordState()
                return true
            }

            else -> super.onKeyUp(keyCode, event)
        }
    }
}
