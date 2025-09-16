
package com.example.fd.video.recorder.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter

class WifiReconnectManager(private val context: Context, private val onStatusUpdate: (String) -> Unit) {
    companion object {
        private const val TAG = "WifiReconnectManager"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectionJob: Job? = null
    private var targetSsid: String? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    Log.d(TAG, "Wi-Fi scan successful.")
                    onStatusUpdate("scan successful, searching for target network...")
                    connectToTargetWifi()
                } else {
                    Log.w(TAG, "Wi-Fi scan failed.")
                    onStatusUpdate("scan failed, will retry...")
                    reconnectionJob = coroutineScope.launch {
                        delay(5000)
                        scanAndReconnect()
                    }
                }
                context.unregisterReceiver(this)
            }
        }
    }

    fun startMonitoring(ssid: String) {
        if (networkCallback != null) {
            Log.w(TAG, "Monitoring is already active.")
            return
        }
        this.targetSsid = ssid
        Log.d(TAG, "Starting network monitoring for SSID: $targetSsid")
        onStatusUpdate("starting network monitoring...")

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "Network connection lost!")
                onStatusUpdate("network connection lost, preparing to reconnect...")
                
                // check if there is any active wifi connection
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                val isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false

                if (!isWifiConnected) {
                    reconnectionJob?.cancel() // cancel the previous reconnect task
                    reconnectionJob = coroutineScope.launch {
                        delay(1000) // wait for the network state to stabilize
                        scanAndReconnect()
                    }
                } else {
                    Log.d(TAG, "Another Wi-Fi network is active. No need to reconnect.")
                    onStatusUpdate("connected to another wifi network, no need to reconnect.")
                }
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun scanAndReconnect() {
        if (targetSsid == null) return
        Log.d(TAG, "Scanning for Wi-Fi networks...")
        onStatusUpdate("scanning for wifi networks...")

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)

        if (!wifiManager.startScan()) {
             context.unregisterReceiver(wifiScanReceiver)
             Log.e(TAG, "startScan failed. Retrying in 5s.")
             onStatusUpdate("failed to start scan, will retry in 5s...")
             coroutineScope.launch {
                 delay(5000)
                 scanAndReconnect()
             }
        }
    }

    private fun connectToTargetWifi() {
        val target = targetSsid ?: return
        Log.d(TAG, "Searching for target SSID: $target")

        val scanResults = wifiManager.scanResults
        val targetNetwork = scanResults.find { it.SSID == target }

        if (targetNetwork != null) {
            Log.d(TAG, "Target network '$target' found.")
            onStatusUpdate("target network found, connecting...")
            connectToWifi(target)
        } else {
            Log.w(TAG, "Target network '$target' not in scan results. Retrying scan in 10s.")
            onStatusUpdate("target network not found, will retry scan in 10s...")
            reconnectionJob = coroutineScope.launch {
                delay(10000)
                scanAndReconnect()
            }
        }
    }

    private fun connectToWifi(ssid: String) {
        val configuredNetworks = wifiManager.configuredNetworks
        val existingConfig = configuredNetworks?.find { it.SSID == "\"$ssid\"" }

        if (existingConfig != null) {
            Log.d(TAG, "Wi-Fi configuration for '$ssid' already exists. Enabling it.")
            wifiManager.enableNetwork(existingConfig.networkId, true)
        } else {
            Log.w(TAG, "No configuration found for '$ssid'. This implementation does not support adding new networks with passwords.")
             onStatusUpdate("no configuration found for '$ssid', please connect manually first.")
        }
    }

    fun stopMonitoring() {
        Log.d(TAG, "Stopping network monitoring")
        onStatusUpdate("stopping network monitoring...")
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Network callback was not registered or already unregistered.")
            }
        }
        networkCallback = null
        reconnectionJob?.cancel()
    }
}
