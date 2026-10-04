package com.unlock.fingerprint.network

import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val syncManager = ClipboardSyncManager(applicationContext)
        syncManager.syncCurrentClipboard { success, msg ->
            val displayMsg = if (success) "✓ Copied to Laptop Clipboard!" else msg
            Toast.makeText(applicationContext, displayMsg, Toast.LENGTH_SHORT).show()
        }
    }
}
