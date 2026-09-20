@file:Suppress("MissingPermission", "DEPRECATION")
package com.ticnitsi.baofengprogram.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.ticnitsi.baofengprogram.core.RadioTransport
import com.ticnitsi.baofengprogram.core.TransportKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

data class ConnectionState(val connected: Boolean = false, val message: String = "Disconnected")

class BleRadioTransport(private val context: Context, override val deviceId: String) : RadioTransport {
    override val kind = TransportKind.BLUETOOTH
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val incoming = Channel<ByteArray>(512)
    private val writes = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var writeCompletion: CompletableDeferred<Int>? = null

    suspend fun connect() {
        check(context.hasBluetoothPermissions()) { "Bluetooth permission is required" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "Turn on Bluetooth" }
        mutableState.value = ConnectionState(message = "Connecting")
        try {
            gatt = adapter.getRemoteDevice(deviceId).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            withTimeout(20000) { ready.await() }
        } catch (e: Exception) { close(); throw e }
    }

    override suspend fun write(bytes: ByteArray) = writes.withLock {
        val connection = gatt ?: error("Radio is disconnected")
        val characteristic = writeCharacteristic ?: error("Radio is not ready")
        check(mutableState.value.connected) { "Radio is not connected" }
        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        // Conservative ATT chunks work on the Mini and UART adapters regardless of MTU negotiation.
        for (offset in bytes.indices step 20) {
            val part = bytes.copyOfRange(offset, minOf(offset + 20, bytes.size))
            val completion = CompletableDeferred<Int>()
            writeCompletion = completion
            try {
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    connection.writeCharacteristic(characteristic, part, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.writeType = writeType
                    characteristic.value = part
                    connection.writeCharacteristic(characteristic)
                }
                check(accepted) { "Bluetooth rejected a write" }
                val status = withTimeout(4000) { completion.await() }
                check(status == BluetoothGatt.GATT_SUCCESS) { "Bluetooth write failed ($status)" }
            } catch (e: Exception) {
                close()
                throw e
            } finally { writeCompletion = null }
            if (offset + part.size < bytes.size) delay(15)
        }
    }

    override suspend fun receive() = incoming.receive()
    override suspend fun clearInput() { while (incoming.tryReceive().isSuccess) { /* Start of a new session only. */ } }
    override fun close() {
        val connection = gatt
        gatt = null
        val cause = IOException("Radio disconnected")
        incoming.close(cause)
        if (!ready.isCompleted) ready.completeExceptionally(cause)
        writeCompletion?.completeExceptionally(cause)
        writeCharacteristic = null
        notifyCharacteristic = null
        mutableState.value = ConnectionState()
        runCatching { connection?.disconnect(); connection?.close() }
    }

    private fun fail(message: String) {
        if (!ready.isCompleted) ready.completeExceptionally(IOException(message))
        android.util.Log.w("RadioBluetooth", message)
        close()
        mutableState.value = ConnectionState(message = message)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth connection failed ($status)"); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mutableState.value = ConnectionState(message = "Discovering radio service")
                if (!connection.discoverServices()) fail("Cannot discover radio service")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) fail("Radio disconnected")
        }
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Service discovery failed ($status)"); return }
            val profile = profiles.firstOrNull { p ->
                connection.getService(p.service)?.let { it.getCharacteristic(p.write) != null && it.getCharacteristic(p.notify) != null } == true
            }
            if (profile == null) { fail("No supported Bluetooth programming service found"); return }
            val service = connection.getService(profile.service)
            writeCharacteristic = service.getCharacteristic(profile.write)
            val notify = service.getCharacteristic(profile.notify)
            notifyCharacteristic = notify
            val descriptor = notify.getDescriptor(cccd)
            if (descriptor == null || !connection.setCharacteristicNotification(notify, true)) {
                fail("Cannot subscribe to radio replies"); return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val accepted = if (Build.VERSION.SDK_INT >= 33) connection.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                else { descriptor.value = value; connection.writeDescriptor(descriptor) }
            if (!accepted) fail("Cannot enable radio replies")
        }
        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (connection !== gatt || descriptor.uuid != cccd) return
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("Radio reply subscription failed ($status)"); return }
            mutableState.value = ConnectionState(connected = true, message = "Connected")
            ready.complete(Unit)
        }
        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (connection === gatt && characteristic.uuid == writeCharacteristic?.uuid) writeCompletion?.complete(status)
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            accept(connection, characteristic, value)
        }
        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) accept(connection, characteristic, characteristic.value ?: byteArrayOf())
        }
        private fun accept(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
            if (connection !== gatt || characteristic.uuid != notifyCharacteristic?.uuid || bytes.isEmpty()) return
            if (!incoming.trySend(bytes.copyOf()).isSuccess) fail("Radio receive buffer overflow")
        }
    }
    private data class Profile(val service: UUID, val write: UUID, val notify: UUID)
    companion object {
        private fun short(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        private val cccd = short("2902")
        private val profiles = listOf(
            Profile(short("ffe0"), short("ffe1"), short("ffe1")),
            Profile(short("fff0"), short("fff2"), short("fff1")),
            Profile(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"), UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")),
        )
        val serviceIds = profiles.map { it.service }.toSet()
    }
}
