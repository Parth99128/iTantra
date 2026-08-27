package com.itantra.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.itantra.app.core.BtConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth (RFCOMM/SPP) link between two phones.
 * Audio never crosses the link; only finalized UTF-8 text sentences are sent.
 */
class BluetoothTransceiver(
    private val onTextReceived: (String) -> Unit,
    private val onStateChanged: (BtConnectionState) -> Unit
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "iTantraWalkieLink"
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startAsServer() {
        onStateChanged(BtConnectionState.SCANNING)
        scope.launch {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                val socket = serverSocket?.accept()
                if (socket == null) {
                    onStateChanged(BtConnectionState.DISCONNECTED)
                    return@launch
                }
                activeSocket = socket
                onStateChanged(BtConnectionState.CONNECTED)
                listenLoop(socket)
            } catch (_: IOException) {
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        onStateChanged(BtConnectionState.CONNECTING)
        scope.launch {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter?.cancelDiscovery()
                socket.connect()
                activeSocket = socket
                onStateChanged(BtConnectionState.CONNECTED)
                listenLoop(socket)
            } catch (_: IOException) {
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    fun sendText(text: String) {
        scope.launch {
            try {
                val socket = activeSocket ?: return@launch
                socket.outputStream.write((text + "\n").toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            } catch (_: IOException) {
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
        } catch (_: IOException) {
            onStateChanged(BtConnectionState.DISCONNECTED)
        }
    }

    fun disconnect() {
        try {
            activeSocket?.close()
            serverSocket?.close()
        } catch (_: IOException) {
        } finally {
            activeSocket = null
            serverSocket = null
            onStateChanged(BtConnectionState.DISCONNECTED)
        }
    }
}
