package com.example.fd.video.recorder.compose

import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fd.video.recorder.AudioTestViewModel
import com.example.fd.video.recorder.AudioTestViewModelFactory
import com.example.fd.video.recorder.R

@Composable
fun AudioTestScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val factory = AudioTestViewModelFactory(context.applicationContext as android.app.Application, lifecycle)
    val audioTestViewModel: AudioTestViewModel = viewModel(factory = factory)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onNavigateBack) {
            Text("返回主屏幕")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("音量测试")
        Spacer(modifier = Modifier.height(16.dp))
        
        // 通知音量
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_NOTIFICATION) }) {
                Text("增加音量")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_NOTIFICATION) }) {
                Text("减少音量")
            }
        }
        Button(onClick = { audioTestViewModel.playNotification(context) }) {
            Text("播放通知")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 铃声音量
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_RING) }) {
                Text("增加音量")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_RING) }) {
                Text("减少音量")
            }
        }
        Button(onClick = { audioTestViewModel.playRingtone(context) }) {
            Text("播放铃声")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 媒体音量
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_MUSIC) }) {
                Text("增加音量")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_MUSIC) }) {
                Text("减少音量")
            }
        }
        Row{
            Button(onClick = { audioTestViewModel.playTtsMessage() }) {
                Text("播放 'recording finished'")
            }
        }
    }
}
