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
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
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
                    if (BlockerVpnService.isActive) {
                        Toast.makeText(
                            this@MainActivity,
                            "Restart VPN to apply changes",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BlockerVpnService::class.java)
            .setAction(BlockerVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, BlockerVpnService::class.java)
            .setAction(BlockerVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun updateUI(active: Boolean) {
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
