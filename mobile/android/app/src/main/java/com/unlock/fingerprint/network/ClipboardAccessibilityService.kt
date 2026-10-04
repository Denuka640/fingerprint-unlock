package com.unlock.fingerprint.network

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardAccService"
        var isServiceRunning = false
            private set
    }

    private var syncManager: ClipboardSyncManager? = null
    private var clipboardManager: ClipboardManager? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            syncManager?.checkAndAutoSyncIfNewText()
        } catch (e: Exception) {
            Log.w(TAG, "Error in clip listener: ${e.message}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        syncManager = ClipboardSyncManager(applicationContext)

        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
            Log.d(TAG, "Clipboard Accessibility Service connected & listening")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register clip listener in AccessibilityService: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Trigger auto-sync check whenever text selection or view event occurs system-wide
        try {
            syncManager?.checkAndAutoSyncIfNewText()
        } catch (e: Exception) {
            Log.w(TAG, "Error handling accessibility event: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Clipboard Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (_: Exception) {}
    }
}
