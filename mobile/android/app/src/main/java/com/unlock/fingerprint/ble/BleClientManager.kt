package com.unlock.fingerprint.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _connectionState.value = "Scanning for PC..."

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
            
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = "Scan Failed ($errorCode)"
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = "Connecting to ${device.name ?: "PC"}..."
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = "Connected"
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = "Disconnected"
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = "Ready to Unlock"
                requestChallenge()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHALLENGE_CHAR_UUID) {
                // Use deprecated API only for SDK < 33; use new API on Android 13+
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value // value is populated in legacy callback path
                } else {
                    characteristic.value
                }
                currentChallenge = data
                onChallengeReceived?.invoke(data)
            }
        }

        // Android 13+ (API 33) new callback signature with value parameter
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
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == AUTH_RESPONSE_CHAR_UUID) {
                _connectionState.value = "PC Unlocked Successfully!"
                onUnlockSuccess?.invoke()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiValue.value = rssi
            }
        }
    }

    fun requestChallenge() {
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
        bluetoothGatt?.writeCharacteristic(char)
    }

    fun isConnected(): Boolean = bluetoothGatt != null

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
