package com.autodialer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivitySubscriptionBinding
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

class SubscriptionActivity : AppCompatActivity(), PaymentResultWithDataListener {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager
    private var pendingPlanType: String? = null
    private var pendingOrderId: String? = null
    private var pendingAmountRupees: Int = 0
    private var lastPaymentId: String? = null
    private var checkoutOpen = false
    private var confirming = false
    private val slowHintHandler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        subscriptionManager = SubscriptionManager(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()
        Checkout.preload(applicationContext)
        restorePendingPaymentIfAny()

        binding.btnFreeTrial.setOnClickListener {
            binding.btnFreeTrial.isEnabled = false
            val phone = AuthManager(this).phoneNumber()
            subscriptionManager.startFreeTrial(phone) { success, message ->
                binding.btnFreeTrial.isEnabled = true
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshUi()
                if (success) goToMainAfterDelay()
            }
        }
        binding.btnPay12Hour.setOnClickListener {
            startRazorpayPayment("HOURLY12", 10, "12 Hour Access")
        }
        binding.btnPayMonthly.setOnClickListener {
            startRazorpayPayment("MONTHLY", 300, "1 Month Access")
        }
        binding.btnPayYearly.setOnClickListener {
            startRazorpayPayment("YEARLY", 1000, "1 Year Access")
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
            subscriptionManager.redeemCode(code) { success, message, planType ->
                cancelSlowHint()
                binding.btnRedeem.isEnabled = true
                binding.tvRedeemResult.text = message
                if (success) {
                    refreshUi()
                    if (!planType.isNullOrBlank()) {
                        showCongratulationsDialog(planType, null)
                    } else {
                        goToMainAfterDelay()
                    }
                }
            }
        }

        refreshUi()
    }

    /**
     * Creates a Razorpay order on the server, then opens Razorpay Checkout inside the app. On a
     * phone it lists the installed UPI apps (PhonePe / GPay / Paytm...) to pay with directly -
     * no QR code. Once paid, the license backend asks Razorpay itself whether the order is
     * really paid before activating the plan.
     */
    private fun startRazorpayPayment(planType: String, amountRupees: Int, planLabel: String) {
        if (checkoutOpen || confirming) return
        setPayButtonsEnabled(false)
        binding.tvPaymentResult.text = "Starting secure payment..."
        subscriptionManager.createRazorpayOrder(planType) { order, message ->
            if (order == null) {
                setPayButtonsEnabled(true)
                binding.tvPaymentResult.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@createRazorpayOrder
            }
            pendingPlanType = planType
            pendingAmountRupees = amountRupees
            pendingOrderId = order.orderId
            lastPaymentId = null
            // Persisted immediately so that if Android kills this screen while the user is in
            // their UPI app, coming back still finds and confirms the payment.
            subscriptionManager.savePendingPayment(planType, amountRupees, System.currentTimeMillis(), order.orderId)
            openCheckout(order, planLabel)
        }
    }

