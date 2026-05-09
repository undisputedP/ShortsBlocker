package com.shortsBlocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortsBlocker.data.StatsManager

/**
 * Detects when the YouTube app is showing the Shorts player and dismisses it.
 *
 * Why this exists: DNS-based blocking can't separate Shorts from regular
 * YouTube videos — both are served from www.youtube.com, with the Shorts
 * vs. video distinction in the URL path that DNS never sees. The only way
 * to block Shorts specifically (without breaking regular YouTube) is to
 * inspect the app's UI tree and act on it.
 *
 * Detection strategy: probe a handful of view IDs that have appeared in
 * YouTube's Shorts UI across recent versions. View IDs are obfuscated and
 * change occasionally, so the list is intentionally broad. A back press
 * is enough to leave Shorts in every variant we've seen.
 *
 * The service throttles to one dismissal per ~600 ms so a chain of
 * accessibility events from the same Shorts state doesn't cause repeated
 * back presses that exit the app entirely.
 */
class ShortsAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsA11y"
        const val YT_PACKAGE = "com.google.android.youtube"
        private const val MIN_DISMISS_INTERVAL_MS = 600L

        // Across YouTube versions the Shorts player has appeared under
        // several view IDs. Match any of them.
        private val SHORTS_ID_HINTS = listOf(
            "reel_recycler",
            "reel_player_page_container",
            "reel_player_underlay",
            "shorts_video_pager",
            "shorts_player",
            "shorts_container_layout"
        )

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
                Log.d(TAG, "Shorts detected — dismissing via GLOBAL_ACTION_BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastDismissAt = now
                StatsManager.recordBlock("youtube-shorts", "YouTube Shorts")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Detection error: ${e.message}")
        }
    }

    private fun isShortsVisible(root: AccessibilityNodeInfo): Boolean {
        for (hint in SHORTS_ID_HINTS) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$YT_PACKAGE:id/$hint")
            if (!nodes.isNullOrEmpty()) {
                return true
            }
        }
        return false
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
