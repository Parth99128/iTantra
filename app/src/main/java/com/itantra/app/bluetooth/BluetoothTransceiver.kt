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
 * Classic Bluetooth (RFCOMM/SPP) link between two phones. We deliberately use
 * Bluetooth Classic, not BLE or WiFi Direct, as the PRIMARY path for the demo:
 * pairing is more predictable on stage, and payload here is tiny (a short text
 * string per sentence) so Bluetooth Classic's lower theoretical bandwidth is
 * irrelevant — this is exactly the point of the PS (audio never crosses the link).
 *
 * WiFi Direct can be added as a secondary/longer-range transport later using the
 * same TextTransceiver interface shape if you have time after the core loop works.
 */
class BluetoothTransceiver(
    private val onTextReceived: (String) -> Unit,
    private val onStateChanged: (BtConnectionState) -> Unit
) {
    companion object {
        // Standard Serial Port Profile UUID — required for RFCOMM interop between phones.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "iTantraWalkieLink"
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Call this on one phone (acts as "host"). */
    @SuppressLint("MissingPermission")
    fun startAsServer() {
        onStateChanged(BtConnectionState.SCANNING)
        scope.launch {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                val socket = serverSocket?.accept() // blocks until the other phone connects
                activeSocket = socket
                onStateChanged(BtConnectionState.CONNECTED)
                listenLoop(socket)
            } catch (e: IOException) {
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    /** Call this on the other phone (acts as "client") after the user picks a
     *  paired device from the system Bluetooth picker. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        onStateChanged(BtConnectionState.CONNECTING)
        scope.launch {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter?.cancelDiscovery()
                socket.connect() // blocking call, run off main thread
                activeSocket = socket
                onStateChanged(BtConnectionState.CONNECTED)
                listenLoop(socket)
            } catch (e: IOException) {
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    /** Send a finalized STT sentence to the other phone. Payload is UTF-8 text,
     *  a few dozen bytes typically — this is why the "low bitrate link" constraint
     *  in the PS is a non-issue once you've moved to text instead of audio. */
    fun sendText(text: String) {
        scope.launch {
            try {
                activeSocket?.outputStream?.write((text + "\n").toByteArray(Charsets.UTF_8))
                activeSocket?.outputStream?.flush()
            } catch (e: IOException) {
                onStateChanged(BtConnectionState.DISCONNECTED)
            }
        }
    }

    private fun listenLoop(socket: BluetoothSocket) {
        val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) onTextReceived(line)
            }
        } catch (e: IOException) {
            onStateChanged(BtConnectionState.DISCONNECTED)
        }
    }

    fun disconnect() {
        try {
            activeSocket?.close()
            serverSocket?.close()
        } catch (_: IOException) {
        }
        onStateChanged(BtConnectionState.DISCONNECTED)
    }
}
