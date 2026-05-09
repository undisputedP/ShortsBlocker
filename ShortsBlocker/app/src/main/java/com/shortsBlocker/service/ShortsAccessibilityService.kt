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
 * video distinction in the URL path that DNS never sees. The only way to
 * block Shorts specifically is to inspect the app's UI tree.
 *
 * Detection strategy: collect several independent signals in one tree
 * walk and trigger on any single signal. Compared to v1.0.5 this is
 * intentionally aggressive — false negatives (Shorts plays through)
 * are the bug we're fixing, and the package filter alone gives us
 * a reasonably tight scope (we only ever act inside YouTube's window).
 *
 * Debug state is exposed via static fields the UI reads, so users
 * without ADB can still see whether the service is receiving events
 * and what its detectors saw.
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

        // Substrings that, if present anywhere in a view ID's local part,
        // strongly suggest the Shorts UI is up.
        private val ID_FRAGMENTS = listOf("reel", "short")

        // Class-name fragments. YouTube can rename internal classes but
        // tends to keep "Reel" / "Shorts" in the package path because
        // their own crash analytics still differentiate them.
        private val CLASS_FRAGMENTS = listOf("reel", "shorts")

        // Vertical pagers / scrollers — a corroborating signal.
        private val SCROLLER_FRAGMENTS = listOf("viewpager2", "viewpager", "recyclerview")

        // ----- debug state visible to MainActivity ----------------------

        @Volatile var isRunning: Boolean = false; private set
        @Volatile var lastPackage: String = "(none yet)"; private set
        @Volatile var lastEventType: String = "(none yet)"; private set
        @Volatile var lastEventAtMs: Long = 0L; private set
        @Volatile var lastIdHits: Int = 0; private set
        @Volatile var lastClassHits: Int = 0; private set
        @Volatile var lastShortsTextHits: Int = 0; private set
        @Volatile var lastSawScroller: Boolean = false; private set
        @Volatile var lastTriggered: Boolean = false; private set
        @Volatile var totalDismissals: Int = 0; private set
    }

    private var lastDismissAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Permissive package filter: any package whose name contains
        // "youtube" — catches stock, Vanced, ReVanced, et al.
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
                        "ids=$lastIdHits cls=$lastClassHits shortsTxt=$lastShortsTextHits scroller=$lastSawScroller")
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
        var idHits = 0
        var classHits = 0
        var shortsTextHits = 0
        var sawScroller = false

        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES_PER_WALK) {
            val node = queue.removeFirst() ?: continue
            visited++

            // View ID
            val rawId = node.viewIdResourceName
            if (rawId != null) {
                val suffix = rawId.substringAfter("/", missingDelimiterValue = rawId).lowercase()
                if (suffix in STRONG_VIEW_ID_SUFFIXES) {
                    idHits += 2
                } else {
                    for (frag in ID_FRAGMENTS) {
                        if (suffix.contains(frag)) {
                            idHits += 1
                            break
                        }
                    }
                }
            }

            // Class name
            val cls = node.className?.toString()?.lowercase()
            if (cls != null) {
                for (frag in CLASS_FRAGMENTS) {
                    if (cls.contains(frag)) {
                        classHits += 1
                        break
                    }
                }
                for (frag in SCROLLER_FRAGMENTS) {
                    if (cls.endsWith(frag)) {
                        sawScroller = true
                        break
                    }
                }
            }

            // Content description / text
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains("shorts")) {
                shortsTextHits += 1
            }
            val txt = node.text?.toString()?.lowercase()
            if (txt != null && txt.contains("shorts")) {
                shortsTextHits += 1
            }

            // Children
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }

        // Cache for debug UI
        lastIdHits = idHits
        lastClassHits = classHits
        lastShortsTextHits = shortsTextHits
        lastSawScroller = sawScroller

        // Aggressive trigger rule: any one of these
        //   1) >= 1 idHit  (covers exact suffix match or any *reel/short* substring in IDs)
        //   2) >= 1 classHit (any view in the tree with class containing "Reel"/"Shorts")
        //   3) >= 2 shortsTextHits with a vertical scroller present
        //      (avoids the bottom-nav "Shorts" tab triggering on the YT home page)
        return idHits >= 1 ||
                classHits >= 1 ||
                (shortsTextHits >= 2 && sawScroller)
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
