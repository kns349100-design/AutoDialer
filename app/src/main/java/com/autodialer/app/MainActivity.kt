package com.autodialer.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NumberAdapter
    private val numberList = mutableListOf<String>()

    private var currentIndex = -1
    private var isAutoDialing = false
    private var wasOffHook = false

    private lateinit var telephonyManager: TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Delay after a call ends before dialing the next number (ms)
    private val NEXT_CALL_DELAY_MS = 2000L

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )

    private val permissionRequestCode = 101

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    wasOffHook = true
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (wasOffHook) {
                        wasOffHook = false
                        onCallEnded()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        adapter = NumberAdapter(numberList)
        binding.rvNumbers.layoutManager = LinearLayoutManager(this)
        binding.rvNumbers.adapter = adapter

        requestNeededPermissions()

        binding.btnLoadList.setOnClickListener {
            loadNumbersFromInput()
        }

        binding.btnStart.setOnClickListener {
            startAutoDialing()
        }

        binding.btnStop.setOnClickListener {
            stopAutoDialing()
        }
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun loadNumbersFromInput() {
        val raw = binding.etNumbers.text.toString()
        val parsed = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("[^0-9+]"), "") }
            .filter { it.isNotEmpty() }

        if (parsed.isEmpty()) {
            Toast.makeText(this, "Koi valid number nahi mila", Toast.LENGTH_SHORT).show()
            return
        }

        adapter.setNumbers(parsed)
        currentIndex = -1
        binding.tvStatus.text = "Status: ${parsed.size} numbers loaded"
    }

    private fun startAutoDialing() {
        if (numberList.isEmpty()) {
            Toast.makeText(this, "Pehle list load karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasCallPermission()) {
            Toast.makeText(this, "Call permission chahiye", Toast.LENGTH_SHORT).show()
            requestNeededPermissions()
            return
        }

        // Start listening for call state changes
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        isAutoDialing = true
        currentIndex = 0
        dialCurrentNumber()
    }

    private fun stopAutoDialing() {
        isAutoDialing = false
        mainHandler.removeCallbacksAndMessages(null)
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        binding.tvStatus.text = "Status: Stopped"
    }

    private fun dialCurrentNumber() {
        if (!isAutoDialing) return
        if (currentIndex < 0 || currentIndex >= numberList.size) {
            binding.tvStatus.text = "Status: List khatam ho gayi"
            stopAutoDialing()
            return
        }

        val number = numberList[currentIndex]
        adapter.currentIndex = currentIndex
        binding.tvStatus.text = "Status: Calling ${currentIndex + 1}/${numberList.size} -> $number"

        if (!hasCallPermission()) {
            Toast.makeText(this, "Call permission missing", Toast.LENGTH_SHORT).show()
            stopAutoDialing()
            return
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_CALL)
        intent.data = Uri.parse("tel:$number")
        startActivity(intent)
    }

    private fun onCallEnded() {
        if (!isAutoDialing) return

        currentIndex += 1
        binding.tvStatus.text = "Status: Call khatam, agla number ${NEXT_CALL_DELAY_MS / 1000}s me"

        mainHandler.postDelayed({
            dialCurrentNumber()
        }, NEXT_CALL_DELAY_MS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Call aur Phone State permission zaruri hai app chalne ke liye",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
