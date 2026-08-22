package com.autodialer.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySubscriptionBinding
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONObject

class SubscriptionActivity : AppCompatActivity(), PaymentResultListener {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var pendingPlanType: String? = null
    private val slowHintHandler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Checkout.preload(applicationContext)

        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()

        binding.btnFreeTrial.setOnClickListener {
            subscriptionManager.startFreeTrial()
            Toast.makeText(this, "Free trial started - 24 hours", Toast.LENGTH_SHORT).show()
            goToMainAfterDelay()
        }
        binding.btnPay12Hour.setOnClickListener {
            startPayment("HOURLY12", SubscriptionManager.PRICE_HOURLY12_PAISE, "12 Hour Access")
        }
        binding.btnPayMonthly.setOnClickListener {
            startPayment("MONTHLY", SubscriptionManager.PRICE_MONTHLY_PAISE, "1 Month Access")
        }
        binding.btnPayYearly.setOnClickListener {
            startPayment("YEARLY", SubscriptionManager.PRICE_YEARLY_PAISE, "1 Year Access")
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

    private fun setPayButtonsEnabled(enabled: Boolean) {
        binding.btnPay12Hour.isEnabled = enabled
        binding.btnPayMonthly.isEnabled = enabled
        binding.btnPayYearly.isEnabled = enabled
    }

    private fun startPayment(planType: String, amountPaise: Int, description: String) {
        if (SubscriptionManager.RAZORPAY_KEY_ID.startsWith("PASTE_")) {
            Toast.makeText(this, "Razorpay key is not set - follow RAZORPAY_SETUP.md", Toast.LENGTH_LONG).show()
            return
        }
        pendingPlanType = planType
        setPayButtonsEnabled(false)
        val checkout = Checkout()
        checkout.setKeyID(SubscriptionManager.RAZORPAY_KEY_ID)
        try {
            val options = JSONObject()
            options.put("name", "AutoDialer")
            options.put("description", description)
            options.put("currency", "INR")
            options.put("amount", amountPaise)
            val prefill = JSONObject()
            val phone = AuthManager(this).phoneNumber()
            if (phone.isNotEmpty()) prefill.put("contact", phone)
            options.put("prefill", prefill)
            checkout.open(this, options)
        } catch (e: Exception) {
            setPayButtonsEnabled(true)
            Toast.makeText(this, "Could not start payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val planType = pendingPlanType
        if (planType == null || razorpayPaymentId == null) {
            setPayButtonsEnabled(true)
            return
        }
        binding.tvPaymentResult.text = "Verifying payment..."
        startSlowHint(binding.tvPaymentResult, "Still verifying - the server can take a few extra seconds, hang on...")
        subscriptionManager.verifyPayment(razorpayPaymentId, planType) { success, message ->
            cancelSlowHint()
            setPayButtonsEnabled(true)
            binding.tvPaymentResult.text = message
            if (success) {
                refreshUi()
                goToMainAfterDelay()
            }
        }
    }

    override fun onPaymentError(code: Int, description: String?) {
        setPayButtonsEnabled(true)
        binding.tvPaymentResult.text = "Payment cancelled or failed: $description"
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
