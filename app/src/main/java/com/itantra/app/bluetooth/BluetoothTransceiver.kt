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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
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
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val CONNECT_RETRIES = 2
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    private var receiverRegistered = false
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var hostRunning = false

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
                    if (activeSocket == null && !hostRunning) onStateChanged(BtConnectionState.DISCONNECTED)
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
        } catch (_: SecurityException) {
            onError("Bluetooth permission is required to read paired devices")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val bt = adapter ?: return onError("This phone does not support Bluetooth")
        try {
            if (!bt.isEnabled) return onError("Turn on Bluetooth first")
            bt.cancelDiscovery()
            devices.clear()
            bt.bondedDevices?.forEach { devices[it.address] = it }
            publishDevices()
            onStateChanged(BtConnectionState.SCANNING)
            if (!bt.startDiscovery()) onError("Bluetooth discovery could not start")
        } catch (_: SecurityException) {
            onError("Bluetooth scan permission was denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAsServer(activity: Activity) {
        val bt = adapter ?: return onError("This phone does not support Bluetooth")
        if (hostRunning) return
        try {
            if (!bt.isEnabled) return onError("Turn on Bluetooth first")
            hostRunning = true
            bt.cancelDiscovery()
            onStateChanged(BtConnectionState.SCANNING)

            // Start listening before asking the OS to make the device discoverable.
            scope.launch {
                try {
                    serverSocket?.close()
                    serverSocket = try {
                        bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                    } catch (e: IOException) {
                        bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                    }

                    try {
                        activity.startActivity(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            }
                        )
                    } catch (e: Exception) {
                        onError("Could not request Bluetooth discoverability: ${e.message}")
                    }

                    while (hostRunning) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            closeSocketOnly()
                            attachSocket(socket)
                            // attachSocket blocks in listenLoop until this peer disconnects.
                        } catch (e: IOException) {
                            if (!hostRunning) break
                            onError("Bluetooth host socket failed: ${e.message}")
                            delay(500)
                        }
                    }
                } catch (e: SecurityException) {
                    onError("Bluetooth connect/advertise permission was denied")
                } catch (e: IOException) {
                    if (hostRunning) onError("Bluetooth host failed: ${e.message}")
                } finally {
                    if (hostRunning) onStateChanged(BtConnectionState.DISCONNECTED)
                    try { serverSocket?.close() } catch (_: Exception) { }
                    serverSocket = null
                }
            }
        } catch (_: SecurityException) {
            hostRunning = false
            onError("Bluetooth permission was denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        onStateChanged(BtConnectionState.CONNECTING)
        scope.launch {
            var lastError: Throwable? = null
            repeat(CONNECT_RETRIES) { attempt ->
                if (activeSocket != null) return@launch
                try {
                    adapter?.cancelDiscovery()
                    closeSocketOnly()

                    // Secure RFCOMM first; many devices accept it.
                    try {
                        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        connectAndAttach(socket)
                        return@launch
                    } catch (secureError: Throwable) {
                        lastError = secureError
                    }

                    // OEM fallback: insecure RFCOMM using the same service UUID.
                    try {
                        val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        connectAndAttach(socket)
                        return@launch
                    } catch (insecureError: Throwable) {
                        lastError = insecureError
                    }
                } catch (e: SecurityException) {
                    lastError = e
                }
                if (attempt + 1 < CONNECT_RETRIES) {
                    delay(750L * (attempt + 1))
                }
            }

            onError("Bluetooth connection failed for ${safeName(device)} after retries: ${lastError?.message ?: "unknown socket error"}. Pair the phones in Android Bluetooth settings and keep the host phone discoverable.")
            onStateChanged(BtConnectionState.DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndAttach(socket: BluetoothSocket) {
        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                socket.connect()
            }
            attachSocket(socket)
        } catch (e: Throwable) {
            try { socket.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun attachSocket(socket: BluetoothSocket) {
        activeSocket = socket
        synchronized(this) {
            writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        }
        onStateChanged(BtConnectionState.CONNECTED)
        listenLoop(socket)
    }

    fun sendText(text: String) {
        scope.launch {
            try {
                val out = synchronized(this@BluetoothTransceiver) { writer }
                    ?: return@launch onError("Bluetooth is not connected")
                synchronized(this@BluetoothTransceiver) {
                    out.write(text.replace("\n", " "))
                    out.newLine()
                    out.flush()
                }
            } catch (e: IOException) {
                onError("Bluetooth send failed: ${e.message}")
                disconnect()
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
        } catch (_: IOException) {
        } finally {
            if (activeSocket === socket) {
                closeSocketOnly()
                if (hostRunning) onStateChanged(BtConnectionState.DISCONNECTED)
                else onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishDevices() {
        onDevicesChanged(devices.values.sortedWith(compareBy({ safeName(it) }, { it.address })))
    }

    @SuppressLint("MissingPermission")
    fun safeName(device: BluetoothDevice): String =
        try { device.name?.takeIf { it.isNotBlank() } ?: device.address }
        catch (_: SecurityException) { device.address }

    private fun closeSocketOnly() {
        try { writer?.close() } catch (_: Exception) { }
        try { activeSocket?.close() } catch (_: Exception) { }
        writer = null
        activeSocket = null
    }

    fun disconnect() {
        hostRunning = false
        try { serverSocket?.close() } catch (_: Exception) { }
        try { adapter?.cancelDiscovery() } catch (_: Exception) { }
        serverSocket = null
        closeSocketOnly()
        onStateChanged(BtConnectionState.DISCONNECTED)
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