    private fun openCheckout(order: SubscriptionManager.RazorpayOrder, planLabel: String) {
        try {
            val checkout = Checkout()
            checkout.setKeyID(order.keyId)
            val phone = AuthManager(this).phoneNumber().filter { it.isDigit() }.takeLast(10)
            val options = JSONObject()
            options.put("name", "CallConnect")
            options.put("description", planLabel)
            options.put("order_id", order.orderId)
            options.put("currency", "INR")
            options.put("amount", order.amountPaise)
            if (phone.length == 10) {
                options.put("prefill", JSONObject().put("contact", phone))
            }
            options.put("theme", JSONObject().put("color", "#1A56DB"))
            checkoutOpen = true
            binding.tvPaymentResult.text = "Complete the payment in the payment screen."
            binding.btnCheckPayment.visibility = android.view.View.VISIBLE
            checkout.open(this, options)
        } catch (e: Exception) {
            checkoutOpen = false
            setPayButtonsEnabled(true)
            Toast.makeText(this, "Could not open payment screen", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        checkoutOpen = false
        lastPaymentId = razorpayPaymentId
        confirmPendingPayment(quiet = false)
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        checkoutOpen = false
        setPayButtonsEnabled(true)
        binding.tvPaymentResult.text = "Payment cancelled or failed. If money was deducted, it will be confirmed automatically - or use the WhatsApp button below."
        binding.btnCheckPayment.visibility = android.view.View.VISIBLE
    }

    /** Asks the backend to confirm the pending order with Razorpay and activates the plan.
     * quiet = true is used for silent recovery when the screen is reopened - it never shows an
     * error for an order that simply was never paid. Safe to repeat: the server grants an order
     * only once. */
    private fun confirmPendingPayment(quiet: Boolean) {
        val orderId = pendingOrderId ?: return
        if (confirming) return
        confirming = true
        if (!quiet) {
            binding.tvPaymentResult.text = "Confirming your payment..."
            startSlowHint(binding.tvPaymentResult, "Still confirming - the server can take a few extra seconds, hang on...")
        }
        subscriptionManager.confirmRazorpayPayment(orderId) { success, planType, message ->
            confirming = false
            cancelSlowHint()
            setPayButtonsEnabled(true)
            if (success && !planType.isNullOrBlank()) {
                val amount = if (pendingAmountRupees > 0) pendingAmountRupees else null
                subscriptionManager.clearPendingPayment()
                pendingOrderId = null
                pendingPlanType = null
                binding.btnCheckPayment.visibility = android.view.View.GONE
                binding.tvPaymentResult.text = "Payment confirmed!"
                refreshUi()
                showCongratulationsDialog(planType, amount)
            } else {
                binding.tvPaymentResult.text = if (quiet) {
                    "Last payment not confirmed yet. If money was deducted, it activates automatically - or use the WhatsApp button below."
                } else {
                    "$message\nIf money was deducted, it will activate automatically - or use the WhatsApp button below."
                }
                binding.btnCheckPayment.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun setPayButtonsEnabled(enabled: Boolean) {
        binding.btnPay12Hour.isEnabled = enabled
        binding.btnPayMonthly.isEnabled = enabled
        binding.btnPayYearly.isEnabled = enabled
    }

    /** If a payment was started but never confirmed (screen killed while in the UPI app, or the
     * app was closed and reopened), pick it back up instead of silently losing track of it. */
    private fun restorePendingPaymentIfAny() {
        val pending = subscriptionManager.loadPendingPayment() ?: return
        if (!pending.reference.startsWith("order_")) {
            // Leftover from the old UPI/SMS flow - not a Razorpay order, nothing to confirm.
            subscriptionManager.clearPendingPayment()
            return
        }
        pendingPlanType = pending.planType
        pendingAmountRupees = pending.amountRupees
        pendingOrderId = pending.reference
        binding.tvPaymentResult.text = "Checking your last payment..."
        binding.btnCheckPayment.visibility = android.view.View.VISIBLE
    }

    /** Clear confirmation of exactly what was bought - plan name, price paid, and the exact
     * date/time it now runs out - so there's never any ambiguity about what a payment got. */
    private fun showCongratulationsDialog(planType: String, amountRupees: Int?) {
        val (planName, priceLabel) = subscriptionManager.planDisplayInfo(planType)
        val expiryLabel = subscriptionManager.expiryDateLabel()

        val dialogView = layoutInflater.inflate(R.layout.dialog_congrats, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsPlanName).text = planName
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsBadge).text = planType
        dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsExpiry).text = expiryLabel

        val amountView = dialogView.findViewById<android.widget.TextView>(R.id.tvCongratsAmount)
        if (amountRupees != null) {
            amountView.text = "₹$amountRupees paid • $priceLabel"
            amountView.visibility = android.view.View.VISIBLE
        } else {
            amountView.visibility = android.view.View.GONE
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.TextView>(R.id.btnCongratsStart).setOnClickListener {
            dialog.dismiss()
            goToMainAfterDelay(0)
        }
        dialog.show()
    }

    /** Opens WhatsApp to the admin's number with the payment/order IDs pre-filled, so a
     * payment that didn't auto-activate can be sorted out quickly. */
    private fun sendPaymentProofOnWhatsApp() {
        val message = "Hi, I've paid for CallConnect. Order: ${pendingOrderId ?: "-"}, Payment: ${lastPaymentId ?: "-"}. Please activate my plan."
        val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/919075034748?text=$encodedMessage")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_SHORT).show()
        }
    }

    /** After successfully activating any plan (free trial started, payment verified, or a
     * code redeemed) - go straight into the app instead of leaving the user sitting on the
     * plan screen. Short delay so they can actually see the confirmation message first. */
    private fun goToMainAfterDelay(delayMs: Long = 1200) {
        slowHintHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(android.content.Intent(this, MainActivity::class.java))
                finish()
            }
        }, delayMs)
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
        if (!checkoutOpen && !confirming && pendingOrderId != null) {
            confirmPendingPayment(quiet = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
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
