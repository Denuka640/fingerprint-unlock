package com.unlock.fingerprint.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

class TcpClientManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TcpClientManager"
        @Volatile
        private var instance: TcpClientManager? = null

        fun getInstance(context: Context): TcpClientManager {
            return instance ?: synchronized(this) {
                instance ?: TcpClientManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var currentIpAddress: String? = null
    var currentPort: Int = 9898

    suspend fun getChallenge(): ByteArray? = withContext(Dispatchers.IO) {
        val ip = currentIpAddress ?: return@withContext null
        try {
            val url = URL("http://$ip:$currentPort/challenge")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val input = connection.inputStream
                val bytes = input.readBytes()
                input.close()
                return@withContext bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get challenge: ${e.message}")
        }
        return@withContext null
    }

    suspend fun sendPairingData(deviceId: String, deviceName: String, pubKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val ip = currentIpAddress ?: return@withContext false
        try {
            val url = URL("http://$ip:$currentPort/pair")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            
            val output = DataOutputStream(connection.outputStream)
            val devIdBytes = deviceId.toByteArray()
            val devNameBytes = deviceName.toByteArray()

            output.writeByte(devIdBytes.size)
            output.write(devIdBytes)
            output.writeByte(devNameBytes.size)
            output.write(devNameBytes)
            output.writeShort(pubKey.size)
            output.write(pubKey)
            
            output.flush()
            output.close()

            return@withContext connection.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pair over WiFi: ${e.message}")
        }
        return@withContext false
    }

    suspend fun sendUnlockAuth(deviceId: String, signature: ByteArray, challenge: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val ip = currentIpAddress ?: return@withContext false
        try {
            val url = URL("http://$ip:$currentPort/unlock")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            
            val output = DataOutputStream(connection.outputStream)
            val devIdBytes = deviceId.toByteArray()

            output.writeByte(devIdBytes.size)
            output.write(devIdBytes)
            output.writeShort(signature.size)
            output.write(signature)
            output.writeShort(challenge.size)
            output.write(challenge)
            
            output.flush()
            output.close()

            return@withContext connection.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auth over WiFi: ${e.message}")
        }
        return@withContext false
    }
}
