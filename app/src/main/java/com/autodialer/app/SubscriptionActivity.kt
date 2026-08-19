package com.autodialer.app

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Checkout.preload(applicationContext)

        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()

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
            binding.tvRedeemResult.text = "Checking..."
            subscriptionManager.redeemCode(code) { success, message ->
                binding.tvRedeemResult.text = message
                if (success) refreshUi()
            }
        }

        refreshUi()
    }

    private fun startPayment(planType: String, amountPaise: Int, description: String) {
        if (SubscriptionManager.RAZORPAY_KEY_ID.startsWith("PASTE_")) {
            Toast.makeText(this, "Razorpay key is not set - follow RAZORPAY_SETUP.md", Toast.LENGTH_LONG).show()
            return
        }
        pendingPlanType = planType
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
            Toast.makeText(this, "Could not start payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val planType = pendingPlanType
        if (planType == null || razorpayPaymentId == null) return
        binding.tvPaymentResult.text = "Verifying payment..."
        subscriptionManager.verifyPayment(razorpayPaymentId, planType) { success, message ->
            binding.tvPaymentResult.text = message
            if (success) refreshUi()
        }
    }

    override fun onPaymentError(code: Int, description: String?) {
        binding.tvPaymentResult.text = "Payment cancelled or failed: $description"
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        binding.tvPlanStatus.text = subscriptionManager.currentPlanLabel()
        binding.tvTrialInfo.text = when {
            subscriptionManager.isSubscribed() -> "Your plan is active"
            subscriptionManager.isTrialActive() -> {
                val hoursLeft = subscriptionManager.trialMillisRemaining() / (1000 * 60 * 60)
                "${hoursLeft}h left in trial"
            }
            else -> "Trial expired - subscribe to continue"
        }
        binding.tvDeviceId.text = "Device ID: ${subscriptionManager.deviceId()}"
    }
}
