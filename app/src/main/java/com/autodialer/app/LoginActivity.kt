package com.autodialer.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autodialer.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        if (authManager.isLoggedIn()) {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnForgotPin.setOnClickListener {
            binding.resetSection.visibility = android.view.View.VISIBLE
        }
        binding.btnResetPin.setOnClickListener { attemptResetPin() }
    }

    private fun attemptLogin() {
        val phone = binding.etPhone.text.toString().trim()
        val pin = binding.etPin.text.toString().trim()

        if (phone.length != 10) {
            binding.tvLoginStatus.text = "Enter a 10-digit number"
            return
        }
        if (pin.length != 4) {
            binding.tvLoginStatus.text = "Enter a 4-digit PIN"
            return
        }

        binding.tvLoginStatus.text = "Logging in..."
        val fullNumber = "+91$phone"
        authManager.login(fullNumber, pin) { success, _, message ->
            binding.tvLoginStatus.text = message
            if (success) {
                authManager.setLoggedIn(fullNumber)
                goToMain()
            }
        }
    }

    private fun attemptResetPin() {
        val phone = binding.etPhone.text.toString().trim()
        val newPin = binding.etNewPin.text.toString().trim()

        if (phone.length != 10) {
            binding.tvLoginStatus.text = "Enter your 10-digit number above first"
            return
        }
        if (newPin.length != 4) {
            binding.tvLoginStatus.text = "Enter a 4-digit new PIN"
            return
        }

        binding.tvLoginStatus.text = "Resetting PIN..."
        val fullNumber = "+91$phone"
        authManager.resetPin(fullNumber, newPin) { success, message ->
            binding.tvLoginStatus.text = message
            if (success) {
                binding.etNewPin.setText("")
                binding.etPin.setText(newPin)
                binding.resetSection.visibility = android.view.View.GONE
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
