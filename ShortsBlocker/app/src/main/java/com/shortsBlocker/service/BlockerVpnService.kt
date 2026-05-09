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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class BlockerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outputLock = Any()

    companion object {
        const val ACTION_START = "com.shortsBlocker.START_VPN"
        const val ACTION_STOP = "com.shortsBlocker.STOP_VPN"
        const val ACTION_STATUS = "com.shortsBlocker.VPN_STATUS"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "ShortsBlockerChannel"
        const val TAG = "BlockerVPN"

        // Tunnel-local DNS endpoint we advertise to the OS. Sits inside our
        // /24 so the system routes DNS queries here through the tunnel where
        // we can intercept them. Everything else flows over the real network
        // because we only add a /32 route for this address.
        private const val LOCAL_DNS_ADDRESS = "10.99.0.2"
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 3000

        private val UPSTREAM_DNS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

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
            // Only the tunnel-local DNS IP routes through the VPN. Non-DNS
            // traffic and DNS to other servers fall through to the real
            // network — we are not a general-purpose tunnel.
            .addRoute(LOCAL_DNS_ADDRESS, 32)
            .addDnsServer(LOCAL_DNS_ADDRESS)
            .setMtu(1500)
        try {
            vpnBuilder.addDisallowedApplication("com.android.vending")
        } catch (_: Exception) {
            // Play Store not present on this device — ignore.
        }

        vpnInterface = vpnBuilder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null — VPN not authorized?")
            stopSelf()
            return
        }

        isRunning = true
        isActive = true
        blockedToday = StatsManager.getTodayCount()

        broadcastStatus(true)

        serviceScope.launch {
            runPacketInterception()
        }

        Log.d(TAG, "VPN started. Intercepting DNS to $LOCAL_DNS_ADDRESS, forwarding allowed queries to ${UPSTREAM_DNS.joinToString()}")
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

                // Only DNS queries should reach us thanks to the narrow route,
                // but be defensive: anything that isn't a DNS query gets dropped.
                val domain = DnsPacketParser.extractDomain(packet) ?: continue

                if (BlockingRules.shouldBlock(domain)) {
                    val response = DnsPacketParser.buildNxDomainResponse(packet)
                    if (response != null) {
                        synchronized(outputLock) { output.write(response) }
                    }
                    blockedToday++
                    val platform = getPlatformForDomain(domain)
                    StatsManager.recordBlock(domain, platform)
                    if (blockedToday % 5 == 0) {
                        updateNotification()
                    }
                    Log.d(TAG, "BLOCKED: $domain ($platform)")
                } else {
                    // Forward to a real upstream resolver concurrently — the
                    // read loop should not block on the network round-trip,
                    // otherwise rapid DNS queries from the device queue up
                    // and feel like the network has stalled.
                    serviceScope.launch { forwardDns(packet, output) }
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Read error: ${e.message}")
            }
        }
    }

    private fun forwardDns(query: ByteArray, output: FileOutputStream) {
        for (upstream in UPSTREAM_DNS) {
            val socket = DatagramSocket()
            try {
                if (!protect(socket)) {
                    Log.w(TAG, "protect() returned false for upstream socket")
                    continue
                }
                socket.soTimeout = UPSTREAM_TIMEOUT_MS

                val ipHeaderLen = (query[0].toInt() and 0x0F) * 4
                val dnsPayload = query.copyOfRange(ipHeaderLen + 8, query.size)

                socket.send(
                    DatagramPacket(
                        dnsPayload, dnsPayload.size,
                        InetAddress.getByName(upstream), DNS_PORT
                    )
                )

                val recvBuf = ByteArray(4096)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPkt)

                val response = buildDnsResponsePacket(query, recvBuf, recvPkt.length) ?: continue
                synchronized(outputLock) { output.write(response) }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Upstream $upstream failed: ${e.message}")
            } finally {
                socket.close()
            }
        }
    }

    /**
     * Reuse the original IPv4 + UDP headers from the query, swap src/dst,
     * splice in the upstream DNS response payload, fix lengths and IP
     * checksum. UDP checksum is set to 0 (allowed for IPv4 per RFC 768).
     */
    private fun buildDnsResponsePacket(
        query: ByteArray,
        dnsResponse: ByteArray,
        dnsResponseLen: Int
    ): ByteArray? {
        return try {
            val ipHeaderLen = (query[0].toInt() and 0x0F) * 4
            val totalLen = ipHeaderLen + 8 + dnsResponseLen
            val out = ByteArray(totalLen)

            // IP + UDP headers verbatim, then DNS payload.
            System.arraycopy(query, 0, out, 0, ipHeaderLen + 8)
            System.arraycopy(dnsResponse, 0, out, ipHeaderLen + 8, dnsResponseLen)

            // IP total length.
            out[2] = ((totalLen ushr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()

            // Swap IP source/destination addresses.
            for (i in 0..3) {
                val tmp = out[12 + i]
                out[12 + i] = out[16 + i]
                out[16 + i] = tmp
            }

            // Swap UDP source/destination ports.
            val sp0 = out[ipHeaderLen]
            val sp1 = out[ipHeaderLen + 1]
            out[ipHeaderLen]     = out[ipHeaderLen + 2]
            out[ipHeaderLen + 1] = out[ipHeaderLen + 3]
            out[ipHeaderLen + 2] = sp0
            out[ipHeaderLen + 3] = sp1

            // UDP length.
            val udpLen = 8 + dnsResponseLen
            out[ipHeaderLen + 4] = ((udpLen ushr 8) and 0xFF).toByte()
            out[ipHeaderLen + 5] = (udpLen and 0xFF).toByte()

            // IP header checksum: zero out, then recompute.
            out[10] = 0
            out[11] = 0
            val ipCk = onesComplementChecksum(out, 0, ipHeaderLen)
            out[10] = ((ipCk ushr 8) and 0xFF).toByte()
            out[11] = (ipCk and 0xFF).toByte()

            // UDP checksum: 0 means "not computed" for IPv4.
            out[ipHeaderLen + 6] = 0
            out[ipHeaderLen + 7] = 0

            out
        } catch (e: Exception) {
            Log.w(TAG, "buildDnsResponsePacket failed: ${e.message}")
            null
        }
    }

    private fun onesComplementChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv().toInt() and 0xFFFF)
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
