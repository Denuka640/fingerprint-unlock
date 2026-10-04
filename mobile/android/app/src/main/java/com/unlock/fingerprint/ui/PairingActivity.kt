package com.unlock.fingerprint.ui

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.unlock.fingerprint.R
import com.unlock.fingerprint.network.TcpClientManager
import com.unlock.fingerprint.ble.BleClientManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.unlock.fingerprint.crypto.KeyStoreManager
import org.json.JSONObject

class PairingActivity : AppCompatActivity() {

    private lateinit var txtPairingInfo: TextView
    private lateinit var btnScanQr: Button
    private lateinit var bleManager: BleClientManager
    private lateinit var tcpManager: TcpClientManager

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            try {
                val json = JSONObject(result.contents)
                val machineName = json.optString("MachineName", "Windows PC")
                
                // Extract IP for WiFi
                val ipAddress = if (json.has("IPAddress")) json.getString("IPAddress") else null
                val prefs = getSharedPreferences("UnlockPrefs", MODE_PRIVATE)
                val editor = prefs.edit().putString("PAIRED_MACHINE_NAME", machineName)
                if (ipAddress != null) {
                    editor.putString("PC_IP_ADDRESS", ipAddress)
                    tcpManager.saveIpAddress(ipAddress, machineName)
                }
                editor.apply()

                txtPairingInfo.text = "Sending pairing data to $machineName via WiFi..."
                btnScanQr.isEnabled = false

                val pubKeyDer = KeyStoreManager.getPublicKeyDer()
                val deviceId = Build.MODEL ?: "AndroidPhone"
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

                // Send via TCP in a coroutine
                lifecycleScope.launch {
                    val success = tcpManager.sendPairingData(deviceId, deviceName, pubKeyDer)
                    if (success) {
                        txtPairingInfo.text = "✓ Successfully paired via WiFi with $machineName!"
                        Toast.makeText(this@PairingActivity, "✓ Paired with $machineName!", Toast.LENGTH_LONG).show()
                        btnScanQr.isEnabled = true
                        txtPairingInfo.postDelayed({ finish() }, 1500)
                    } else {
                        // Fallback to BLE
                        txtPairingInfo.text = "WiFi failed. Trying Bluetooth..."
                        if (bleManager.isConnected()) {
                            bleManager.onPairingSuccess = {
                                runOnUiThread {
                                    txtPairingInfo.text = "✓ Successfully paired with $machineName!"
                                    Toast.makeText(this@PairingActivity, "✓ Paired with $machineName!", Toast.LENGTH_LONG).show()
                                    btnScanQr.isEnabled = true
                                    txtPairingInfo.postDelayed({ finish() }, 1500)
                                }
                            }
                            bleManager.onPairingFailed = { error ->
                                runOnUiThread {
                                    txtPairingInfo.text = "✗ Pairing failed: $error"
                                    Toast.makeText(this@PairingActivity, "Pairing failed: $error", Toast.LENGTH_LONG).show()
                                    btnScanQr.isEnabled = true
                                }
                            }
                            bleManager.sendPairingData(deviceId, deviceName, pubKeyDer)
                        } else {
                            txtPairingInfo.text = "✗ Network unreachable and BLE disconnected."
                            Toast.makeText(this@PairingActivity, "Network unreachable", Toast.LENGTH_LONG).show()
                            btnScanQr.isEnabled = true
                        }
                    }
                }

            } catch (e: Exception) {
                Toast.makeText(this, "Invalid QR code format: ${e.message}", Toast.LENGTH_LONG).show()
                btnScanQr.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        txtPairingInfo = findViewById(R.id.txtPairingInfo)
        btnScanQr = findViewById(R.id.btnScanQr)
        bleManager = BleClientManager.getInstance(this)
        tcpManager = TcpClientManager.getInstance(this)

        btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Point camera at Laptop Setup App QR code")
                setBeepEnabled(true)
                setOrientationLocked(true)
            }
            qrLauncher.launch(options)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up callbacks to avoid leaks
        bleManager.onPairingSuccess = null
        bleManager.onPairingFailed = null
    }
}
