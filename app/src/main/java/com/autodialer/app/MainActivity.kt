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
    private lateinit var infoMessageStore: InfoMessageStore
    private lateinit var authManager: AuthManager

    // True right after we've sent the user to WhatsApp for the INFO outcome - the next call is
    // dialed only once they actually come back to the app (onResume), i.e. after tapping Send.
    private var pendingInfoOutcome = false

    // Optional cap on how many calls to dial in one batch. Set via Settings > Batch Limit.
    // 0 = unlimited (dial the whole list).
    private var batchTargetPref = 0

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

        authManager = AuthManager(this)
        if (!authManager.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)
        callLogStore = CallLogStore(this)
        subscriptionManager = SubscriptionManager(this)
        outcomeStore = OutcomeStore(this)
        infoMessageStore = InfoMessageStore(this)
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
        binding.btnLoadListTop.setOnClickListener { loadNumbersFromInput() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { engine.stop() }
        binding.btnFloatingStop.setOnClickListener { engine.stop() }
        binding.btnSettings.setOnClickListener { showSettingsMenu() }
        binding.btnRemoveList.setOnClickListener { confirmRemoveList() }

        setLoadButtonActive(false)
        binding.etNumbers.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // List changed since the last Load - go back to light until user taps Load again.
                setLoadButtonActive(false)
            }
        })

        // Outcome overlay buttons are built dynamically (see renderOutcomeOverlay) so any
        // number of default + custom options fit and adjust automatically.
        renderOutcomeOverlay()

        if (sessionStore.hasSavedSession()) {
            if (engine.restoreIfAvailable()) {
                adapter.setLeads(engine.leads)
                refreshDashboard()
                Toast.makeText(this, "Previous list restored - tap 'Start' to continue calling it", Toast.LENGTH_LONG).show()
                // If a call had ended but its outcome was never tagged (Stop pressed, app
                // closed, crash, etc.), ask for it right away - don't wait for Start.
                if (engine.pendingLead() != null) {
                    hideKeyboard()
                    binding.overlayOutcome.visibility = android.view.View.VISIBLE
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
                Toast.makeText(this, "File imported, now tap the ⬇ icon to load", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "${numbers.size} number(s) found (duplicates auto-removed), now tap the ⬇ icon to load", Toast.LENGTH_LONG).show()
        }
    }

    private fun setLoadButtonActive(active: Boolean) {
        val drawableRes = if (active) R.drawable.bg_pill_load_active else R.drawable.bg_pill_load_idle
        binding.btnLoadListTop.background = androidx.core.content.ContextCompat.getDrawable(this, drawableRes)
    }

    /** Top-left gear icon: Sheets, Dashboard, Plan, Batch Limit, and editing the WhatsApp info message all live here. */
    private fun showSettingsMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnSettings)
        popup.menu.add(0, 1, 0, "Sheets")
        popup.menu.add(0, 2, 1, "Dashboard")
        popup.menu.add(0, 3, 2, "Edit Info Message (WhatsApp)")
        popup.menu.add(0, 4, 3, "Plan / Subscription")
        popup.menu.add(0, 5, 4, "+ Custom Call Option")
        popup.menu.add(0, 6, 5, "Batch Limit (calls per session)")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, CallLogActivity::class.java))
                2 -> startActivity(Intent(this, HistoryActivity::class.java))
                3 -> showEditInfoMessageDialog()
                4 -> startActivity(Intent(this, SubscriptionActivity::class.java))
                5 -> showManageOutcomesDialog()
                6 -> showBatchLimitDialog()
            }
            true
        }
        popup.show()
    }

    private fun showBatchLimitDialog() {
        val editText = android.widget.EditText(this)
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        editText.setText(if (batchTargetPref > 0) batchTargetPref.toString() else "")
        editText.hint = "0 = no limit (call the whole list)"
        editText.setPadding(48, 32, 48, 32)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Batch limit")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                batchTargetPref = editText.text.toString().trim().toIntOrNull() ?: 0
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadNumbersFromInput() {
        if (engine.hasActiveList()) {
            Toast.makeText(
                this,
                "Aaj ki list abhi khatam nahi hui hai. Pehle usse poora karo ya ✕ se hatao.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
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

        val alreadyCalledPhones = callLogStore.calledPhonesWithinDays(60)
        val neverCalled = deduped.filter { !alreadyCalledPhones.contains(it.phone) }
        val alreadyCalledCount = deduped.size - neverCalled.size
        if (alreadyCalledCount > 0) {
            val existing = binding.tvDuplicateInfo.text.toString()
            val warning = "$alreadyCalledCount number(s) called in the last 2 months have been excluded."
            binding.tvDuplicateInfo.text = if (existing.isEmpty()) warning else "$existing\n$warning"
        }

        if (neverCalled.isEmpty()) {
            Toast.makeText(this, "All these numbers were already called in the last 2 months", Toast.LENGTH_LONG).show()
            return
        }

        // Consent confirmation happens once right here at load time, instead of a checkbox
        // sitting on the main screen at all times.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm before loading")
            .setMessage("Are these ${neverCalled.size} numbers all consenting/authorized contacts?")
            .setPositiveButton("Yes, load list") { _, _ ->
                engine.loadLeads(neverCalled, "Session ${System.currentTimeMillis()}")
                adapter.setLeads(engine.leads)
                refreshDashboard()
                setLoadButtonActive(true)
                Toast.makeText(this, "${neverCalled.size} numbers loaded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onStartClicked() {
        hideKeyboard()
        if (!subscriptionManager.hasAccess()) {
            Toast.makeText(this, "Trial/subscription expired - activate a plan in Settings > Plan", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SubscriptionActivity::class.java))
            return
        }
        if (engine.leads.isEmpty()) {
            Toast.makeText(this, "Load a list first", Toast.LENGTH_SHORT).show()
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
        engine.batchTarget = batchTargetPref
        engine.start()
    }

    private fun confirmRemoveList() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Remove current list?")
            .setMessage("Numbers already called stay recorded in the sheet. Remaining un-called numbers in this list will be discarded.")
            .setPositiveButton("Remove") { _, _ ->
                engine.removeCurrentList()
                clearListUi()
                Toast.makeText(this, "List removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Resets the number-entry UI so a fresh list can be pasted/loaded. */
    private fun clearListUi() {
        binding.etNumbers.setText("")
        binding.tvDuplicateInfo.text = ""
        adapter.setLeads(engine.leads)
        binding.overlayOutcome.visibility = android.view.View.GONE
        binding.btnFloatingStop.visibility = android.view.View.GONE
        setLoadButtonActive(false)
        refreshDashboard()
    }

    private fun selectOutcomeAndHide(outcomeId: String) {
        binding.overlayOutcome.visibility = android.view.View.GONE
        if (outcomeId == Outcome.INFO.id) {
            // Don't dial the next number yet - open WhatsApp first and wait for the user to
            // actually tap Send and come back to the app (see onResume). Dialing immediately
            // here would fire the next call while WhatsApp is still on screen.
            val lead = engine.pendingLead()
            if (lead != null) {
                pendingInfoOutcome = true
                openWhatsAppWithMessage(lead.phone)
            } else {
                engine.selectOutcome(outcomeId)
            }
        } else {
            engine.selectOutcome(outcomeId)
        }
    }

    /** Opens WhatsApp directly on this number's chat with the info message pre-filled (editable, just needs Send). */
    private fun openWhatsAppWithMessage(phone: String) {
        val digits = phone.filter { it.isDigit() }
        val withCountryCode = when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.substring(1)}"
            else -> digits
        }
        val message = infoMessageStore.getMessage()
        val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://wa.me/$withCountryCode?text=$encodedMessage")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.whatsapp")
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // WhatsApp not installed under that package (e.g. WhatsApp Business) - fall back to generic view
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Editable from Settings > Edit Info Message. */
    private fun showEditInfoMessageDialog() {
        val editText = android.widget.EditText(this)
        editText.setText(infoMessageStore.getMessage())
        editText.setPadding(48, 32, 48, 32)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Info message (WhatsApp)")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                infoMessageStore.setMessage(editText.text.toString())
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    if (outcome.id == Outcome.INFO.id) {
                        setOnLongClickListener { showEditInfoMessageDialog(); true }
                    }
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

    /**
     * Drives all the visible state that depends on whether a list is loaded: the paste area
     * vs. the list-summary+numbers view, the bottom Start/Stop dock, and the count text.
     */
    private fun refreshDashboard() {
        val leads = engine.leads
        val total = leads.size
        val completed = leads.count { it.status == CallSequencer.Status.COMPLETED }
        val skipped = leads.count { it.status == CallSequencer.Status.SKIPPED }

        val hasList = engine.hasActiveList()
        binding.llListSummary.visibility = if (hasList) android.view.View.VISIBLE else android.view.View.GONE
        binding.llPasteArea.visibility = if (hasList) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvNumbersLabel.visibility = if (hasList) android.view.View.VISIBLE else android.view.View.GONE
        binding.dockControls.visibility = if (hasList) android.view.View.VISIBLE else android.view.View.GONE

        if (hasList) {
            binding.tvListSummaryCount.text = "$total numbers • ${completed + skipped} called"
        }
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
            val label = if (lead.name.isNullOrBlank()) lead.phone else "${lead.name} (${lead.phone})"
            binding.tvListSummaryCount.text = "Calling: $label  (${index + 1}/${engine.leads.size})"
            binding.btnFloatingStop.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCallEndedAwaitingOutcome(index: Int) {
        runOnUiThread {
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
            binding.btnFloatingStop.visibility = android.view.View.GONE
            Toast.makeText(this, "Target of $callsDone calls reached. Tap Start to continue.", Toast.LENGTH_LONG).show()
            refreshDashboard()
        }
    }

    override fun onSessionPaused() {
        runOnUiThread { refreshDashboard() }
    }

    override fun onSessionResumed() {
        runOnUiThread {
            binding.btnFloatingStop.visibility = android.view.View.VISIBLE
        }
    }

    override fun onSessionStopped() {
        runOnUiThread {
            binding.overlayOutcome.visibility = android.view.View.GONE
            binding.btnFloatingStop.visibility = android.view.View.GONE
            refreshDashboard()
        }
    }

    override fun onSessionComplete() {
        runOnUiThread {
            Toast.makeText(this, "List complete - all numbers moved to sheet", Toast.LENGTH_LONG).show()
            clearListUi()
        }
    }

    override fun onEngineError(message: String) {
        runOnUiThread {
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
        if (pendingInfoOutcome) {
            pendingInfoOutcome = false
            engine.selectOutcome(Outcome.INFO.id)
        }
        authManager.checkSessionInBackground {
            runOnUiThread {
                Toast.makeText(this, "Logged out - this account was used on another device", Toast.LENGTH_LONG).show()
                authManager.logout()
                engine.stop()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.teardown()
    }
}
