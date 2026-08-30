package com.autodialer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySettingsBinding

/**
 * Light, card-based settings screen (see activity_settings.xml) that replaced the old
 * PopupMenu. Simple navigation items (Sheets/Dashboard/Plan) are handled directly here.
 * Items that need MainActivity's own state (its dialogs read/write in-memory stores that
 * live on MainActivity) are reported back via setResult() + an action extra, and
 * MainActivity opens the actual dialog in onActivityResult - this avoids duplicating any
 * of that logic here or exposing MainActivity internals just for this screen.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "settings_action"
        const val ACTION_EDIT_INFO_MESSAGE = "edit_info_message"
        const val ACTION_CUSTOM_OUTCOME = "custom_outcome"
        const val ACTION_BATCH_LIMIT = "batch_limit"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupRow(binding.rowSheets, "📋", "Sheets", "Numbers grouped by upload") {
            startActivity(Intent(this, CallLogActivity::class.java))
        }
        setupRow(binding.rowDashboard, "📊", "Dashboard", "Call history and outcomes") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        setupRow(binding.rowPlan, "💳", "Plan / Subscription", SubscriptionManager(this).planSummaryForSettings()) {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        setupRow(binding.rowInfoMessage, "💬", "Edit Info Message", "Sent on WhatsApp for the Info outcome") {
            finishWithAction(ACTION_EDIT_INFO_MESSAGE)
        }
        setupRow(binding.rowCustomOutcome, "➕", "Custom Call Option", "Add your own outcome buttons") {
            finishWithAction(ACTION_CUSTOM_OUTCOME)
        }
        setupRow(binding.rowBatchLimit, "🔢", "Batch Limit", "Calls per session before pausing") {
            finishWithAction(ACTION_BATCH_LIMIT)
        }

        setupRow(binding.rowBattery, "🔋", "Keep App Running", "Stop the phone from closing this app mid-list") {
            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the live plan status every time this screen is shown (not just on first
        // create) - e.g. after coming back from actually buying/redeeming a plan.
        binding.rowPlan.rowSubtitle.text = SubscriptionManager(this).planSummaryForSettings()
    }

    private fun setupRow(
        row: com.autodialer.app.databinding.RowSettingsItemBinding,
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        row.rowIcon.text = icon
        row.rowTitle.text = title
        row.rowSubtitle.text = subtitle
        row.root.setOnClickListener { onClick() }
    }

    private fun finishWithAction(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    /** Self-contained (doesn't need any MainActivity state), so handled directly here rather
     * than round-tripping through onActivityResult like the dialog-based items above. */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed to run in the background", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Couldn't open battery settings on this phone", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
