@file:Suppress("MissingPermission")
package com.ticnitsi.baofengprogram.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NearbyRadio(val address: String, val name: String, val rssi: Int, val likelyRadio: Boolean)
data class ScanState(val scanning: Boolean = false, val radios: List<NearbyRadio> = emptyList(), val error: String? = null)

fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun Context.hasBluetoothPermissions() = bluetoothPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

class BleScanner(private val context: Context, private val scope: CoroutineScope) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutableState = MutableStateFlow(ScanState())
    val state = mutableState.asStateFlow()
    private var scan: ScanCallback? = null
    private var timeout: Job? = null

    fun start() {
        stop()
        if (!context.hasBluetoothPermissions()) {
            mutableState.update { it.copy(error = "Bluetooth and location permissions are required to discover radios") }
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            mutableState.update { it.copy(error = "Turn on Bluetooth to scan") }
            return
        }
        mutableState.value = ScanState(scanning = true)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
            override fun onScanFailed(errorCode: Int) {
                stop()
                mutableState.update { it.copy(error = "Bluetooth scan failed ($errorCode). Try again in a moment.") }
            }
            private fun record(result: ScanResult) {
                if (scan !== this) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unnamed device"
                val likely = name.contains("walkie", true) || name.contains("baofeng", true) ||
                    result.scanRecord?.serviceUuids.orEmpty().any { uuid -> BleRadioTransport.serviceIds.contains(uuid.uuid) }
                val radio = NearbyRadio(result.device.address, name, result.rssi, likely)
                mutableState.update { old ->
                    old.copy(radios = (old.radios.filterNot { it.address == radio.address } + radio)
                        .sortedWith(compareByDescending<NearbyRadio> { it.likelyRadio }.thenByDescending { it.rssi }))
                }
            }
        }
        scan = callback
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            timeout = scope.launch { delay(15000); stop() }
        } catch (e: Exception) {
            stop()
            mutableState.update { it.copy(error = e.message ?: "Unable to scan") }
        }
    }
    fun stop() {
        timeout?.cancel()
        timeout = null
        val current = scan
        scan = null
        if (current != null) runCatching { adapter?.bluetoothLeScanner?.stopScan(current) }
        mutableState.update { it.copy(scanning = false) }
    }
}
