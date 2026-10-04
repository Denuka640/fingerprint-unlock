package com.unlock.fingerprint.network

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardAccService"
        var isServiceRunning = false
            private set
    }

    private var syncManager: ClipboardSyncManager? = null
    private var clipboardManager: ClipboardManager? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            Log.d(TAG, "Primary clip changed event received in Accessibility Service!")
            syncManager?.checkAndAutoSyncIfNewText()
        } catch (e: Exception) {
            Log.w(TAG, "Error in clip listener: ${e.message}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        syncManager = ClipboardSyncManager(this)

        // Attach a 1x1 transparent accessibility overlay window to gain Android WindowManager focus privilege
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            overlayView = View(this)
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Attached TYPE_ACCESSIBILITY_OVERLAY window successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach overlay window: ${e.message}")
        }

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
        if (event == null) return
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

        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (_: Exception) {}
    }
}
