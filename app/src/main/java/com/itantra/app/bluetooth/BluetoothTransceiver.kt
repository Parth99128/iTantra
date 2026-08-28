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
import java.io.IOException
import java.util.UUID

/** Classic Bluetooth RFCOMM transport. Only UTF-8 text crosses the radio link. */
class BluetoothTransceiver(
    context: Context,
    private val onTextReceived: (String) -> Unit,
    private val onStateChanged: (BtConnectionState) -> Unit,
    private val onDevicesChanged: (List<BluetoothDevice>) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "iTantraWalkieLink"
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var receiverRegistered = false
    private val devices = linkedMapOf<String, BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        devices[device.address] = device
                        publishDevices()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> onStateChanged(BtConnectionState.SCANNING)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (activeSocket == null) onStateChanged(BtConnectionState.DISCONNECTED)
                    publishDevices()
                }
            }
        }
    }

    init {
        registerReceiver()
        loadBondedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiverRegistered || adapter == null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            onError("Bluetooth receiver setup failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        try {
            adapter?.bondedDevices?.forEach { devices[it.address] = it }
            publishDevices()
        } catch (e: SecurityException) {
            onError("Bluetooth permission is required to read paired devices")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val bt = adapter ?: run {
            onError("This phone does not support Bluetooth")
            return
        }
        try {
            if (!bt.isEnabled) {
                onError("Turn on Bluetooth first")
                return
            }
            devices.clear()
            bt.bondedDevices?.forEach { devices[it.address] = it }
            publishDevices()
            bt.cancelDiscovery()
            onStateChanged(BtConnectionState.SCANNING)
            if (!bt.startDiscovery()) onError("Bluetooth discovery could not start")
        } catch (e: SecurityException) {
            onError("Bluetooth scan permission was denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAsServer(activity: Activity) {
        val bt = adapter ?: run {
            onError("This phone does not support Bluetooth")
            return
        }
        try {
            if (!bt.isEnabled) {
                onError("Turn on Bluetooth first")
                return
            }
            // A listening RFCOMM socket does not itself make the phone discoverable.
            // Request temporary discoverability so the other phone can find it.
            val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            activity.startActivity(discoverable)
            onStateChanged(BtConnectionState.SCANNING)
            scope.launch {
                try {
                    serverSocket?.close()
                    serverSocket = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                    val socket = serverSocket?.accept()
                    if (socket == null) {
                        onStateChanged(BtConnectionState.DISCONNECTED)
                        return@launch
                    }
                    activeSocket = socket
                    onStateChanged(BtConnectionState.CONNECTED)
                    listenLoop(socket)
                } catch (e: IOException) {
                    activeSocket = null
                    if (e.message?.contains("socket closed", ignoreCase = true) != true) {
                        onError("Bluetooth host failed: ${e.message}")
                    }
                    onStateChanged(BtConnectionState.DISCONNECTED)
                } catch (e: SecurityException) {
                    onError("Bluetooth connect/advertise permission was denied")
                    onStateChanged(BtConnectionState.DISCONNECTED)
                }
            }
        } catch (e: SecurityException) {
            onError("Bluetooth permission was denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        onStateChanged(BtConnectionState.CONNECTING)
        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                adapter?.cancelDiscovery()
                activeSocket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                activeSocket = socket
                onStateChanged(BtConnectionState.CONNECTED)
                listenLoop(socket)
            } catch (e: IOException) {
                try { socket?.close() } catch (_: IOException) { }
                activeSocket = null
                onError("Could not connect to ${safeName(device)}. Pair the phones in Android Bluetooth settings first, then retry.")
                onStateChanged(BtConnectionState.DISCONNECTED)
            } catch (e: SecurityException) {
                onError("Bluetooth connect permission was denied")
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    fun sendText(text: String) {
        scope.launch {
            try {
                val socket = activeSocket ?: run {
                    onError("Bluetooth is not connected")
                    return@launch
                }
                socket.outputStream.buffered().use { out ->
                    out.write((text.replace("\n", " ") + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: IOException) {
                onError("Bluetooth send failed: ${e.message}")
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    private fun listenLoop(socket: BluetoothSocket) {
        try {
            socket.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onTextReceived(line)
                }
            }
        } catch (e: IOException) {
            if (activeSocket === socket) {
                activeSocket = null
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishDevices() {
        val snapshot = devices.values.sortedWith(compareBy({ safeName(it) }, { it.address }))
        onDevicesChanged(snapshot)
    }

    @SuppressLint("MissingPermission")
    fun safeName(device: BluetoothDevice): String =
        try { device.name?.takeIf { it.isNotBlank() } ?: device.address } catch (_: SecurityException) { device.address }

    fun disconnect() {
        try {
            activeSocket?.close()
            serverSocket?.close()
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        } finally {
            activeSocket = null
            serverSocket = null
            onStateChanged(BtConnectionState.DISCONNECTED)
        }
    }

    fun close() {
        disconnect()
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        scope.cancel()
    }
}
