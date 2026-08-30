package com.autodialer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autodialer.app.databinding.ActivitySubscriptionBinding

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var pendingPlanType: String? = null
    private var pendingPlanReference: String? = null
    private var pendingAmountRupees: Int = 0
    private var paymentStartedAt: Long = 0L
    private val slowHintHandler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null
    private var smsPollRunnable: Runnable? = null
    private val SMS_PERMISSION_REQUEST = 501

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
            startUpiPayment("HOURLY12", 10, "12 Hour Access")
        }
        binding.btnPayMonthly.setOnClickListener {
            startUpiPayment("MONTHLY", 300, "1 Month Access")
        }
        binding.btnPayYearly.setOnClickListener {
            startUpiPayment("YEARLY", 1000, "1 Year Access")
        }
        binding.btnCheckPayment.setOnClickListener {
            sendPaymentProofOnWhatsApp()
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
        requestSmsPermissionIfNeeded()
    }

    private fun requestSmsPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), SMS_PERMISSION_REQUEST)
        }
    }

    /**
     * Opens the user's UPI app (PhonePe/GPay/Paytm/etc) with the amount and a unique
     * reference note pre-filled, so payment happens instantly on the same phone - no
     * payment gateway, no browser, no whitelisting delay. As soon as the user comes back to
     * this screen, checkForAutoUnlock() starts looking for a matching bank/UPI credit SMS and
     * unlocks automatically the moment one arrives - no manual step needed. If no matching SMS
     * shows up (permission denied, unusual bank SMS format, delayed SMS), the WhatsApp
     * screenshot + redeem-code button further down stays available as a fallback.
     */
    private fun startUpiPayment(planType: String, amountRupees: Int, planLabel: String) {
        pendingPlanType = planType
        pendingAmountRupees = amountRupees
        paymentStartedAt = System.currentTimeMillis()
        val phone = AuthManager(this).phoneNumber().filter { it.isDigit() }.takeLast(10)
        val reference = "AD-$planType-$phone-${System.currentTimeMillis().toString().takeLast(5)}"
        pendingPlanReference = reference

        val uri = Uri.parse("upi://pay")
            .buildUpon()
            .appendQueryParameter("pa", SubscriptionManager.UPI_VPA)
            .appendQueryParameter("pn", SubscriptionManager.UPI_PAYEE_NAME)
            .appendQueryParameter("am", amountRupees.toString())
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "AutoDialer $planLabel $reference")
            .build()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            binding.tvPaymentResult.text =
                "Complete the payment, then come back here - it unlocks automatically as soon as we see the payment confirmation."
            binding.btnCheckPayment.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "No UPI app found on this phone (install PhonePe/GPay/Paytm)", Toast.LENGTH_LONG).show()
        }
    }

    /** Polls the SMS inbox every couple of seconds (while this screen is visible) for a
     * matching bank/UPI credit message. Stops automatically once found, once the user leaves
     * this screen, or if no payment is currently pending. */
    private fun checkForAutoUnlock() {
        smsPollRunnable?.let { slowHintHandler.removeCallbacks(it) }
        val planType = pendingPlanType ?: return
        if (paymentStartedAt == 0L) return
        if (subscriptionManager.isSubscribed()) return

        if (SmsPaymentVerifier.foundMatchingCreditSms(this, paymentStartedAt, pendingAmountRupees)) {
            subscriptionManager.grantPlanLocally(planType)
            binding.tvPaymentResult.text = "Payment confirmed automatically - plan activated!"
            binding.btnCheckPayment.visibility = android.view.View.GONE
            paymentStartedAt = 0L
            refreshUi()
            goToMainAfterDelay()
            return
        }

        val runnable = Runnable { checkForAutoUnlock() }
        smsPollRunnable = runnable
        slowHintHandler.postDelayed(runnable, 3000)
    }

    /** Opens WhatsApp to the admin's number with a pre-filled message including the payment
     * reference, so the user only needs to attach the screenshot and hit Send. */
    private fun sendPaymentProofOnWhatsApp() {
        val reference = pendingPlanReference ?: ""
        val message = "Hi, I've paid for AutoDialer. Reference: $reference. Sending payment screenshot now - please activate my code."
        val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/919075034748?text=$encodedMessage")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_SHORT).show()
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
        checkForAutoUnlock()
    }

    override fun onPause() {
        super.onPause()
        smsPollRunnable?.let { slowHintHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
        smsPollRunnable?.let { slowHintHandler.removeCallbacks(it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST) {
            checkForAutoUnlock()
        }
    }

    private fun refreshUi() {
        binding.tvPlanStatus.text = subscriptionManager.currentPlanLabel()
        binding.tvTrialInfo.text = when {
            subscriptionManager.isSubscribed() -> "Plan active — ${subscriptionManager.remainingTimeLabel()}"
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
