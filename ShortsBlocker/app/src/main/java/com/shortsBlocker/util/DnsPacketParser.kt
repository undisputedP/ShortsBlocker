package com.shortsBlocker.util

object DnsPacketParser {

    /**
     * Extracts domain name from a raw IPv4/UDP/DNS packet.
     * Packet layout:
     *   [0..19]  = IPv4 header (20 bytes)
     *   [20..27] = UDP header (8 bytes)
     *   [28..39] = DNS header (12 bytes)
     *   [40..]   = DNS question section
     */
    fun extractDomain(packet: ByteArray): String? {
        return try {
            // Minimum size: IP(20) + UDP(8) + DNS header(12) + 1 byte
            if (packet.size < 41) return null

            // Check IP version (first 4 bits should be 4 for IPv4)
            val ipVersion = (packet[0].toInt() and 0xFF) shr 4
            if (ipVersion != 4) return null

            // Check protocol: 17 = UDP
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return null

            // Get IP header length (lower 4 bits of first byte * 4)
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4

            // DNS starts after IP + UDP headers
            val dnsOffset = ipHeaderLen + 8

            // DNS header: flags at offset +2, QR bit (MSB) = 0 means query
            if (packet.size <= dnsOffset + 12) return null
            val dnsFlags = ((packet[dnsOffset + 2].toInt() and 0xFF) shl 8) or
                    (packet[dnsOffset + 3].toInt() and 0xFF)
            val isQuery = (dnsFlags and 0x8000) == 0
            if (!isQuery) return null

            // Parse question section starting at dnsOffset + 12
            parseDnsName(packet, dnsOffset + 12)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDnsName(packet: ByteArray, startOffset: Int): String? {
        val labels = mutableListOf<String>()
        var offset = startOffset

        while (offset < packet.size) {
            val labelLen = packet[offset].toInt() and 0xFF
            if (labelLen == 0) break  // end of name
            if (offset + labelLen + 1 > packet.size) return null

            val label = String(packet, offset + 1, labelLen, Charsets.US_ASCII)
            labels.add(label)
            offset += labelLen + 1
        }

        if (labels.isEmpty()) return null
        return labels.joinToString(".")
    }

    /**
     * Build a DNS NXDOMAIN response for blocked domains
     */
    fun buildNxDomainResponse(queryPacket: ByteArray): ByteArray? {
        return try {
            if (queryPacket.size < 41) return null
            val response = queryPacket.copyOf()

            val ipHeaderLen = (queryPacket[0].toInt() and 0x0F) * 4
            val dnsOffset = ipHeaderLen + 8

            // Set QR=1 (response), RCODE=3 (NXDOMAIN)
            response[dnsOffset + 2] = (response[dnsOffset + 2].toInt() or 0x80).toByte()
            response[dnsOffset + 3] = (response[dnsOffset + 3].toInt() or 0x03).toByte()

            // Swap source/dest IP
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Swap source/dest UDP port
            val srcPort0 = response[ipHeaderLen]
            val srcPort1 = response[ipHeaderLen + 1]
            response[ipHeaderLen] = response[ipHeaderLen + 2]
            response[ipHeaderLen + 1] = response[ipHeaderLen + 3]
            response[ipHeaderLen + 2] = srcPort0
            response[ipHeaderLen + 3] = srcPort1

            response
        } catch (e: Exception) {
            null
        }
    }
}
