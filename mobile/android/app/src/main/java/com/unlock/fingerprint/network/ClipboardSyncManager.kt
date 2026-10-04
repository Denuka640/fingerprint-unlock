package com.unlock.fingerprint.network

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.unlock.fingerprint.ble.BleClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClipboardSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardSyncManager"
        private const val PREFS_NAME = "UnlockPrefs"
        private const val KEY_AUTO_SYNC = "AUTO_CLIPBOARD_SYNC"
        private const val KEY_LAST_SYNCED = "LAST_SYNCED_TEXT"
    }

    private var clipboardManager: ClipboardManager? = null
    private val tcpManager = TcpClientManager.getInstance(context)
    private val bleManager = BleClientManager.getInstance(context)

    init {
        try {
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ClipboardManager: ${e.message}")
        }
    }

    var isAutoSyncEnabled: Boolean
        get() {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getBoolean(KEY_AUTO_SYNC, true)
            } catch (e: Exception) {
                true
            }
        }
        set(value) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
            } catch (_: Exception) {}
        }

    private var lastSyncedText: String? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isAutoSyncEnabled) {
            checkAndAutoSyncIfNewText()
        }
    }

    fun startListening() {
        try {
            if (clipboardManager == null) {
                clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            }
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register clip listener: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (_: Exception) {}
    }

    /**
     * Checks if current clipboard has new text since last sync and automatically sends it.
     */
    fun checkAndAutoSyncIfNewText(onResult: ((Boolean, String) -> Unit)? = null) {
        if (!isAutoSyncEnabled) return

        try {
            val cm = clipboardManager ?: (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            if (cm == null) return
            
            val primaryClip = try {
                if (cm.hasPrimaryClip()) cm.primaryClip else null
            } catch (e: Exception) {
                Log.d(TAG, "Background clip read info: ${e.message}")
                null
            }

            if (primaryClip != null && primaryClip.itemCount > 0) {
                val item = primaryClip.getItemAt(0)
                val text = item.text?.toString() ?: item.coerceToText(context)?.toString()

                if (!text.isNullOrBlank() && text != lastSyncedText) {
                    lastSyncedText = text
                    sendClipboardToPc(text, onResult)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking clipboard text: ${e.message}")
        }
    }

    fun syncCurrentClipboard(onResult: (Boolean, String) -> Unit) {
        try {
            val cm = clipboardManager ?: (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            if (cm == null) {
                onResult(false, "Clipboard unavailable on this device")
                return
            }

            if (!cm.hasPrimaryClip()) {
                onResult(false, "Phone clipboard is empty")
                return
            }

            val primaryClip = cm.primaryClip
            if (primaryClip == null || primaryClip.itemCount == 0) {
                onResult(false, "Phone clipboard is empty")
                return
            }

            val item = primaryClip.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(context)?.toString()

            if (text.isNullOrBlank()) {
                onResult(false, "No text found in clipboard")
                return
            }

            lastSyncedText = text
            sendClipboardToPc(text, onResult)
        } catch (e: Exception) {
            onResult(false, "Failed to access clipboard: ${e.message}")
        }
    }

    fun sendClipboardToPc(text: String, onResult: ((Boolean, String) -> Unit)?) {
        val deviceId = Build.MODEL ?: "AndroidPhone"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Try WiFi HTTP first
                val wifiSuccess = tcpManager.sendClipboardData(deviceId, text)
                if (wifiSuccess) {
                    Log.d(TAG, "Successfully synced clipboard to PC over WiFi")
                    onResult?.invoke(true, "✓ Synced via WiFi: ${truncateText(text)}")
                    return@launch
                }

                // 2. Try BLE fallback
                val bleSuccess = bleManager.sendClipboardData(text)
                if (bleSuccess) {
                    Log.d(TAG, "Successfully synced clipboard to PC over BLE")
                    onResult?.invoke(true, "✓ Synced via Bluetooth: ${truncateText(text)}")
                    return@launch
                }

                Log.w(TAG, "Failed to sync clipboard to PC over WiFi and BLE")
                onResult?.invoke(false, "PC Unreachable. Reconnect WiFi or Bluetooth.")
            } catch (e: Exception) {
                onResult?.invoke(false, "Clipboard sync error: ${e.message}")
            }
        }
    }

    private fun truncateText(str: String): String {
        return if (str.length > 25) str.take(25) + "..." else str
    }
}
