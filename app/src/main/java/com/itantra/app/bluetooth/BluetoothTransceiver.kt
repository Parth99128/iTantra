package com.itantra.app.bluetooth

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.itantra.app.core.BtConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID

class BluetoothTransceiver(
    context: Context,
    private val onTextReceived: (String) -> Unit,
    private val onStateChanged: (BtConnectionState) -> Unit,
    private val onDevicesChanged: (List<BluetoothDevice>) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object { val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); const val SERVICE_NAME = "iTantraWalkieLink" }
    private val appContext = context.applicationContext
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var receiverRegistered = false
    private val devices = linkedMapOf<String, BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) { devices[device.address] = device; publishDevices() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> onStateChanged(BtConnectionState.SCANNING)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { if (activeSocket == null) onStateChanged(BtConnectionState.DISCONNECTED); publishDevices() }
            }
        }
    }

    init { registerReceiver(); loadBondedDevices() }

    @SuppressLint("MissingPermission") private fun registerReceiver() {
        if (receiverRegistered || adapter == null) return
        val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        try {
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
            receiverRegistered = true
        } catch (e: Exception) { onError("Bluetooth receiver setup failed: ${e.message}") }
    }

    @SuppressLint("MissingPermission") private fun loadBondedDevices() {
        try { adapter?.bondedDevices?.forEach { devices[it.address] = it }; publishDevices() }
        catch (_: SecurityException) { onError("Bluetooth permission is required to read paired devices") }
    }

    @SuppressLint("MissingPermission") fun startDiscovery() {
        val bt = adapter ?: return onError("This phone does not support Bluetooth")
        try {
            if (!bt.isEnabled) return onError("Turn on Bluetooth first")
            devices.clear(); bt.bondedDevices?.forEach { devices[it.address] = it }; publishDevices()
            bt.cancelDiscovery(); onStateChanged(BtConnectionState.SCANNING)
            if (!bt.startDiscovery()) onError("Bluetooth discovery could not start")
        } catch (_: SecurityException) { onError("Bluetooth scan permission was denied") }
    }

    @SuppressLint("MissingPermission") fun startAsServer(activity: Activity) {
        val bt = adapter ?: return onError("This phone does not support Bluetooth")
        try {
            if (!bt.isEnabled) return onError("Turn on Bluetooth first")
            activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            onStateChanged(BtConnectionState.SCANNING)
            scope.launch {
                try {
                    serverSocket?.close(); serverSocket = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                    val socket = serverSocket?.accept() ?: return@launch
                    attachSocket(socket)
                } catch (e: IOException) {
                    if (e.message?.contains("socket closed", true) != true) onError("Bluetooth host failed: ${e.message}")
                    if (activeSocket == null) onStateChanged(BtConnectionState.DISCONNECTED)
                } catch (_: SecurityException) { onError("Bluetooth connect/advertise permission was denied") }
            }
        } catch (_: SecurityException) { onError("Bluetooth permission was denied") }
    }

    @SuppressLint("MissingPermission") fun connectToDevice(device: BluetoothDevice) {
        onStateChanged(BtConnectionState.CONNECTING)
        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                adapter?.cancelDiscovery(); closeSocketOnly()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID); socket.connect(); attachSocket(socket)
            } catch (e: IOException) {
                try { socket?.close() } catch (_: IOException) { }
                onError("Could not connect to ${safeName(device)}. Pair both phones in Android Bluetooth settings first.")
                onStateChanged(BtConnectionState.DISCONNECTED)
            } catch (_: SecurityException) { onError("Bluetooth connect permission was denied"); onStateChanged(BtConnectionState.DISCONNECTED) }
        }
    }

    private fun attachSocket(socket: BluetoothSocket) {
        activeSocket = socket
        synchronized(this) { writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8)) }
        onStateChanged(BtConnectionState.CONNECTED)
        listenLoop(socket)
    }

    fun sendText(text: String) {
        scope.launch {
            try {
                val out = synchronized(this@BluetoothTransceiver) { writer }
                    ?: return@launch onError("Bluetooth is not connected")
                synchronized(this@BluetoothTransceiver) { out.write(text.replace("\n", " ")); out.newLine(); out.flush() }
            } catch (e: IOException) { onError("Bluetooth send failed: ${e.message}"); disconnect() }
        }
    }

    private fun listenLoop(socket: BluetoothSocket) {
        try {
            socket.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> while (true) { val line = reader.readLine() ?: break; if (line.isNotBlank()) onTextReceived(line) } }
        } catch (_: IOException) { }
        finally { if (activeSocket === socket) { closeSocketOnly(); onStateChanged(BtConnectionState.DISCONNECTED) } }
    }

    @SuppressLint("MissingPermission") private fun publishDevices() {
        onDevicesChanged(devices.values.sortedWith(compareBy({ safeName(it) }, { it.address })))
    }
    @SuppressLint("MissingPermission") fun safeName(device: BluetoothDevice): String = try { device.name?.takeIf { it.isNotBlank() } ?: device.address } catch (_: SecurityException) { device.address }

    private fun closeSocketOnly() {
        try { writer?.close() } catch (_: Exception) { }
        try { activeSocket?.close() } catch (_: Exception) { }
        writer = null; activeSocket = null
    }
    fun disconnect() { try { serverSocket?.close(); adapter?.cancelDiscovery() } catch (_: Exception) { }; closeSocketOnly(); onStateChanged(BtConnectionState.DISCONNECTED) }
    fun close() { disconnect(); if (receiverRegistered) try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }; receiverRegistered = false; scope.cancel() }
}
