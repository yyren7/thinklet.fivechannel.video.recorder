package com.example.fd.video.recorder.compose

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.fd.video.recorder.RecorderState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.compose.runtime.rememberUpdatedState
import android.annotation.SuppressLint
import com.example.fd.video.recorder.manager.WifiReconnectManager

/**
 * カメラのPreviewを表示．録画中は右上に緑色の丸図形を描画する Compose．
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    recorderState: RecorderState,
    modifier: Modifier = Modifier,
    onNavigateToAudioTest: () -> Unit
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    )
    var isCameraEnabled by remember { mutableStateOf(true) }


    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (permissionsState.allPermissionsGranted) {
                Box(modifier = Modifier.weight(0.4f)) {
                    if (isCameraEnabled) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            recorderState = recorderState
                        )
                    }
                    if (recorderState.isRecording) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(Color.Green, shape = CircleShape)
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        )
                    }
                }
                Button(onClick = { isCameraEnabled = !isCameraEnabled }) {
                    Text(if (isCameraEnabled) "关闭摄像头" else "开启摄像头")
                }
                Button(onClick = onNavigateToAudioTest) {
                    Text("Go to Audio Test")
                }
                Box(modifier = Modifier.weight(0.3f)) {
                    WifiInfoView()
                }
                Box(modifier = Modifier.weight(0.3f)) {
                    BatteryInfoView()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun WifiInfoView() {
    val context = LocalContext.current
    var wifiInfoText by remember { mutableStateOf("Loading Wi-Fi info...") }
    var reconnectStatusText by remember { mutableStateOf("正在初始化监控...") }

    // define the target ssid
    val targetSsid = "ncjfrnw"

    val wifiReconnectManager = remember {
        WifiReconnectManager(context) { status ->
            reconnectStatusText = status
        }
    }

    DisposableEffect(Unit) {
        wifiReconnectManager.startMonitoring(targetSsid)
        onDispose {
            wifiReconnectManager.stopMonitoring()
        }
    }

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
                    InetAddress.getByAddress(it).hostAddress
                } catch (e: Exception) {
                    "N/A"
                }
            }

            wifiInfoText = """
                SSID: ${connectionInfo.ssid}
                BSSID: ${connectionInfo.bssid}
                IP Address: $ipString
                Link Speed: ${connectionInfo.linkSpeed} Mbps
                RSSI: ${connectionInfo.rssi} dBm
            """.trimIndent()
            delay(1000L) // 每隔1秒刷新一次
        }
    }

    Text(
        text = wifiInfoText,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    )
    Text(
        text = "reconnect status: $reconnectStatusText",
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Cyan)
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
                        Charging Status: ${if (isCharging) "Charging" else "Discharging"}
                        Charge Current: $chargeCurrent mA
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