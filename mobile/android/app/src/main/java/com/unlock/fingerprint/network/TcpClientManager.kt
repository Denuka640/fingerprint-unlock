package com.unlock.fingerprint.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class TcpClientManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TcpClientManager"
        private const val UDP_DISCOVERY_PORT = 9899

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

    init {
        // Load saved IP on initialization
        val prefs = context.getSharedPreferences("UnlockPrefs", Context.MODE_PRIVATE)
        currentIpAddress = prefs.getString("PC_IP_ADDRESS", null)
    }

    fun saveIpAddress(ip: String, machineName: String? = null) {
        currentIpAddress = ip
        val prefs = context.getSharedPreferences("UnlockPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit().putString("PC_IP_ADDRESS", ip)
        if (machineName != null) {
            editor.putString("PAIRED_MACHINE_NAME", machineName)
        }
        editor.apply()
    }

    /**
     * Broadcasts a UDP ping on port 9899 to discover PC IP dynamically if DHCP IP changed.
     */
    suspend fun discoverPcIpOnLan(timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val pingData = "DISCOVER_FINGERPRINT_PC".toByteArray()
            // Send to global broadcast 255.255.255.255
            try {
                val packet = DatagramPacket(
                    pingData,
                    pingData.size,
                    InetAddress.getByName("255.255.255.255"),
                    UDP_DISCOVERY_PORT
                )
                socket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Global broadcast send failed: ${e.message}")
            }

            // Also send to all local interface subnet broadcast addresses
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isLoopback && networkInterface.isUp) {
                        for (interfaceAddress in networkInterface.interfaceAddresses) {
                            val broadcast = interfaceAddress.broadcast
                            if (broadcast != null) {
                                val p = DatagramPacket(pingData, pingData.size, broadcast, UDP_DISCOVERY_PORT)
                                socket.send(p)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Interface broadcast send failed: ${e.message}")
            }

            Log.d(TAG, "Sent UDP discovery pings on port $UDP_DISCOVERY_PORT")

            val receiveBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(responsePacket)

            val reply = String(responsePacket.data, 0, responsePacket.length).trim()
            val hostIp = responsePacket.address.hostAddress
            Log.d(TAG, "Received UDP discovery response from $hostIp: $reply")

            var machineName: String? = null
            try {
                val json = JSONObject(reply)
                if (json.has("MachineName")) {
                    machineName = json.getString("MachineName")
                }
            } catch (e: Exception) {
                // Ignore json parse error if simple string returned
            }

            if (hostIp != null) {
                saveIpAddress(hostIp, machineName)
                return@withContext hostIp
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP discovery scan timeout/failed: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return@withContext null
    }

    private fun tryHttpChallenge(ip: String): ByteArray? {
        try {
            val url = URL("http://$ip:$currentPort/challenge")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            if (connection.responseCode == 200) {
                val input = connection.inputStream
                val bytes = input.readBytes()
                input.close()
                return bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed HTTP challenge to $ip: ${e.message}")
        }
        return null
    }

    suspend fun getChallenge(): ByteArray? = withContext(Dispatchers.IO) {
        val savedIp = currentIpAddress
        if (savedIp != null) {
            val challengeBytes = tryHttpChallenge(savedIp)
            if (challengeBytes != null) {
                return@withContext challengeBytes
            }
            Log.i(TAG, "Stored IP ($savedIp) unreachable. Attempting LAN UDP discovery...")
        }

        // Auto-heal IP address using LAN discovery if saved IP failed or was missing
        val discoveredIp = discoverPcIpOnLan()
        if (discoveredIp != null) {
            val challengeBytes = tryHttpChallenge(discoveredIp)
            if (challengeBytes != null) {
                return@withContext challengeBytes
            }
        }

        return@withContext null
    }

    suspend fun sendPairingData(deviceId: String, deviceName: String, pubKey: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val ip = currentIpAddress ?: discoverPcIpOnLan() ?: return@withContext false
        try {
            val url = URL("http://$ip:$currentPort/pair")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
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

            if (connection.responseCode == 200) {
                saveIpAddress(ip)
                return@withContext true
            }
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
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
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

    suspend fun sendClipboardData(deviceId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val ip = currentIpAddress ?: discoverPcIpOnLan() ?: return@withContext false
        try {
            val url = URL("http://$ip:$currentPort/clipboard")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val output = DataOutputStream(connection.outputStream)
            val devIdBytes = deviceId.toByteArray(Charsets.UTF_8)
            val textBytes = text.toByteArray(Charsets.UTF_8)

            output.writeByte(devIdBytes.size)
            output.write(devIdBytes)
            output.writeInt(textBytes.size)
            output.write(textBytes)

            output.flush()
            output.close()

            return@withContext connection.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clipboard data over WiFi: ${e.message}")
        }
        return@withContext false
    }
}

