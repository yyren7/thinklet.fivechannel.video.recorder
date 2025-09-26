package com.example.fd.video.recorder.compose

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.example.fd.video.recorder.R
import com.example.fd.video.recorder.RecorderState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder
import android.net.wifi.WifiManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    recorderState: RecorderState,
    onNavigateToTest: () -> Unit
) {
    val permissionState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    )

    when {
        permissionState.allPermissionsGranted -> {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                onDispose {
                    recorderState.releaseRecorder()
                }
            }

            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                    if (recorderState.isPreviewEnabled) {
                        AndroidView(
                            factory = { context ->
                                PreviewView(context)
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { previewView ->
                                recorderState.setPreviewSurfaceProvider(previewView.surfaceProvider)
                            }
                        )
                    } else {
                        recorderState.setPreviewSurfaceProvider(null)
                    }

                    if (recorderState.isRebinding) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x80000000)), // Semi-transparent black
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                Text(text = if (recorderState.isRecording) "Recording" else "Stopped")
                
                // 响应式UseCase状态显示
                Text(
                    text = "Preview: ${if (recorderState.isPreviewEnabled) "ON" else "OFF"} | " +
                          "Streaming: ${if (recorderState.isStreamingEnabled) "ON" else "OFF"} | " +
                          "Recording: ${if (recorderState.isRecording) "ON" else "OFF"}",
                    modifier = Modifier.padding(8.dp),
                    color = Color.Gray
                )
                
                // Rebinding状态和计数器
                Text(
                    text = "Rebinding: ${if (recorderState.isRebinding) "IN PROGRESS" else "IDLE"} | " +
                          "Count: ${recorderState.rebindingCount}",
                    modifier = Modifier.padding(8.dp),
                    color = if (recorderState.isRebinding) Color.Red else Color.Green
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { recorderState.togglePreviewState() },
                        enabled = !recorderState.isRebinding
                    ) {
                        Text(if (recorderState.isPreviewEnabled) "Hide Preview" else "Show Preview")
                    }
                    Button(onClick = onNavigateToTest) {
                        Text("Test")
                    }
                }

                Box(modifier = Modifier.weight(0.3f)) {
                    WifiInfoView()
                }
                Box(modifier = Modifier.weight(0.3f)) {
                    BatteryInfoView()
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera and Audio permissions are required.")
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Request Permissions")
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun WifiInfoView() {
    val context = LocalContext.current
    var wifiInfoText by remember { mutableStateOf("Loading Wi-Fi info...") }

    LaunchedEffect(Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        while (true) {
            val connectionInfo = wifiManager.connectionInfo
            val ipAddress = connectionInfo.ipAddress
            val ip = if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                Integer.reverseBytes(ipAddress)
            } else {
                ipAddress
            }
            val ipString = BigInteger.valueOf(ip.toLong()).toByteArray().let {
                try {
                    InetAddress.getByAddress(it).hostAddress ?: "N/A"
                } catch (e: Exception) {
                    "N/A"
                }
            }

            wifiInfoText = """
                SSID: ${connectionInfo.ssid}
                IP Address: $ipString
                RSSI: ${connectionInfo.rssi} dBm
            """.trimIndent()
            delay(2000L)
        }
    }

    Text(
        text = wifiInfoText,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    )
}

@Composable
fun BatteryInfoView() {
    val context = LocalContext.current
    var batteryInfoText by remember { mutableStateOf("Loading battery info...") }
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()

                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                    val chargeCurrent = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000

                    batteryInfoText = """
                        Battery Level: ${batteryPct.toInt()}%
                        Status: ${if (isCharging) "Charging" else "Discharging"}
                        Current: $chargeCurrent mA
                    """.trimIndent()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Text(
        text = batteryInfoText,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.LightGray)
            .padding(16.dp)
    )
}