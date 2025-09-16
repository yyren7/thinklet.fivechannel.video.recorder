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
            Text("Back to Main Screen")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("Volume Test")
        Spacer(modifier = Modifier.height(16.dp))
        
        // Notification Volume
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_NOTIFICATION) }) {
                Text("Increase Volume")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_NOTIFICATION) }) {
                Text("Decrease Volume")
            }
        }
        Button(onClick = { audioTestViewModel.playNotification(context) }) {
            Text("Play Notification")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Ringtone Volume
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_RING) }) {
                Text("Increase Volume")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_RING) }) {
                Text("Decrease Volume")
            }
        }
        Button(onClick = { audioTestViewModel.playRingtone(context) }) {
            Text("Play Ringtone")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { audioTestViewModel.stopRingtone() }) {
            Text("Stop Ringtone")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Media Volume
        Row {
            Button(onClick = { audioTestViewModel.increaseVolume(AudioManager.STREAM_MUSIC) }) {
                Text("Increase Volume")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { audioTestViewModel.decreaseVolume(AudioManager.STREAM_MUSIC) }) {
                Text("Decrease Volume")
            }
        }
        Row{
            Button(onClick = { audioTestViewModel.playTtsMessage() }) {
                Text("Play 'recording finished'")
            }
        }
    }
}
