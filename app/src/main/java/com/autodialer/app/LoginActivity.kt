package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivityLoginBinding
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authStore: AuthStore
    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authStore = AuthStore(this)

        if (authStore.isLoggedIn()) {
            goToMain()
            return
        }

        binding.btnSendOtp.setOnClickListener { sendOtp() }
        binding.btnVerifyOtp.setOnClickListener { verifyOtp() }
    }

    private fun sendOtp() {
        val phone = binding.etPhone.text.toString().trim()
        if (phone.length != 10) {
            binding.tvLoginStatus.text = "Enter a 10-digit number"
            return
        }
        val fullNumber = "+91$phone"
        binding.tvLoginStatus.text = "Sending OTP..."

        try {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signIn(credential, fullNumber)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    binding.tvLoginStatus.text =
                        "Could not send OTP. Is Firebase setup complete? (see FIREBASE_SETUP.md)\n${e.message}"
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    storedVerificationId = verificationId
                    resendToken = token
                    binding.otpSection.visibility = android.view.View.VISIBLE
                    binding.tvLoginStatus.text = "OTP sent to $fullNumber"
                }
            }

            val options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(fullNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        } catch (e: Exception) {
            binding.tvLoginStatus.text = "Login service is not set up yet - follow FIREBASE_SETUP.md"
        }
    }

    private fun verifyOtp() {
        val otp = binding.etOtp.text.toString().trim()
        val verificationId = storedVerificationId
        if (verificationId == null) {
            binding.tvLoginStatus.text = "Request an OTP first"
            return
        }
        if (otp.length != 6) {
            binding.tvLoginStatus.text = "Enter the 6-digit OTP"
            return
        }
        val phone = "+91${binding.etPhone.text.toString().trim()}"
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, otp)
            signIn(credential, phone)
        } catch (e: Exception) {
            binding.tvLoginStatus.text = "Incorrect OTP, try again"
        }
    }

    private fun signIn(credential: PhoneAuthCredential, phone: String) {
        try {
            FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        authStore.setLoggedIn(phone)
                        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
                        goToMain()
                    } else {
                        binding.tvLoginStatus.text = "Login failed: ${task.exception?.message}"
                    }
                }
        } catch (e: Exception) {
            binding.tvLoginStatus.text = "Login service is not set up yet - follow FIREBASE_SETUP.md"
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
