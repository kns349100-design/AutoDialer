package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivityLoginBinding

/**
 * Login with phone number + OTP (sent by SMS from the backend). A correct OTP makes this device
 * the only active device for that number.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager
    private val handler = Handler(Looper.getMainLooper())
    private var slowHintRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        binding.btnSendOtp.setOnClickListener { requestOtp() }
        binding.btnResendOtp.setOnClickListener { requestOtp() }
        binding.btnVerifyOtp.setOnClickListener { verifyOtp() }
        binding.btnChangeNumber.setOnClickListener { backToPhoneEntry() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSlowHint()
        stopCountdown()
    }

    private fun fullPhone(): String? {
        val digits = binding.etPhone.text.toString().filter { it.isDigit() }
        if (digits.length != 10 || digits[0] !in '6'..'9') {
            binding.tvLoginStatus.text = "Enter a valid 10-digit mobile number"
            return null
        }
        return "+91$digits"
    }

    private fun requestOtp() {
        if (busy) return
        val phone = fullPhone() ?: return
        busy = true
        binding.btnSendOtp.isEnabled = false
        binding.btnResendOtp.isEnabled = false
        binding.tvLoginStatus.text = "Sending OTP..."
        startSlowHint("Still sending - the server can take a few extra seconds, hang on...")
        authManager.sendOtp(phone) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnSendOtp.isEnabled = true
            if (success) {
                showOtpStep()
                binding.tvLoginStatus.text = "OTP sent to $phone"
                startResendCountdown(60)
            } else {
                binding.tvLoginStatus.text = message
                // keep resend usable if we were already on the OTP step
                if (binding.otpSection.visibility == View.VISIBLE) binding.btnResendOtp.isEnabled = true
            }
        }
    }

    private fun verifyOtp() {
        if (busy) return
        val phone = fullPhone() ?: return
        val otp = binding.etOtp.text.toString().filter { it.isDigit() }
        if (otp.length != 6) {
            binding.tvLoginStatus.text = "Enter the 6-digit OTP"
            return
        }
        busy = true
        binding.btnVerifyOtp.isEnabled = false
        binding.tvLoginStatus.text = "Verifying..."
        startSlowHint("Still verifying - the server can take a few extra seconds, hang on...")
        authManager.verifyOtp(phone, otp) { success, message ->
            busy = false
            cancelSlowHint()
            binding.btnVerifyOtp.isEnabled = true
            if (success) {
                authManager.setLoggedIn(phone)
                goToMain()
            } else {
                binding.tvLoginStatus.text = message
            }
        }
    }

    private fun showOtpStep() {
        binding.otpSection.visibility = View.VISIBLE
        binding.btnSendOtp.visibility = View.GONE
        binding.etPhone.isEnabled = false
        binding.etOtp.requestFocus()
    }

    private fun backToPhoneEntry() {
        stopCountdown()
        binding.otpSection.visibility = View.GONE
        binding.btnSendOtp.visibility = View.VISIBLE
        binding.etPhone.isEnabled = true
        binding.etOtp.text?.clear()
        binding.tvLoginStatus.text = ""
    }

    private fun startResendCountdown(seconds: Int) {
        stopCountdown()
        var left = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (left > 0) {
                    binding.btnResendOtp.isEnabled = false
                    binding.btnResendOtp.alpha = 0.5f
                    binding.btnResendOtp.text = "Resend OTP in ${left}s"
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    binding.btnResendOtp.isEnabled = true
                    binding.btnResendOtp.alpha = 1f
                    binding.btnResendOtp.text = "Resend OTP"
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        binding.btnResendOtp.alpha = 1f
        binding.btnResendOtp.text = "Resend OTP"
    }

    /** Shows a reassuring message if the backend hasn't responded within a few seconds
     * (the free backend can be slow to "wake up" after being idle). */
    private fun startSlowHint(message: String) {
        cancelSlowHint()
        val runnable = Runnable { binding.tvLoginStatus.text = message }
        slowHintRunnable = runnable
        handler.postDelayed(runnable, 4000)
    }

    private fun cancelSlowHint() {
        slowHintRunnable?.let { handler.removeCallbacks(it) }
        slowHintRunnable = null
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
