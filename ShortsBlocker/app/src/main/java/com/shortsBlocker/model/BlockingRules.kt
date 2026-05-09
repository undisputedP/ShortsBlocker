package com.shortsBlocker.model

data class PlatformRule(
    val name: String,
    val emoji: String,
    val domains: List<String>,
    var isEnabled: Boolean = true
)

object BlockingRules {

    val PLATFORMS = listOf(
        // YouTube Shorts is intentionally NOT in this DNS platform list.
        // Shorts and regular videos share the www.youtube.com hostname,
        // so DNS can't distinguish them. ShortsBlocker handles YouTube
        // Shorts via ShortsAccessibilityService instead — see the
        // dedicated card in MainActivity.

        PlatformRule(
            name = "Instagram Reels",
            emoji = "📸",
            domains = listOf(
                "i.instagram.com",
                "edge-chat.instagram.com",
                "graph.instagram.com"
            )
        ),
        PlatformRule(
            name = "Facebook Reels",
            emoji = "👍",
            domains = listOf(
                "reels.facebook.com",
                "graph.facebook.com"
            )
        ),
        PlatformRule(
            name = "TikTok",
            emoji = "🎵",
            domains = listOf(
                "tiktok.com",
                "www.tiktok.com",
                "vm.tiktok.com",
                "api.tiktok.com",
                "log.tiktok.com",
                "api16-normal.tiktokv.com",
                "api22-normal.tiktokv.com",
                "api-normal.tiktokv.com",
                "mon.tiktokv.com",
                "analytics.tiktok.com"
            )
        ),
        PlatformRule(
            name = "Snapchat Stories",
            emoji = "👻",
            domains = listOf(
                "ads.snapchat.com",
                "feelinsonice-hrd.appspot.com",
                "sc-cdn.net"
            )
        ),
        PlatformRule(
            name = "Twitter/X Explore",
            emoji = "🐦",
            domains = listOf(
                "api.twitter.com",
                "abs.twimg.com"
            )
        )
    )

    // Custom user-defined domains
    val customDomains = mutableListOf<String>()

    fun shouldBlock(domain: String): Boolean {
        val domainLower = domain.lowercase().trim()
        val platformMatch = PLATFORMS
            .filter { it.isEnabled }
            .flatMap { it.domains }
            .any { blocked -> domainLower == blocked || domainLower.endsWith(".$blocked") }

        val customMatch = customDomains.any { blocked ->
            domainLower == blocked || domainLower.endsWith(".$blocked")
        }

        return platformMatch || customMatch
    }

    fun getEnabledCount() = PLATFORMS.count { it.isEnabled }
    fun getTotalDomainCount() = PLATFORMS.filter { it.isEnabled }.sumOf { it.domains.size } + customDomains.size
}
