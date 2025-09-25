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
import com.example.fd.video.recorder.TestViewModel
import com.example.fd.video.recorder.TestViewModelFactory
import com.example.fd.video.recorder.R

@Composable
fun TestScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val factory = TestViewModelFactory(context.applicationContext as android.app.Application, lifecycle)
    val testViewModel: TestViewModel = viewModel(factory = factory)

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

        Button(onClick = { testViewModel.toggleLed() }) {
            Text("Toggle LED")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { testViewModel.playTTSMessage() }) {
            Text("Play 'recording finished'")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { testViewModel.vibrate() }) {
            Text("Vibrate for 0.5s")
        }
    }
}
