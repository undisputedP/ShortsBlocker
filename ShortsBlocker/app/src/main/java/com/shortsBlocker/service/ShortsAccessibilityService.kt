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
 * v1.0.10 detection rules. Either fires the dismissal on its own:
 *
 *   1. STRONG view ID match: a node's view-ID suffix is in the curated
 *      STRONG_VIEW_ID_SUFFIXES list. The list is restricted to ids that
 *      only appear inside the full-screen player (every entry has
 *      `player` or `pager` in its name). Earlier versions also matched
 *      generic ids like `reel_recycler`, `shorts_container`, and
 *      `watch_while_layout`, but those appear on the Shorts shelf
 *      embedded in Subscriptions / "You" feeds and on regular video
 *      pages, so they back-pressed those screens away.
 *
 *   2. STRICT class match: a single class name contains BOTH a Shorts
 *      keyword (`shorts` / `reel`) AND `player`. This separates the
 *      full-screen Shorts player (e.g. `ReelPlayerView`) from the
 *      bottom-nav Shorts tab button (`LegacyShortsTabIndicatorView`)
 *      and from shelf items.
 *
 * Loose counts (any view ID containing "shorts"/"reel"; any class
 * containing "shorts" without a player keyword; 3+ "shorts" text hits +
 * scroller) are still computed and surfaced in the in-app debug card,
 * but no longer trigger on their own — they false-positived on the
 * "Your Shorts" section of the You tab and similar shelves.
 *
 * Event subscription: the service registers both window-state and
 * window-content events, but content events are processed only inside
 * a 5-second "sticky" window after each dismissal. State events fire
 * on screen transitions (opening Shorts, switching tabs); they're rare
 * and always processed. Content events fire at ~30 Hz inside YouTube
 * during scroll — outside sticky mode the handler returns immediately
 * (no tree walk) so frame rate is preserved. Inside sticky mode they
 * are throttled to MIN_CONTENT_WALK_INTERVAL_MS so we still catch the
 * scroll-to-next-Short case once the user is already in the player.
 */
class ShortsAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsA11y"
        private const val MIN_DISMISS_INTERVAL_MS = 600L
        // Throttle for tree walks during content-changed events. Only
        // consulted inside the sticky window — outside sticky we don't
        // walk on content events at all, see onAccessibilityEvent.
        private const val MIN_CONTENT_WALK_INTERVAL_MS = 250L
        // After each dismissal, keep processing content-changed events
        // for this long. Catches scroll-to-next-Short within the player
        // (which is a content change inside the same window, not a
        // state change). Outside this window content events are dropped
        // immediately to keep YouTube's normal scroll smooth.
        private const val STICKY_DURATION_MS = 5_000L
        private const val MAX_NODES_PER_WALK = 600

        // STRONG view-ID suffixes (matches "<package>:id/<suffix>"
        // exactly). All entries contain `player` or `pager` so they
        // only match inside the full-screen Shorts player. Generic
        // ids like `reel_recycler` / `shorts_container` /
        // `watch_while_layout` were removed in v1.0.10 because they
        // appear on Shorts shelves in Subscriptions / "You" feeds and
        // on regular video pages, false-positiving those screens.
        private val STRONG_VIEW_ID_SUFFIXES = setOf(
            "reel_player_page_container",
            "reel_player_underlay",
            "reel_player_underlay_view",
            "shorts_video_pager",
            "shorts_player",
            "shorts_player_view"
        )

        // Substrings that indicate Shorts content somewhere — used both for
        // the strict class rule and (for diagnostics only) the loose
        // counters surfaced in the debug card.
        private val SHORTS_FRAGMENTS = listOf("reel", "shorts")

        // Player keyword. The strict class rule fires only when a class
        // name contains a SHORTS_FRAGMENT *and* this in the same name —
        // narrows detection to the full-screen Shorts player, since
        // every relevant YouTube class has "Player" in its name there.
        //
        // Earlier versions also matched "container" / "recycler" /
        // "pager", but those words appear on the Shorts *shelf* embedded
        // in the Subscriptions and "You" feeds, which caused those tabs
        // to be back-pressed away as soon as they loaded. Restricting
        // to "player" alone keeps shelves out of the trigger set.
        private val PLAYER_CONTAINER_FRAGMENTS = listOf("player")

        // Vertical scroller class signatures. Used to populate the
        // `sawScroller` debug counter; no longer feeds the trigger.
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
    private var lastWalkAt: Long = 0L
    private var stickyUntilMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Permissive package filter: any package whose name contains
        // "youtube" — catches stock, Vanced, ReVanced, etc.
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("youtube", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        lastPackage = pkg
        lastEventType = AccessibilityEvent.eventTypeToString(event.eventType)
        lastEventAtMs = now

        // Recently dismissed → don't even consider another walk.
        if (now - lastDismissAt < MIN_DISMISS_INTERVAL_MS) return

        // State changes (rare, fire on screen transitions like opening
        // Shorts) are always processed. Content changes (very frequent
        // during scroll) are dropped immediately unless we're inside
        // the sticky window — i.e. we recently dismissed Shorts and
        // want to keep watching for the user landing back on the
        // player or scrolling to the next Short. Inside sticky we
        // additionally throttle to MIN_CONTENT_WALK_INTERVAL_MS.
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isStateChange) {
            if (now >= stickyUntilMs) return
            if (now - lastWalkAt < MIN_CONTENT_WALK_INTERVAL_MS) return
        }

        lastWalkAt = now

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
                stickyUntilMs = lastEventAtMs + STICKY_DURATION_MS
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

        // v1.0.10 trigger rule. The "shorts text + scroller" weak rule
        // from v1.0.7 was dropped because the "Your Shorts" section of
        // the You tab has 3+ Shorts thumbnails and lives inside a
        // recycler, so it false-positived and back-pressed that tab.
        return strongIdHits >= 1 || strictClassHits >= 1
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
