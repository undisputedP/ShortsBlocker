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
 * YouTube videos — both come from www.youtube.com, with the Shorts vs
 * video distinction in the URL path that DNS never sees.
 *
 * v1.0.7 detection rules. Each fires the dismissal on its own:
 *
 *   1. STRONG view ID match: a node's view-ID suffix is in the curated
 *      STRONG_VIEW_ID_SUFFIXES list (the Shorts player container's known
 *      ids).
 *
 *   2. STRICT class match: a single class name contains BOTH a Shorts
 *      keyword (`shorts` / `reel`) AND a player-container keyword
 *      (`player` / `pager` / `recycler` / `container`). This separates
 *      the active Shorts player (e.g. `ReelPlayerView`) from the
 *      bottom-nav Shorts tab button (`LegacyShortsTabIndicatorView`),
 *      which only carries the first keyword. v1.0.6's looser rule
 *      ("any class contains shorts") fired on the tab button and
 *      closed YouTube on launch.
 *
 *   3. WEAK corroborated: 3+ "shorts" content-description / text hits
 *      AND a vertical pager / recycler in the tree.
 *
 * Looser counts (any view ID containing "shorts"/"reel"; any class
 * containing "shorts" without a player keyword) are still computed and
 * surfaced in the in-app debug card, but no longer trigger on their
 * own — they were the source of v1.0.6's false positives.
 */
class ShortsAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsA11y"
        private const val MIN_DISMISS_INTERVAL_MS = 600L
        private const val MAX_NODES_PER_WALK = 1200

        // STRONG view-ID suffixes (matches "<package>:id/<suffix>" exactly).
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

        // Substrings that indicate Shorts content somewhere — used both for
        // the strict class rule and (for diagnostics only) the loose
        // counters surfaced in the debug card.
        private val SHORTS_FRAGMENTS = listOf("reel", "shorts")

        // Player-container keywords. The strict class rule fires only when
        // a class name contains a SHORTS_FRAGMENT *and* one of these in
        // the same name — that distinguishes the player container from
        // the tab button or other Shorts-aware UI on home screen.
        private val PLAYER_CONTAINER_FRAGMENTS = listOf(
            "player", "pager", "recycler", "container"
        )

        // Vertical scroller class signatures, corroborates the weak
        // "shorts text" rule.
        private val SCROLLER_FRAGMENTS = listOf("viewpager2", "viewpager", "recyclerview")

        // ----- debug state visible to MainActivity ----------------------

        @Volatile var isRunning: Boolean = false; private set
        @Volatile var lastPackage: String = "(none yet)"; private set
        @Volatile var lastEventType: String = "(none yet)"; private set
        @Volatile var lastEventAtMs: Long = 0L; private set
        @Volatile var lastStrongIdHits: Int = 0; private set
        @Volatile var lastStrictClassHits: Int = 0; private set
        @Volatile var lastIdHits: Int = 0; private set        // loose, debug only
        @Volatile var lastClassHits: Int = 0; private set     // loose, debug only
        @Volatile var lastShortsTextHits: Int = 0; private set
        @Volatile var lastSawScroller: Boolean = false; private set
        @Volatile var lastTriggered: Boolean = false; private set
        @Volatile var totalDismissals: Int = 0; private set
    }

    private var lastDismissAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Permissive package filter: any package whose name contains
        // "youtube" — catches stock, Vanced, ReVanced, etc.
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("youtube", ignoreCase = true)) return

        lastPackage = pkg
        lastEventType = AccessibilityEvent.eventTypeToString(event.eventType)
        lastEventAtMs = System.currentTimeMillis()

        if (lastEventAtMs - lastDismissAt < MIN_DISMISS_INTERVAL_MS) return

        val root = rootInActiveWindow ?: return
        try {
            val triggered = isShortsVisible(root)
            lastTriggered = triggered
            if (triggered) {
                Log.d(TAG, "trigger pkg=$pkg event=$lastEventType " +
                        "strongIds=$lastStrongIdHits strictCls=$lastStrictClassHits " +
                        "ids=$lastIdHits cls=$lastClassHits shortsTxt=$lastShortsTextHits " +
                        "scroller=$lastSawScroller")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastDismissAt = lastEventAtMs
                totalDismissals++
                StatsManager.recordBlock("youtube-shorts", "YouTube Shorts")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Detection error: ${e.message}")
        } finally {
            try { root.recycle() } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun isShortsVisible(root: AccessibilityNodeInfo): Boolean {
        var strongIdHits = 0
        var strictClassHits = 0
        var looseIdHits = 0
        var looseClassHits = 0
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
                    strongIdHits++
                }
                // Loose substring presence — debug only, no longer triggers.
                if (SHORTS_FRAGMENTS.any { suffix.contains(it) }) {
                    looseIdHits++
                }
            }

            // ---- Class name ---------------------------------------------
            val cls = node.className?.toString()?.lowercase()
            if (cls != null) {
                val hasShortsKw = SHORTS_FRAGMENTS.any { cls.contains(it) }
                val hasPlayerKw = PLAYER_CONTAINER_FRAGMENTS.any { cls.contains(it) }
                if (hasShortsKw && hasPlayerKw) {
                    strictClassHits++
                }
                if (hasShortsKw) {
                    looseClassHits++
                }
                if (SCROLLER_FRAGMENTS.any { cls.endsWith(it) }) {
                    sawScroller = true
                }
            }

            // ---- Content description / text -----------------------------
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains("shorts")) shortsTextHits++
            val txt = node.text?.toString()?.lowercase()
            if (txt != null && txt.contains("shorts")) shortsTextHits++

            // Children
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        // Cache for debug UI
        lastStrongIdHits = strongIdHits
        lastStrictClassHits = strictClassHits
        lastIdHits = looseIdHits
        lastClassHits = looseClassHits
        lastShortsTextHits = shortsTextHits
        lastSawScroller = sawScroller

        // v1.0.7 trigger rule. Strict — does NOT fire on tab button alone.
        return strongIdHits >= 1 ||
                strictClassHits >= 1 ||
                (shortsTextHits >= 3 && sawScroller)
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
