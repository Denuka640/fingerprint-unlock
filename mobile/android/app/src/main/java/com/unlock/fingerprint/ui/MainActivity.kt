package com.unlock.fingerprint.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.unlock.fingerprint.R
import com.unlock.fingerprint.ble.BleClientManager
import com.unlock.fingerprint.crypto.KeyStoreManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var bleManager: BleClientManager
    private lateinit var tcpManager: com.unlock.fingerprint.network.TcpClientManager
    private lateinit var biometricHelper: BiometricPromptHelper

    private lateinit var txtStatus: TextView
    private lateinit var txtRssi: TextView
    private lateinit var btnScan: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnPair: Button

    private var currentChallenge: ByteArray? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.startScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for unlock", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtRssi = findViewById(R.id.txtRssi)
        btnScan = findViewById(R.id.btnScan)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnPair = findViewById(R.id.btnPair)

        // Initialize hardware key
        KeyStoreManager.getOrCreateHardwareKeyPair()

        bleManager = BleClientManager.getInstance(this)
        tcpManager = com.unlock.fingerprint.network.TcpClientManager.getInstance(this)
        
        // Load saved IP
        val prefs = getSharedPreferences("UnlockPrefs", MODE_PRIVATE)
        tcpManager.currentIpAddress = prefs.getString("PC_IP_ADDRESS", null)

        biometricHelper = BiometricPromptHelper(
            this,
            onAuthSuccess = { signature ->
                currentChallenge?.let { challenge ->
                    val deviceId = Build.MODEL ?: "AndroidPhone"
                    lifecycleScope.launch {
                        val success = tcpManager.sendUnlockAuth(deviceId, signature, challenge)
                        if (success) {
                            runOnUiThread {
                                txtStatus.text = "✓ Laptop Unlocked Successfully via WiFi!"
                                Toast.makeText(this@MainActivity, "✓ Laptop Unlocked Successfully!", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            // Fallback to BLE
                            bleManager.sendBiometricAuthResponse(signature, challenge)
                            runOnUiThread {
                                txtStatus.text = "Fingerprint Verified! Unlocking PC via BLE..."
                            }
                        }
                    }
                }
            },
            onAuthError = { err ->
                runOnUiThread {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            }
        )

        bleManager.onChallengeReceived = { challenge ->
            currentChallenge = challenge
            runOnUiThread {
                txtStatus.text = "Challenge Received from PC"
                // Automatically prompt biometric when challenge arrives
                biometricHelper.showBiometricPrompt(challenge)
            }
        }

        bleManager.onUnlockSuccess = {
            runOnUiThread {
                Toast.makeText(this, "✓ Laptop Unlocked Successfully!", Toast.LENGTH_LONG).show()
            }
        }

        btnScan.setOnClickListener {
            // Check WiFi connection first
            lifecycleScope.launch {
                txtStatus.text = "Checking WiFi connection..."
                val challengeBytes = tcpManager.getChallenge()
                if (challengeBytes != null) {
                    currentChallenge = challengeBytes
                    runOnUiThread {
                        txtStatus.text = "Challenge Received via WiFi"
                        biometricHelper.showBiometricPrompt(challengeBytes)
                    }
                } else {
                    runOnUiThread {
                        txtStatus.text = "WiFi failed. Scanning BLE..."
                        checkAndRequestPermissions()
                    }
                }
            }
        }

        btnUnlock.setOnClickListener {
            currentChallenge?.let {
                biometricHelper.showBiometricPrompt(it)
                currentChallenge = null
            } ?: run {
                // Check WiFi connection first
                lifecycleScope.launch {
                    txtStatus.text = "Requesting unlock via WiFi..."
                    val challengeBytes = tcpManager.getChallenge()
                    if (challengeBytes != null) {
                        currentChallenge = challengeBytes
                        runOnUiThread {
                            txtStatus.text = "Challenge Received via WiFi"
                            biometricHelper.showBiometricPrompt(challengeBytes)
                        }
                    } else {
                        runOnUiThread {
                            txtStatus.text = "WiFi failed. Trying BLE..."
                            bleManager.requestChallenge()
                        }
                    }
                }
            }
        }

        btnPair.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }

        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                txtStatus.text = state
            }
        }

        lifecycleScope.launch {
            bleManager.rssiValue.collectLatest { rssi ->
                txtRssi.text = if (rssi != 0) "$rssi dBm" else "-- dBm"
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            bleManager.startScan()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
