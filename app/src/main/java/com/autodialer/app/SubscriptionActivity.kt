package com.autodialer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySubscriptionBinding

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var pendingPlanType: String? = null
    private var pendingPlanReference: String? = null
    private val slowHintHandler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()

        binding.btnFreeTrial.setOnClickListener {
            subscriptionManager.startFreeTrial()
            Toast.makeText(this, "Free trial started - 24 hours", Toast.LENGTH_SHORT).show()
            goToMainAfterDelay()
        }
        binding.btnPay12Hour.setOnClickListener {
            startCashfreePayment("HOURLY12")
        }
        binding.btnPayMonthly.setOnClickListener {
            startCashfreePayment("MONTHLY")
        }
        binding.btnPayYearly.setOnClickListener {
            startCashfreePayment("YEARLY")
        }
        binding.btnCheckPayment.setOnClickListener {
            checkPendingPayment()
        }

        binding.btnRedeem.setOnClickListener {
            val code = binding.etCode.text.toString()
            if (code.isBlank()) {
                binding.tvRedeemResult.text = "Enter a code"
                return@setOnClickListener
            }
            binding.btnRedeem.isEnabled = false
            binding.tvRedeemResult.text = "Checking..."
            startSlowHint(binding.tvRedeemResult, "Still checking - the server can take a few extra seconds, hang on...")
            subscriptionManager.redeemCode(code) { success, message ->
                cancelSlowHint()
                binding.btnRedeem.isEnabled = true
                binding.tvRedeemResult.text = message
                if (success) {
                    refreshUi()
                    goToMainAfterDelay()
                }
            }
        }

        refreshUi()
    }

    /**
     * Asks the backend to create a Cashfree Payment Link for this plan, then opens it in the
     * browser - user can pay via UPI/card/netbanking on Cashfree's own checkout page. There's
     * no automatic in-app callback from a browser payment, so after paying the user comes back
     * and taps "I've Paid" to trigger an automatic server-side check (see checkPendingPayment).
     */
    private fun startCashfreePayment(planType: String) {
        pendingPlanType = planType
        val phone = AuthManager(this).phoneNumber().filter { it.isDigit() }.takeLast(10)

        binding.tvPaymentResult.text = "Starting payment..."
        binding.btnCheckPayment.visibility = android.view.View.GONE

        subscriptionManager.createPaymentLink(planType, phone) { success, linkUrl, linkId, message ->
            if (success && linkUrl != null && linkId != null) {
                pendingPlanReference = linkId
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                    binding.tvPaymentResult.text =
                        "Complete the payment on the page that opens, then come back and tap \"I've Paid\" below."
                    binding.btnCheckPayment.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open payment page", Toast.LENGTH_LONG).show()
                }
            } else {
                binding.tvPaymentResult.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Called when the user taps "I've Paid" - asks the backend to check the payment link's
     * status directly with Cashfree and activate the plan automatically if it's paid. */
    private fun checkPendingPayment() {
        val linkId = pendingPlanReference
        val planType = pendingPlanType
        if (linkId == null || planType == null) return

        binding.btnCheckPayment.isEnabled = false
        binding.tvPaymentResult.text = "Checking payment..."
        startSlowHint(binding.tvPaymentResult, "Still checking - hang on a few more seconds...")
        subscriptionManager.checkPayment(linkId, planType) { success, message ->
            cancelSlowHint()
            binding.btnCheckPayment.isEnabled = true
            binding.tvPaymentResult.text = message
            if (success) {
                binding.btnCheckPayment.visibility = android.view.View.GONE
                refreshUi()
                goToMainAfterDelay()
            }
        }
    }

    /** After successfully activating any plan (free trial started, payment verified, or a
     * code redeemed) - go straight into the app instead of leaving the user sitting on the
     * plan screen. Short delay so they can actually see the confirmation message first. */
    private fun goToMainAfterDelay() {
        slowHintHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(android.content.Intent(this, MainActivity::class.java))
                finish()
            }
        }, 1200)
    }

    /** Shows a reassuring message if the backend hasn't responded within a few seconds
     * (the free backend can be genuinely slow to "wake up" after being idle). */
    private fun startSlowHint(target: android.widget.TextView, message: String) {
        cancelSlowHint()
        val runnable = Runnable { target.text = message }
        slowHintRunnable = runnable
        slowHintHandler.postDelayed(runnable, 4000)
    }

    private fun cancelSlowHint() {
        slowHintRunnable?.let { slowHintHandler.removeCallbacks(it) }
        slowHintRunnable = null
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
    }

    private fun refreshUi() {
        binding.tvPlanStatus.text = subscriptionManager.currentPlanLabel()
        binding.tvTrialInfo.text = when {
            subscriptionManager.isSubscribed() -> "Your plan is active"
            subscriptionManager.isTrialActive() -> {
                val hoursLeft = subscriptionManager.trialMillisRemaining() / (1000 * 60 * 60)
                "${hoursLeft}h left in trial"
            }
            subscriptionManager.hasStartedFreeTrial() -> "Trial expired - subscribe to continue"
            else -> "Pick a plan below to get started"
        }
        // The free trial is a one-time offer - hide it from the list once it's been used,
        // whether it's still running or already expired.
        binding.btnFreeTrial.visibility =
            if (subscriptionManager.hasStartedFreeTrial()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvDeviceId.text = "Device ID: ${subscriptionManager.deviceId()}"
    }
}
