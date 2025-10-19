package com.bd.rc

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

private const val TAG = "RC_CAR"

enum class ConnectionState { Disconnected, Connecting, Connected }

data class UiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val deviceName: String? = null,
    val lastCommand: Char? = null,
    val commandHistory: List<Char> = emptyList(),
    val permissionGranted: Boolean = false,
    val errorMessage: String? = null
)

class RcCarViewModel(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var bluetoothSocket: BluetoothSocket? = null
    private var connectJob: Job? = null

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    fun refreshPermission(granted: Boolean) {
        _state.value = _state.value.copy(permissionGranted = granted)
    }

    fun disconnect() {
        viewModelScope.launch(ioDispatcher) {
            try { bluetoothSocket?.close() } catch (_: IOException) {}
            bluetoothSocket = null
            _state.value = _state.value.copy(connectionState = ConnectionState.Disconnected, deviceName = null)
        }
    }

    fun autoConnect() {
        if (_state.value.connectionState == ConnectionState.Connected || _state.value.connectionState == ConnectionState.Connecting) return
        connectJob?.cancel()
        connectJob = viewModelScope.launch(ioDispatcher) {
            val context = getApplication<Application>().applicationContext
            val adapter = bluetoothAdapter(context)
            if (adapter == null || !adapter.isEnabled) {
                _state.value = _state.value.copy(connectionState = ConnectionState.Disconnected, errorMessage = "Bluetooth unavailable")
                return@launch
            }

            val candidate = findBondedHc05(adapter)
            if (candidate == null) {
                _state.value = _state.value.copy(connectionState = ConnectionState.Disconnected, errorMessage = "HC-05 not bonded")
                return@launch
            }

            var lastError: String? = null
            repeat(3) attempt@{ attempt ->
                _state.value = _state.value.copy(connectionState = ConnectionState.Connecting, deviceName = candidate.name)
                try {
                    val socket = createSocket(candidate)
                    socket.connect()
                    bluetoothSocket = socket
                    _state.value = _state.value.copy(connectionState = ConnectionState.Connected, deviceName = candidate.name, errorMessage = null)
                    Log.i(TAG, "Connected to ${candidate.name}")
                    return@launch
                } catch (t: Throwable) {
                    lastError = t.message ?: t.toString()
                    Log.e(TAG, "Connect attempt ${attempt + 1} failed: $lastError")
                    try { bluetoothSocket?.close() } catch (_: IOException) {}
                    bluetoothSocket = null
                    delay(5000)
                }
            }
            _state.value = _state.value.copy(connectionState = ConnectionState.Disconnected, errorMessage = lastError)
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBondedHc05(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = adapter.bondedDevices
        return bonded.firstOrNull { it.name?.contains("HC-05", ignoreCase = true) == true }
    }

    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice): BluetoothSocket {
        // Prefer well-known SPP UUID
        return try {
            device.createRfcommSocketToServiceRecord(sppUuid)
        } catch (e: Throwable) {
            // Fallback via reflection for some stacks
            Log.w(TAG, "SPP create failed, fallback", e)
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }
    }

    fun sendCommand(command: Char) {
        viewModelScope.launch(ioDispatcher) {
            val socket = bluetoothSocket
            if (socket == null || !socket.isConnected) {
                _state.value = _state.value.copy(errorMessage = "Not connected")
                return@launch
            }
            try {
                socket.outputStream.write(byteArrayOf(command.code.toByte()))
                socket.outputStream.flush()
                Log.i(TAG, "Sent command: $command")
                val newHistory = (listOf(command) + _state.value.commandHistory).take(10)
                _state.value = _state.value.copy(lastCommand = command, commandHistory = newHistory, errorMessage = null)
            } catch (t: Throwable) {
                Log.e(TAG, "Send failed", t)
                _state.value = _state.value.copy(errorMessage = t.message)
            }
        }
    }
}
