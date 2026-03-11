package com.shortsBlocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs: SharedPreferences = context.getSharedPreferences(
                "shortsBlocker_prefs", Context.MODE_PRIVATE
            )
            val wasActive = prefs.getBoolean("vpn_was_active", false)

            if (wasActive) {
                val vpnIntent = Intent(context, BlockerVpnService::class.java)
                    .setAction(BlockerVpnService.ACTION_START)
                ContextCompat.startForegroundService(context, vpnIntent)
            }
        }
    }
}
