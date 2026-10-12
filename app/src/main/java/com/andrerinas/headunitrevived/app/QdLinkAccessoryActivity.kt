package com.andrerinas.headunitrevived.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog

/**
 * Launched when a QDLink head unit puts this phone into USB accessory mode.
 * Being chosen here is what grants the accessory permission; the bridge then
 * opens the link itself, so all this does is make sure the service is running.
 */
class QdLinkAccessoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startQdLinkUsb(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        startQdLinkUsb(intent)
        finish()
    }

    private fun startQdLinkUsb(intent: Intent?) {
        AppLog.i("QDLink USB accessory intent: $intent")
        ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
            action = AapService.ACTION_QDLINK_USB
        })
    }
}
