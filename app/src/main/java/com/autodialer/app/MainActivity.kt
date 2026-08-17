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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity(), CallEngineListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NumberAdapter
    private lateinit var sessionStore: SessionStore
    private lateinit var callLogStore: CallLogStore
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var outcomeStore: OutcomeStore
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

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val uris = mutableListOf<Uri>()
                val clipData = data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else {
                    data?.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) extractNumbersFromImages(uris)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        callLogStore = CallLogStore(this)
        subscriptionManager = SubscriptionManager(this)
        outcomeStore = OutcomeStore(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()
        engine = CallEngine(this, sessionStore, callLogStore, this)

        adapter = NumberAdapter(mutableListOf(), outcomeStore)
        binding.rvNumbers.layoutManager = LinearLayoutManager(this)
        binding.rvNumbers.adapter = adapter

        requestNeededPermissions()

        binding.btnPasteClipboard.setOnClickListener { pasteFromClipboard() }
        binding.btnImportFile.setOnClickListener { openFilePicker() }
        binding.btnImportImage.setOnClickListener { openImagePicker() }
        binding.btnLoadList.setOnClickListener { loadNumbersFromInput() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnPause.setOnClickListener { engine.pause() }
        binding.btnSkip.setOnClickListener { engine.skip() }
        binding.btnStop.setOnClickListener { engine.stop() }
        binding.btnFloatingStop.setOnClickListener { engine.stop() }
        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnSheets.setOnClickListener {
            startActivity(Intent(this, CallLogActivity::class.java))
        }
        binding.btnSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        binding.btnManageOutcomes.setOnClickListener { showManageOutcomesDialog() }

        // Outcome overlay buttons are built dynamically (see renderOutcomeOverlay) so any
        // number of default + custom options fit and adjust automatically.
        renderOutcomeOverlay()

        if (sessionStore.hasSavedSession()) {
            if (engine.restoreIfAvailable()) {
                adapter.setLeads(engine.leads)
                binding.etBatchTarget.setText(if (engine.batchTarget > 0) engine.batchTarget.toString() else "")
                refreshDashboard()
                binding.tvResumeBanner.visibility = android.view.View.VISIBLE
                binding.tvResumeBanner.text = "Previous list restored - tap 'Start' to continue calling it"
                binding.tvStatus.text = "Status: ${engine.leads.size} numbers restored from last session"
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
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "File imported, now tap 'Load List'", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, "Select image(s) with numbers"))
    }

    /**
     * Runs on-device OCR (ML Kit, free, works offline) on each selected image, extracts
     * phone-number-like sequences exactly as they appear, and merges the de-duplicated
     * set into the paste box. Numbers are kept exactly as recognized - nothing is invented.
     */
    private fun extractNumbersFromImages(uris: List<Uri>) {
        Toast.makeText(this, "Processing ${uris.size} image(s)...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val allNumbers = linkedSetOf<String>()
        var remaining = uris.size

        uris.forEach { uri ->
            try {
                val image = InputImage.fromFilePath(this, uri)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        allNumbers.addAll(extractPhoneNumbers(visionText.text))
                        remaining--
                        if (remaining == 0) onImagesProcessed(allNumbers)
                    }
                    .addOnFailureListener {
                        remaining--
                        if (remaining == 0) onImagesProcessed(allNumbers)
                    }
            } catch (e: Exception) {
                remaining--
                if (remaining == 0) onImagesProcessed(allNumbers)
            }
        }
    }

    private fun extractPhoneNumbers(text: String): Set<String> {
        val regex = Regex("(\\+?\\d[\\d\\-\\s]{8,14}\\d)")
        val results = linkedSetOf<String>()
        regex.findAll(text).forEach { match ->
            val cleaned = match.value.replace(Regex("[\\s-]"), "")
            val digitsOnly = cleaned.replace("+", "")
            if (digitsOnly.length in 10..13 && digitsOnly.all { it.isDigit() }) {
                results.add(cleaned)
            }
        }
        return results
    }

    private fun onImagesProcessed(numbers: Set<String>) {
        runOnUiThread {
            if (numbers.isEmpty()) {
                Toast.makeText(this, "No numbers found in the image(s)", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val existing = binding.etNumbers.text.toString()
            val newText = numbers.joinToString("\n")
            val merged = if (existing.isBlank()) newText else "$existing\n$newText"
            binding.etNumbers.setText(merged)
            Toast.makeText(this, "${numbers.size} number(s) found (duplicates auto-removed), now tap 'Load List'", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "No valid numbers found", Toast.LENGTH_SHORT).show()
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
            "${duplicates.size} duplicate number(s) found and removed." else ""

        val alreadyCalledPhones = callLogStore.allCalledPhones()
        val neverCalled = deduped.filter { !alreadyCalledPhones.contains(it.phone) }
        val alreadyCalledCount = deduped.size - neverCalled.size
        if (alreadyCalledCount > 0) {
            val existing = binding.tvDuplicateInfo.text.toString()
            val warning = "$alreadyCalledCount number(s) were already called before and have been excluded."
            binding.tvDuplicateInfo.text = if (existing.isEmpty()) warning else "$existing\n$warning"
        }

        if (neverCalled.isEmpty()) {
            Toast.makeText(this, "All these numbers have already been called before", Toast.LENGTH_LONG).show()
            return
        }

        engine.loadLeads(neverCalled, "Session ${System.currentTimeMillis()}")
        adapter.setLeads(engine.leads)
        refreshDashboard()
        binding.tvStatus.text = "Status: ${neverCalled.size} numbers loaded"
    }

    private fun onStartClicked() {
        hideKeyboard()
        if (engine.leads.isEmpty()) {
            Toast.makeText(this, "Load a list first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!binding.cbConsent.isChecked) {
            Toast.makeText(this, "Confirm the consent checkbox first", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = requiredPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            requestNeededPermissions()
            Toast.makeText(this, "Call permission is required", Toast.LENGTH_SHORT).show()
            return
        }
        val batchText = binding.etBatchTarget.text.toString().trim()
        engine.batchTarget = batchText.toIntOrNull() ?: 0
        engine.start()
    }

    private fun selectOutcomeAndHide(outcomeId: String) {
        binding.overlayOutcome.visibility = android.view.View.GONE
        engine.selectOutcome(outcomeId)
    }

    /**
     * Builds the outcome overlay buttons at runtime (2 per row) from whatever outcomes
     * currently exist (4 defaults + any custom ones the user added). This is what lets
     * the layout adjust automatically instead of being stuck at a fixed 4 boxes.
     */
    private fun renderOutcomeOverlay() {
        val container = binding.outcomeButtonsContainer
        container.removeAllViews()
        val outcomes = outcomeStore.allOutcomes()

        outcomes.chunked(2).forEach { rowOutcomes ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            rowOutcomes.forEachIndexed { i, outcome ->
                val button = android.widget.TextView(this).apply {
                    text = outcome.label.uppercase()
                    setTextColor(outcome.textColor())
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.cornerRadius = dp(18).toFloat()
                    bg.setColor(outcome.color())
                    background = bg
                    elevation = dp(16).toFloat()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        outlineAmbientShadowColor = outcome.color()
                        outlineSpotShadowColor = outcome.color()
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    ).apply {
                        if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                    }
                    setOnClickListener { selectOutcomeAndHide(outcome.id) }
                }
                row.addView(button)
            }
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showManageOutcomesDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manage_outcomes, null)
        val etLabel = dialogView.findViewById<android.widget.EditText>(R.id.etNewOutcomeLabel)
        val btnAdd = dialogView.findViewById<android.widget.TextView>(R.id.btnAddOutcome)
        val existingContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.existingOutcomesContainer)
        val btnClose = dialogView.findViewById<android.widget.TextView>(R.id.btnCloseManageOutcomes)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        fun refreshList() {
            existingContainer.removeAllViews()
            outcomeStore.customOutcomes().forEach { outcome ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                val badge = android.widget.TextView(this).apply {
                    text = outcome.label
                    setTextColor(outcome.textColor())
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.cornerRadius = dp(20).toFloat()
                    bg.setColor(outcome.color())
                    background = bg
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val remove = android.widget.TextView(this).apply {
                    text = "Remove"
                    setTextColor(android.graphics.Color.parseColor("#FF3B5C"))
                    textSize = 12f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        outcomeStore.deleteCustom(outcome.id)
                        refreshList()
                        renderOutcomeOverlay()
                    }
                }
                row.addView(badge)
                row.addView(remove)
                existingContainer.addView(row)
            }
        }
        refreshList()

        btnAdd.setOnClickListener {
            val label = etLabel.text.toString()
            val added = outcomeStore.addCustom(label)
            if (added != null) {
                etLabel.setText("")
                refreshList()
                renderOutcomeOverlay()
                Toast.makeText(this, "'${added.label}' added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enter a label", Toast.LENGTH_SHORT).show()
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun refreshDashboard() {
        val leads = engine.leads
        val total = leads.size
        val completed = leads.count { it.status == CallSequencer.Status.COMPLETED }
        val skipped = leads.count { it.status == CallSequencer.Status.SKIPPED }
        val pending = leads.count { it.status == CallSequencer.Status.PENDING }
        binding.tvProgress.text = "${completed + skipped} / $total"
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
            binding.btnFloatingStop.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCallEndedAwaitingOutcome(index: Int) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Call ended - choose an option"
            hideKeyboard()
            binding.overlayOutcome.visibility = android.view.View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { focusedView ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
        }
    }

    override fun onBatchComplete(callsDone: Int) {
        runOnUiThread {
            binding.tvStatus.text = "Status: Batch complete ($callsDone calls). Tap 'Start' to continue."
            binding.btnFloatingStop.visibility = android.view.View.GONE
            Toast.makeText(this, "Target of $callsDone calls reached. Tap Start to continue.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSessionPaused() {
        runOnUiThread { binding.tvStatus.text = "Status: Paused" }
    }

    override fun onSessionResumed() {
        runOnUiThread {
            binding.tvStatus.text = "Status: Resumed"
            binding.btnFloatingStop.visibility = android.view.View.VISIBLE
        }
    }

    override fun onSessionStopped() {
        runOnUiThread {
            binding.tvStatus.text = "Status: Stopped"
            binding.overlayOutcome.visibility = android.view.View.GONE
            binding.btnFloatingStop.visibility = android.view.View.GONE
        }
    }

    override fun onSessionComplete() {
        runOnUiThread {
            binding.tvStatus.text = "Status: List complete"
            binding.tvCurrentLead.text = "No active call"
            binding.overlayOutcome.visibility = android.view.View.GONE
            binding.btnFloatingStop.visibility = android.view.View.GONE
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
                    "Call and Phone State permission are required for the app to work",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        subscriptionManager.refreshStatusInBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.teardown()
    }
}
