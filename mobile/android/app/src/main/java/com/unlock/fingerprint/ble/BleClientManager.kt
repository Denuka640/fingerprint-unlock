package com.unlock.fingerprint.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

@SuppressLint("MissingPermission")
class BleClientManager private constructor(private val context: Context) {


    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10001")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10002")
        val CHALLENGE_CHAR_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10003")
        val AUTH_RESPONSE_CHAR_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10004")
        val PAIRING_CHAR_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10005")
        val CLIPBOARD_CHAR_UUID: UUID = UUID.fromString("7B37A55C-3BF2-4D3E-A59B-51421DA10006")

        private const val TAG = "BleClientManager"

        // Singleton so PairingActivity reuses the connected instance from MainActivity
        @Volatile private var _instance: BleClientManager? = null

        fun getInstance(context: Context): BleClientManager {
            return _instance ?: synchronized(this) {
                _instance ?: BleClientManager(context.applicationContext).also { _instance = it }
            }
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _rssiValue = MutableStateFlow(0)
    val rssiValue: StateFlow<Int> = _rssiValue

    private var currentChallenge: ByteArray? = null
    var onChallengeReceived: ((ByteArray) -> Unit)? = null
    var onUnlockSuccess: (() -> Unit)? = null
    var onPairingSuccess: (() -> Unit)? = null
    var onPairingFailed: ((String) -> Unit)? = null

    private var isScanning = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var pendingConnectionDeferred: CompletableDeferred<Boolean>? = null
    private var pendingWriteDeferred: CompletableDeferred<Boolean>? = null

    fun startScan() {
        if (isScanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _connectionState.value = "Scanning for PC..."
        isScanning = true

        // Timeout scan after 12 seconds
        handler.postDelayed({
            if (isScanning) {
                isScanning = false
                scanner.stopScan(scanCallback)
                _connectionState.value = "Scan Timeout - PC not found"
                onPairingFailed?.invoke("Scan Timeout - Unpair from Windows Settings if already paired!")
            }
        }, 12000)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            _rssiValue.value = result.rssi
            Log.d(TAG, "Discovered PC BLE device: ${result.device.address}, RSSI: ${result.rssi}")
            
            if (isScanning) {
                isScanning = false
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = "Scan Failed ($errorCode) - Try restarting Bluetooth"
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        saveMacAddress(device.address)
        _connectionState.value = "Connecting to ${device.name ?: "PC"}..."
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun saveMacAddress(mac: String) {
        val prefs = context.getSharedPreferences("UnlockPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("PAIRED_BLE_MAC", mac).apply()
    }

    fun getSavedMacAddress(): String? {
        val prefs = context.getSharedPreferences("UnlockPrefs", Context.MODE_PRIVATE)
        return prefs.getString("PAIRED_BLE_MAC", null)
    }

    fun connectToSavedMac(): Boolean {
        val mac = getSavedMacAddress() ?: return false
        val adapter = bluetoothAdapter ?: return false
        try {
            val device = adapter.getRemoteDevice(mac)
            connectToDevice(device)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to saved MAC $mac: ${e.message}")
        }
        return false
    }

    suspend fun ensureConnectedAndServicesDiscovered(timeoutMs: Long = 6000): Boolean = withContext(Dispatchers.IO) {
        val currentGatt = bluetoothGatt
        if (currentGatt != null && (_connectionState.value == "Connected" || _connectionState.value == "Ready to Unlock")) {
            val service = currentGatt.getService(SERVICE_UUID)
            if (service != null) {
                return@withContext true
            }
        }

        Log.d(TAG, "BLE GATT disconnected or services unavailable. Auto-reconnecting to saved MAC...")
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null

        val deferred = CompletableDeferred<Boolean>()
        pendingConnectionDeferred = deferred

        val initiated = connectToSavedMac()
        if (!initiated) {
            Log.w(TAG, "No saved BLE MAC address found to reconnect.")
            pendingConnectionDeferred = null
            return@withContext false
        }

        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false

        if (!result) {
            Log.w(TAG, "BLE auto-reconnect timed out or failed after ${timeoutMs}ms")
        }
        pendingConnectionDeferred = null
        return@withContext result
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = "Connected"
                gatt.device?.address?.let { saveMacAddress(it) }
                try {
                    gatt.requestMtu(512)
                } catch (_: Exception) {}
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = "Disconnected"
                bluetoothGatt?.close()
                bluetoothGatt = null
                pendingConnectionDeferred?.complete(false)
                pendingWriteDeferred?.complete(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = "Ready to Unlock"
                pendingConnectionDeferred?.complete(true)
                requestChallenge()
            } else {
                pendingConnectionDeferred?.complete(false)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHALLENGE_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value
                } else {
                    characteristic.value
                }
                currentChallenge = data
                onChallengeReceived?.invoke(data)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHALLENGE_CHAR_UUID) {
                currentChallenge = value
                onChallengeReceived?.invoke(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == AUTH_RESPONSE_CHAR_UUID -> {
                    _connectionState.value = "PC Unlocked Successfully!"
                    onUnlockSuccess?.invoke()
                }
                characteristic.uuid == PAIRING_CHAR_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _connectionState.value = "Paired Successfully!"
                        onPairingSuccess?.invoke()
                    } else {
                        _connectionState.value = "Pairing Failed (status=$status)"
                        onPairingFailed?.invoke("BLE write failed with status $status")
                    }
                }
                characteristic.uuid == CLIPBOARD_CHAR_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        pendingWriteDeferred?.complete(true)
                    } else {
                        pendingWriteDeferred?.complete(false)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiValue.value = rssi
            }
        }
    }

    fun requestChallenge() {
        if (bluetoothGatt == null) {
            if (!connectToSavedMac()) {
                startScan()
            }
            return
        }
        val service = bluetoothGatt?.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CHALLENGE_CHAR_UUID) ?: return
        bluetoothGatt?.readCharacteristic(char)
    }

    fun sendBiometricAuthResponse(signature: ByteArray, challenge: ByteArray) {
        val service = bluetoothGatt?.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(AUTH_RESPONSE_CHAR_UUID) ?: return

        val deviceId = Build.MODEL ?: "AndroidPhone"
        val devIdBytes = deviceId.toByteArray(StandardCharsets.UTF_8)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(devIdBytes.size)
        dos.write(devIdBytes)
        dos.writeShort(signature.size)
        dos.write(signature)
        dos.writeShort(challenge.size)
        dos.write(challenge)
        dos.flush()

        val payload = baos.toByteArray()
        char.value = payload
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        bluetoothGatt?.writeCharacteristic(char)
    }

    fun sendPairingData(deviceId: String, deviceName: String, publicKeyDer: ByteArray) {
        val service = bluetoothGatt?.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(PAIRING_CHAR_UUID) ?: return

        val devIdBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
        val devNameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(devIdBytes.size)
        dos.write(devIdBytes)
        dos.writeByte(devNameBytes.size)
        dos.write(devNameBytes)
        dos.writeShort(publicKeyDer.size)
        dos.write(publicKeyDer)
        dos.flush()

        char.value = baos.toByteArray()
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        bluetoothGatt?.writeCharacteristic(char)
    }

    suspend fun sendClipboardData(text: String): Boolean = withContext(Dispatchers.IO) {
        val isReady = ensureConnectedAndServicesDiscovered(6000)
        if (!isReady) {
            Log.w(TAG, "Cannot send clipboard data: BLE connection could not be established")
            return@withContext false
        }

        val gatt = bluetoothGatt ?: return@withContext false
        val service = gatt.getService(SERVICE_UUID) ?: return@withContext false
        val char = service.getCharacteristic(CLIPBOARD_CHAR_UUID) ?: return@withContext false

        val deviceId = Build.MODEL ?: "AndroidPhone"
        val devIdBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(devIdBytes.size)
        dos.write(devIdBytes)
        dos.writeInt(textBytes.size)
        dos.write(textBytes)
        dos.flush()

        val writeDeferred = CompletableDeferred<Boolean>()
        pendingWriteDeferred = writeDeferred

        char.value = baos.toByteArray()
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val initiated = gatt.writeCharacteristic(char)
        if (!initiated) {
            pendingWriteDeferred = null
            return@withContext false
        }

        val writeSuccess = withTimeoutOrNull(3000) {
            writeDeferred.await()
        } ?: true

        pendingWriteDeferred = null
        return@withContext writeSuccess
    }

    fun isConnected(): Boolean = bluetoothGatt != null

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}

