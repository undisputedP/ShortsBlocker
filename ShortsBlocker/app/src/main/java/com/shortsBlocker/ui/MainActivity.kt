package com.shortsBlocker.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shortsBlocker.R
import com.shortsBlocker.data.StatsManager
import com.shortsBlocker.model.BlockingRules
import com.shortsBlocker.service.BlockerVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvPlatformStats: TextView
    private lateinit var tvDomainCount: TextView
    private lateinit var llPlatforms: LinearLayout

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            showPending("Starting…")
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            updateUI(BlockerVpnService.isActive)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra("active", false) ?: false
            updateUI(active)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        StatsManager.init(applicationContext)
        bindViews()
        setupPlatformToggles()
        setupToggleButton()
        updateUI(BlockerVpnService.isActive)

        registerReceiver(
            statusReceiver,
            IntentFilter(BlockerVpnService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
    }

    private fun bindViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvTodayCount = findViewById(R.id.tvTodayCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        tvPlatformStats = findViewById(R.id.tvPlatformStats)
        tvDomainCount = findViewById(R.id.tvDomainCount)
        llPlatforms = findViewById(R.id.llPlatforms)
    }

    private fun setupPlatformToggles() {
        llPlatforms.removeAllViews()
        BlockingRules.PLATFORMS.forEach { platform ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(this).apply {
                text = "${platform.emoji} ${platform.name}"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val toggle = Switch(this).apply {
                isChecked = platform.isEnabled
                setOnCheckedChangeListener { _, checked ->
                    platform.isEnabled = checked
                    updateDomainCount()
                    // No toast here — toggling a platform takes effect on the
                    // next DNS query automatically. Spamming a toast on every
                    // tap added perceived UI lag without informing the user
                    // of anything actionable.
                }
            }

            row.addView(label)
            row.addView(toggle)
            llPlatforms.addView(row)
        }
        updateDomainCount()
    }

    private fun setupToggleButton() {
        btnToggle.setOnClickListener {
            if (BlockerVpnService.isActive) {
                showPending("Stopping…")
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // System will show a consent dialog; vpnLauncher handles the rest.
            vpnLauncher.launch(intent)
        } else {
            // Already authorized — go straight to the optimistic state.
            showPending("Starting…")
            startVpnService()
        }
    }

    /**
     * Immediately reflect that a toggle action is in flight so the user gets
     * visual feedback within a frame. The service round-trip
     * (VpnService.Builder.establish() etc.) can take 500ms–1.5s on real
     * devices; without this the button feels unresponsive.
     *
     * The receiver will snap us to the final state when the service
     * broadcasts. If for any reason that never happens (failed establish,
     * killed service), the safety post re-syncs from BlockerVpnService.isActive
     * after 5s so the UI doesn't get stuck in the pending state.
     */
    private fun showPending(label: String) {
        btnToggle.text = label
        btnToggle.isEnabled = false
        btnToggle.setBackgroundColor(getColor(android.R.color.darker_gray))
        tvStatus.text = "🟡 $label"
        btnToggle.postDelayed({
            if (!btnToggle.isEnabled) updateUI(BlockerVpnService.isActive)
        }, 5000)
    }

    private fun startVpnService() {
        getSharedPreferences("shortsBlocker_prefs", MODE_PRIVATE)
            .edit().putBoolean("vpn_was_active", true).apply()
        val intent = Intent(this, BlockerVpnService::class.java)
            .setAction(BlockerVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        getSharedPreferences("shortsBlocker_prefs", MODE_PRIVATE)
            .edit().putBoolean("vpn_was_active", false).apply()
        val intent = Intent(this, BlockerVpnService::class.java)
            .setAction(BlockerVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun updateUI(active: Boolean) {
        btnToggle.isEnabled = true
        if (active) {
            btnToggle.text = "⏹ Stop Blocking"
            btnToggle.setBackgroundColor(getColor(android.R.color.holo_red_light))
            tvStatus.text = "🟢 Active — Blocking distractions"
        } else {
            btnToggle.text = "▶ Start Blocking"
            btnToggle.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            tvStatus.text = "🔴 Inactive"
        }
        refreshStats()
    }

    private fun refreshStats() {
        tvTodayCount.text = "Today: ${StatsManager.getTodayCount()} blocked"
        tvTotalCount.text = "All time: ${StatsManager.getTotalCount()} blocked"

        val platformCounts = StatsManager.getPlatformCounts()
        if (platformCounts.isEmpty()) {
            tvPlatformStats.text = "No blocks recorded yet"
        } else {
            tvPlatformStats.text = platformCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString("\n") { (platform, count) -> "  $platform: $count" }
        }
    }

    private fun updateDomainCount() {
        tvDomainCount.text = "Blocking ${BlockingRules.getTotalDomainCount()} domains across ${BlockingRules.getEnabledCount()} platforms"
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        updateUI(BlockerVpnService.isActive)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
