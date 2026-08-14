package com.autodialer.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity(), CallEngineListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NumberAdapter
    private lateinit var sessionStore: SessionStore
    private lateinit var engine: CallEngine

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )
    private val permissionRequestCode = 101

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) importNumbersFromFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        engine = CallEngine(this, sessionStore, this)

        adapter = NumberAdapter(mutableListOf())
        binding.rvNumbers.layoutManager = LinearLayoutManager(this)
        binding.rvNumbers.adapter = adapter

        requestNeededPermissions()

        binding.btnPasteClipboard.setOnClickListener { pasteFromClipboard() }
        binding.btnImportFile.setOnClickListener { openFilePicker() }
        binding.btnLoadList.setOnClickListener { loadNumbersFromInput() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { engine.pause() }
        binding.btnSkip.setOnClickListener { engine.skip() }
        binding.btnStop.setOnClickListener { engine.stop() }
        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // The 4 full-screen outcome boxes - tapping any one dials the next number instantly.
        binding.btnOutcomeResume.setOnClickListener { selectOutcomeAndHide(OutcomeTag.RESUME) }
        binding.btnOutcomeNo.setOnClickListener { selectOutcomeAndHide(OutcomeTag.NO) }
        binding.btnOutcomePositive.setOnClickListener { selectOutcomeAndHide(OutcomeTag.POSITIVE) }
        binding.btnOutcomeInfo.setOnClickListener { selectOutcomeAndHide(OutcomeTag.INFO) }
        applyGlowShadows()

        if (sessionStore.hasSavedSession()) {
            binding.tvResumeBanner.visibility = android.view.View.VISIBLE
            binding.tvResumeBanner.text = "Pichla session mila - tap karo restore karne ke liye"
            binding.tvResumeBanner.setOnClickListener {
                if (engine.restoreIfAvailable()) {
                    adapter.setLeads(engine.leads)
                    binding.etBatchTarget.setText(if (engine.batchTarget > 0) engine.batchTarget.toString() else "")
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

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/comma-separated-values", "text/csv"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun importNumbersFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                val content = reader.readText()
                val existing = binding.etNumbers.text.toString()
                val merged = if (existing.isBlank()) content else "$existing\n$content"
                binding.etNumbers.setText(merged)
                Toast.makeText(this, "File import ho gayi, ab 'List Load' dabao", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "File read nahi ho payi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadNumbersFromInput() {
        val raw = binding.etNumbers.text.toString()
        val rawLines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val parsedLeads = mutableListOf<Lead>()
        for (line in rawLines) {
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

        binding.tvDuplicateInfo.text = if (duplicates.isNotEmpty())
            "${duplicates.size} duplicate number(s) mile aur remove kar diye gaye." else ""

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
        val batchText = binding.etBatchTarget.text.toString().trim()
        engine.batchTarget = batchText.toIntOrNull() ?: 0
        engine.start()
    }

    private fun selectOutcomeAndHide(tag: OutcomeTag) {
        binding.overlayOutcome.visibility = android.view.View.GONE
        engine.selectOutcome(tag)
    }

    /** Adds a colored glow shadow to the 4 outcome boxes on Android 9+ (API 28+). */
    private fun applyGlowShadows() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val map = listOf(
                binding.btnOutcomeResume to android.graphics.Color.parseColor("#2E7CF6"),
                binding.btnOutcomeNo to android.graphics.Color.parseColor("#FF3B5C"),
                binding.btnOutcomePositive to android.graphics.Color.parseColor("#2ED47A"),
                binding.btnOutcomeInfo to android.graphics.Color.parseColor("#FFB020")
            )
            for ((view, color) in map) {
                view.outlineAmbientShadowColor = color
                view.outlineSpotShadowColor = color
            }
        }
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
        binding.tvSessionName.text = "Session: $total numbers loaded"
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

    override fun onCallEndedAwaitingOutcome(index: Int) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Call khatam - option chuno"
            binding.overlayOutcome.visibility = android.view.View.VISIBLE
        }
    }

    override fun onBatchComplete(callsDone: Int) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Batch complete ($callsDone calls). 'Start' dabao aage jane ke liye."
            Toast.makeText(this, "Target ke $callsDone calls ho gaye. Aage jaari rakhne ke liye Start dabao.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSessionPaused() {
        runOnUiThread { binding.tvStatus.text = "Status: Paused" }
    }

    override fun onSessionResumed() {
        runOnUiThread { binding.tvStatus.text = "Status: Resumed" }
    }

    override fun onSessionStopped() {
        runOnUiThread {
            binding.tvStatus.text = "Status: Stopped"
            binding.overlayOutcome.visibility = android.view.View.GONE
        }
    }

    override fun onSessionComplete() {
        runOnUiThread {
            binding.tvStatus.text = "Status: List khatam ho gayi"
            binding.tvCurrentLead.text = "No active call"
            binding.overlayOutcome.visibility = android.view.View.GONE
        }
    }

    override fun onEngineError(message: String) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Error - $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onLog(message: String) {
        // Minimal for this scope.
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
