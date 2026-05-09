package com.shortsBlocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortsBlocker.data.StatsManager

/**
 * Detects when the YouTube app is showing the Shorts player and dismisses it
 * by issuing a back action.
 *
 * Why this exists: DNS-based blocking can't separate Shorts from regular
 * YouTube videos — both are served from www.youtube.com, with the Shorts
 * vs. video distinction in the URL path that DNS never sees. The only way
 * to block Shorts specifically (without breaking regular YouTube) is to
 * inspect the app's UI tree and act on it.
 *
 * Detection strategy — designed to survive YouTube version drift:
 *
 *   We collect several independent signals as we walk the accessibility
 *   tree once per relevant event. The decision rule is:
 *
 *     - A *strong* signal (a known Shorts-player view ID, or class name
 *       that matches `*Reel*` / `*Shorts*` patterns) triggers dismissal
 *       on its own.
 *     - *Weak* signals (the literal text "Shorts" appearing as a content
 *       description or label) only trigger when corroborated, since the
 *       Shorts tab button alone reads "Shorts" without the player being
 *       active. We require multiple shorts-text hits AND a vertical
 *       pager/recycler in the tree.
 *
 *   View IDs are obfuscated and shift between YouTube releases. Class
 *   names tend to survive longer because the package is internal to
 *   YouTube and aggressively flattening them risks breaking their own
 *   crash-reporting / analytics. So when IDs eventually break, the
 *   class-name signals usually keep working.
 *
 *   Throttled to one dismissal per ~600 ms so an event-storm chain from
 *   a single Shorts-state transition doesn't produce repeated back
 *   actions that exit YouTube entirely.
 */
class ShortsAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsA11y"
        const val YT_PACKAGE = "com.google.android.youtube"
        private const val MIN_DISMISS_INTERVAL_MS = 600L
        private const val MAX_NODES_PER_WALK = 800

        // STRONG view-ID hints. Each of these has at some point been the
        // resource id of the Shorts player container or its immediate
        // children. The list is intentionally a *suffix* match (we strip
        // the "<package>:id/" prefix before comparing) so we don't have
        // to maintain the package qualifier when YT ships namespaced
        // resource changes.
        //
        // These are checked exactly. The fuzzy "contains 'reel'/'short'"
        // pass below catches anything renamed.
        private val STRONG_VIEW_ID_SUFFIXES = setOf(
            "reel_recycler",
            "reel_player_page_container",
            "reel_player_underlay",
            "reel_player_underlay_view",
            "shorts_video_pager",
            "shorts_player",
            "shorts_player_view",
            "shorts_container_layout",
            "shorts_container",
            "watch_while_layout"
        )

        // Class-name fragments that suggest a Shorts player is in the
        // hierarchy. Case-insensitive substring match — survives most
        // obfuscation because YT's own internal class hierarchy still
        // distinguishes Reel/Shorts views.
        private val CLASS_NAME_FRAGMENTS = listOf("reel", "shorts")

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var lastDismissAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != YT_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastDismissAt < MIN_DISMISS_INTERVAL_MS) return

        val root = rootInActiveWindow ?: return
        try {
            if (isShortsVisible(root)) {
                Log.d(TAG, "Shorts UI detected — issuing GLOBAL_ACTION_BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastDismissAt = now
                StatsManager.recordBlock("youtube-shorts", "YouTube Shorts")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Detection error: ${e.message}")
        } finally {
            // Pre-API-33 leaks node info if not recycled. On API 33+ the
            // call is a no-op, so this is safe everywhere.
            try { root.recycle() } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun isShortsVisible(root: AccessibilityNodeInfo): Boolean {
        var strongMatches = 0
        var classMatches = 0
        var shortsTextHits = 0
        var sawScroller = false

        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES_PER_WALK) {
            val node = queue.removeFirst() ?: continue
            visited++

            // ---- View ID -------------------------------------------------
            val rawId = node.viewIdResourceName
            if (rawId != null) {
                val suffix = rawId.substringAfter("/", missingDelimiterValue = rawId).lowercase()
                if (suffix in STRONG_VIEW_ID_SUFFIXES) {
                    strongMatches += 2 // bias: known-good IDs count more
                } else if (suffix.contains("reel") || suffix.contains("short")) {
                    strongMatches += 1
                }
            }

            // ---- Class name ---------------------------------------------
            val cls = node.className?.toString()
            if (cls != null) {
                val low = cls.lowercase()
                for (frag in CLASS_NAME_FRAGMENTS) {
                    if (low.contains(frag)) {
                        classMatches++
                        break
                    }
                }
                // ViewPager / RecyclerView signals — corroborate weak
                // text-only matches. Vertical scrolling is how Shorts
                // chains videos.
                if (low.endsWith("viewpager") || low.endsWith("viewpager2") ||
                    low.endsWith("recyclerview")) {
                    sawScroller = true
                }
            }

            // ---- Content description / text -----------------------------
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains("shorts")) {
                shortsTextHits++
            }

            // Children
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        // Decision:
        //   - Any strong match (known view ID, or "reel"/"short" anywhere
        //     in a view ID) — definitely the Shorts player.
        //   - 2+ class matches (multiple Shorts-related classes appear
        //     in the tree only when the player is up, not when the tab
        //     button alone is visible).
        //   - 3+ "shorts" text hits AND a vertical pager — covers the
        //     case where IDs and classes have all been renamed but the
        //     accessibility-only labels still mention Shorts.
        val triggered = strongMatches >= 1 ||
                        classMatches >= 2 ||
                        (shortsTextHits >= 3 && sawScroller)

        if (triggered) {
            Log.d(TAG, "trigger=true strongMatches=$strongMatches classMatches=$classMatches shortsTextHits=$shortsTextHits sawScroller=$sawScroller visited=$visited")
        }
        return triggered
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        Log.d(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }
}
