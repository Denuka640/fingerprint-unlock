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
import com.unlock.fingerprint.ble.BleClientManager
import com.unlock.fingerprint.crypto.KeyStoreManager
import org.json.JSONObject

class PairingActivity : AppCompatActivity() {

    private lateinit var txtPairingInfo: TextView
    private lateinit var btnScanQr: Button
    private lateinit var bleManager: BleClientManager

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            try {
                val json = JSONObject(result.contents)
                val machineName = json.optString("MachineName", "Windows PC")
                txtPairingInfo.text = "Sending pairing data to $machineName..."
                btnScanQr.isEnabled = false

                val pubKeyDer = KeyStoreManager.getPublicKeyDer()
                val deviceId = Build.MODEL ?: "AndroidPhone"
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

                // Set up callbacks BEFORE sending data
                bleManager.onPairingSuccess = {
                    runOnUiThread {
                        txtPairingInfo.text = "✓ Successfully paired with $machineName!"
                        Toast.makeText(this, "✓ Paired with $machineName!", Toast.LENGTH_LONG).show()
                        btnScanQr.isEnabled = true
                        // Delay finish so user can see the success message
                        txtPairingInfo.postDelayed({ finish() }, 1500)
                    }
                }

                bleManager.onPairingFailed = { error ->
                    runOnUiThread {
                        txtPairingInfo.text = "✗ Pairing failed: $error"
                        Toast.makeText(this, "Pairing failed: $error", Toast.LENGTH_LONG).show()
                        btnScanQr.isEnabled = true
                    }
                }

                bleManager.sendPairingData(deviceId, deviceName, pubKeyDer)

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

        btnScanQr.setOnClickListener {
            if (!bleManager.isConnected()) {
                Toast.makeText(this, "Not connected to PC. Return to main screen and wait for 'Ready to Unlock'.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
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
