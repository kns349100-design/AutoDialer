package com.autodialer.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), CallEngineListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NumberAdapter
    private lateinit var sessionStore: SessionStore
    private lateinit var engine: CallEngine

    private val delayOptions = listOf(2, 3, 5, 10)

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )
    private val permissionRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        engine = CallEngine(this, sessionStore, this)

        adapter = NumberAdapter(mutableListOf())
        binding.rvNumbers.layoutManager = LinearLayoutManager(this)
        binding.rvNumbers.adapter = adapter

        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            delayOptions.map { "$it seconds" }
        )
        binding.spinnerDelay.adapter = spinnerAdapter
        binding.spinnerDelay.setSelection(0)

        requestNeededPermissions()

        binding.btnPasteClipboard.setOnClickListener { pasteFromClipboard() }
        binding.btnLoadList.setOnClickListener { loadNumbersFromInput() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { engine.pause() }
        binding.btnResume.setOnClickListener { engine.resume() }
        binding.btnSkip.setOnClickListener { engine.skip() }
        binding.btnStop.setOnClickListener { engine.stop() }

        if (sessionStore.hasSavedSession()) {
            binding.tvResumeBanner.visibility = android.view.View.VISIBLE
            binding.tvResumeBanner.text = "Pichla session mila - tap karo restore karne ke liye"
            binding.tvResumeBanner.setOnClickListener {
                if (engine.restoreIfAvailable()) {
                    adapter.setLeads(engine.leads)
                    binding.spinnerDelay.setSelection(delayOptions.indexOf(engine.delaySeconds).coerceAtLeast(0))
                    refreshDashboard()
                    binding.tvResumeBanner.visibility = android.view.View.GONE
                    Toast.makeText(this, "Session restore ho gaya. Manually 'Start' dabao aage badhne ke liye.", Toast.LENGTH_LONG).show()
                }
            }
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

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            binding.etNumbers.setText(text)
        } else {
            Toast.makeText(this, "Clipboard khali hai", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadNumbersFromInput() {
        val raw = binding.etNumbers.text.toString()
        val rawLines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val parsedLeads = mutableListOf<Lead>()
        for (line in rawLines) {
            // Support "Name, Number" or "Name - Number" or just "Number"
            val parts = line.split(",", "-").map { it.trim() }
            val (name, phonePart) = if (parts.size >= 2) {
                parts[0] to parts.last()
            } else {
                null to parts[0]
            }
            val cleanedPhone = phonePart.replace(Regex("[^0-9+]"), "")
            if (cleanedPhone.isNotEmpty()) {
                parsedLeads.add(Lead(name, cleanedPhone))
            }
        }

        if (parsedLeads.isEmpty()) {
            Toast.makeText(this, "Koi valid number nahi mila", Toast.LENGTH_SHORT).show()
            return
        }

        // Duplicate detection (by phone number)
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        val deduped = mutableListOf<Lead>()
        for (lead in parsedLeads) {
            if (seen.contains(lead.phone)) {
                duplicates.add(lead.phone)
            } else {
                seen.add(lead.phone)
                deduped.add(lead)
            }
        }

        if (duplicates.isNotEmpty()) {
            binding.tvDuplicateInfo.text =
                "${duplicates.size} duplicate number(s) mile aur remove kar diye gaye."
        } else {
            binding.tvDuplicateInfo.text = ""
        }

        val delaySeconds = delayOptions[binding.spinnerDelay.selectedItemPosition]
        engine.delaySeconds = delaySeconds
        engine.loadLeads(deduped, "Session ${System.currentTimeMillis()}")
        adapter.setLeads(engine.leads)
        refreshDashboard()
        binding.tvStatus.text = "Status: ${deduped.size} numbers loaded"
    }

    private fun onStartClicked() {
        if (engine.leads.isEmpty()) {
            Toast.makeText(this, "Pehle list load karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (!binding.cbConsent.isChecked) {
            Toast.makeText(this, "Pehle consent checkbox confirm karo", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = requiredPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            requestNeededPermissions()
            Toast.makeText(this, "Call permission chahiye", Toast.LENGTH_SHORT).show()
            return
        }
        engine.start()
    }

    private fun refreshDashboard() {
        val leads = engine.leads
        val total = leads.size
        val completed = leads.count { it.status == CallSequencer.Status.COMPLETED }
        val skipped = leads.count { it.status == CallSequencer.Status.SKIPPED }
        val pending = leads.count { it.status == CallSequencer.Status.PENDING }
        binding.tvProgress.text = "Progress: ${completed + skipped} / $total"
        binding.tvCounts.text = "Pending: $pending  Completed: $completed  Skipped: $skipped"
        binding.progressBar.max = if (total > 0) total else 1
        binding.progressBar.progress = completed + skipped
        binding.tvSessionName.text = "Session: ${total} numbers loaded"
    }

    // ---------- CallEngineListener ----------

    override fun onLeadUpdated(index: Int, status: CallSequencer.Status) {
        runOnUiThread {
            adapter.refreshRow(index)
            refreshDashboard()
        }
    }

    override fun onDialing(index: Int, lead: Lead) {
        runOnUiThread {
            val label = if (lead.name.isNullOrBlank()) lead.phone else "${lead.name}\n${lead.phone}"
            binding.tvCurrentLead.text = "Calling: $label"
            binding.tvStatus.text = "Status: Calling ${index + 1}/${engine.leads.size}"
        }
    }

    override fun onWaitingForNext(seconds: Int) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Call khatam, agla number ${seconds}s me"
        }
    }

    override fun onSessionPaused() {
        runOnUiThread { binding.tvStatus.text = "Status: Paused" }
    }

    override fun onSessionResumed() {
        runOnUiThread { binding.tvStatus.text = "Status: Resumed" }
    }

    override fun onSessionStopped() {
        runOnUiThread { binding.tvStatus.text = "Status: Stopped" }
    }

    override fun onSessionComplete() {
        runOnUiThread {
            binding.tvStatus.text = "Status: List khatam ho gayi"
            binding.tvCurrentLead.text = "No active call"
        }
    }

    override fun onEngineError(message: String) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Error - $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onLog(message: String) {
        // Kept minimal for this scope; a dedicated debug-log screen can be added later.
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
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
        engine.teardown()
    }
}
