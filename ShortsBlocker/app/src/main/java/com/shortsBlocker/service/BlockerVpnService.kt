package com.shortsBlocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shortsBlocker.data.StatsManager
import com.shortsBlocker.model.BlockingRules
import com.shortsBlocker.ui.MainActivity
import com.shortsBlocker.util.DnsPacketParser
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream

class BlockerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "com.shortsBlocker.START_VPN"
        const val ACTION_STOP = "com.shortsBlocker.STOP_VPN"
        const val ACTION_STATUS = "com.shortsBlocker.VPN_STATUS"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "ShortsBlockerChannel"
        const val TAG = "BlockerVPN"

        @Volatile
        var isActive = false

        @Volatile
        var blockedToday = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn() {
        if (isActive) return

        StatsManager.init(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val vpnBuilder = Builder()
            .setSession("ShortsBlocker")
            .addAddress("10.99.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addDisallowedApplication("com.android.vending") // Don't block Play Store
            .setMtu(1500)

        vpnInterface = vpnBuilder.establish()
        isRunning = true
        isActive = true
        blockedToday = StatsManager.getTodayCount()

        broadcastStatus(true)

        serviceScope.launch {
            runPacketInterception()
        }

        Log.d(TAG, "VPN started. Blocking ${BlockingRules.getTotalDomainCount()} domains across ${BlockingRules.getEnabledCount()} platforms")
    }

    private suspend fun runPacketInterception() = withContext(Dispatchers.IO) {
        val fd = vpnInterface ?: return@withContext
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOfRange(0, length)
                val domain = DnsPacketParser.extractDomain(packet)

                if (domain != null && BlockingRules.shouldBlock(domain)) {
                    // Build NXDOMAIN response to avoid app hanging
                    val response = DnsPacketParser.buildNxDomainResponse(packet)
                    if (response != null) {
                        output.write(response)
                    }
                    // Track stats
                    blockedToday++
                    val platform = getPlatformForDomain(domain)
                    StatsManager.recordBlock(domain, platform)

                    // Update notification periodically
                    if (blockedToday % 5 == 0) {
                        updateNotification()
                    }

                    Log.d(TAG, "BLOCKED: $domain ($platform)")
                } else {
                    // Allow packet through
                    output.write(packet, 0, length)
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Packet error: ${e.message}")
            }
        }
    }

    private fun getPlatformForDomain(domain: String): String {
        return BlockingRules.PLATFORMS.firstOrNull { platform ->
            platform.domains.any { blocked ->
                domain == blocked || domain.endsWith(".$blocked")
            }
        }?.name ?: "Custom"
    }

    private fun stopVpn() {
        isRunning = false
        isActive = false
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        broadcastStatus(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN stopped. Total blocked today: $blockedToday")
    }

    private fun broadcastStatus(active: Boolean) {
        val intent = Intent(ACTION_STATUS).putExtra("active", active)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ShortsBlocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN active - blocking distracting short videos"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BlockerVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚫 ShortsBlocker Active")
            .setContentText("Blocked $blockedToday distractions today")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
