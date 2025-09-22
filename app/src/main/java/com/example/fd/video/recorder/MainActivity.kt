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
import com.example.fd.video.recorder.compose.TestScreen
import com.example.fd.video.recorder.ui.theme.MultiMicVideoRecorderTheme
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import ai.fd.thinklet.sdk.maintenance.launcher.Extension

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

        // THINKLETの自動起動設定
        try {
            val ext = Extension()
            val (pkg, cls) = ext.configure()
            val myPkg = this.packageName
            val myCls = this::class.java.name
            if (pkg != myPkg || cls != myCls) {
                ext.configure(myPkg, myCls)
            }
            if (!ext.isAutoLaunchMode()) {
                ext.enableAutoLaunchMode()
            }
        } catch (e: Exception) {
            Log.e("ThinkletExtension", "Failed to configure auto-launch", e)
        }

        enableEdgeToEdge()
        window.addFlags(FLAG_KEEP_SCREEN_ON)
        setContent {
            var showTestScreen by remember { mutableStateOf(false) }

            MultiMicVideoRecorderTheme {
                if (showTestScreen) {
                    TestScreen(onNavigateBack = { showTestScreen = false })
                } else {
                    MainScreen(
                        modifier = Modifier.fillMaxSize(),
                        recorderState = recorderState,
                        onNavigateToTest = { showTestScreen = true }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorderState.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 音量キーの押下イベントを消費して、システムの音量調整UIが表示されないようにする
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ThinkletKeyEvent", "Key pressed - KeyCode: $keyCode, Event: ${event?.toString()}")
        return when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                recorderState.prepareToRecord(true, true)
                recorderState.toggleRecordState()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                recorderState.speakBatteryAndNetworkStatus()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // このキーには機能を割り当てず、イベントだけを消費する
                return true
            }

            else -> super.onKeyUp(keyCode, event)
        }
    }
}
