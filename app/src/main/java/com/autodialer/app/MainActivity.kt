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
    private lateinit var numberDraftStore: NumberDraftStore

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

    /** Handles results reported back from the new SettingsActivity for the few items whose
     * dialogs need this Activity's own in-memory stores (see SettingsActivity's class doc). */
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                when (result.data?.getStringExtra(SettingsActivity.EXTRA_ACTION)) {
                    SettingsActivity.ACTION_EDIT_INFO_MESSAGE -> showEditInfoMessageDialog()
                    SettingsActivity.ACTION_CUSTOM_OUTCOME -> showManageOutcomesDialog()
                    SettingsActivity.ACTION_BATCH_LIMIT -> showBatchLimitDialog()
                }
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
        numberDraftStore = NumberDraftStore(this)
        subscriptionManager.ensureFirstLaunchRecorded()
        subscriptionManager.refreshStatusInBackground()

        // No free/paid time left (or never picked a plan at all) - send straight to the
        // plan-selection screen instead of showing the dialer. This is the single gate that
        // covers every path into this screen: right after login, and any time a running
        // trial/plan runs out while the app is closed or reopened later.
        if (!subscriptionManager.hasAccess()) {
            startActivity(Intent(this, SubscriptionActivity::class.java))
            finish()
            return
        }

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
        binding.btnSettings.setOnClickListener { settingsLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        binding.btnRemoveList.setOnClickListener { confirmRemoveList() }

        setLoadButtonActive(false)
        binding.etNumbers.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // List changed since the last Load - go back to light until user taps Load again.
                setLoadButtonActive(false)
                // Always mirror the box into durable storage - this is what survives the app
                // being killed while an image/file picker is open, and what late-arriving OCR
                // results are checked against so nothing silently disappears.
                numberDraftStore.setDraft(s?.toString() ?: "")
            }
        })

        // Restore whatever was in the box before (paste/OCR results not yet loaded into a
        // list) in case this Activity was recreated - e.g. process killed while picking images.
        val draft = numberDraftStore.getDraft()
        if (draft.isNotBlank() && binding.etNumbers.text.isBlank()) {
            binding.etNumbers.setText(draft)
        }

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
                // Exception: if the app was specifically waiting for a WhatsApp return when it
                // got killed, don't show the overlay again - just pick up exactly where it left
                // off (see onResume) instead of making the user redo the WhatsApp step.
                if (engine.isAwaitingWhatsAppReturn()) {
                    pendingInfoOutcome = true
                } else if (engine.pendingLead() != null) {
                    hideKeyboard()
                    binding.overlayOutcome.visibility = android.view.View.VISIBLE
                }
            }
        }

        maybePromptBatteryOptimization()
    }

    /** Shown once, the very first time - explains why this matters and offers the setting.
     * Skipped silently if already allowed, if declined once already, or if a list-in-progress
     * outcome overlay is showing (don't stack dialogs on top of that). */
    private fun maybePromptBatteryOptimization() {
        val prefs = getSharedPreferences("autodialer_battery_prompt", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        if (binding.overlayOutcome.visibility == android.view.View.VISIBLE) return
        prefs.edit().putBoolean("asked", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Keep your call list safe")
            .setMessage("Some phones close apps in the background to save battery, which can interrupt a call list in progress. Allow AutoDialer to run without restrictions so this never happens.")
            .setPositiveButton("Allow") { _, _ -> requestIgnoreBatteryOptimizations() }
            .setNegativeButton("Not now", null)
            .show()
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
     *
     * Decoding runs off the main thread and downsamples large images first - full-resolution
     * screenshots decoded on the UI thread is what was causing the repeated freezing/lag when
     * a few images were picked at once.
     */
    private fun extractNumbersFromImages(uris: List<Uri>) {
        Toast.makeText(this, "Processing ${uris.size} image(s)...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val allNumbers = linkedSetOf<String>()
        var failedCount = 0
        var remaining = uris.size

        Thread {
            uris.forEach { uri ->
                try {
                    val bitmap = decodeSampledBitmap(uri, maxDimension = 2048)
                    if (bitmap == null) {
                        synchronized(allNumbers) { failedCount++; remaining-- }
                        if (remaining == 0) onImagesProcessed(allNumbers, failedCount)
                        return@forEach
                    }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            synchronized(allNumbers) {
                                allNumbers.addAll(extractPhoneNumbers(visionText.text))
                                remaining--
                            }
                            if (remaining == 0) onImagesProcessed(allNumbers, failedCount)
                        }
                        .addOnFailureListener {
                            synchronized(allNumbers) { failedCount++; remaining-- }
                            if (remaining == 0) onImagesProcessed(allNumbers, failedCount)
                        }
                } catch (e: Exception) {
                    synchronized(allNumbers) { failedCount++; remaining-- }
                    if (remaining == 0) onImagesProcessed(allNumbers, failedCount)
                }
            }
        }.start()
    }

    /** Decodes a URI to a Bitmap capped at maxDimension on its longest side, entirely off the
     * main thread - avoids decoding several full-resolution screenshots synchronously, which is
     * what causes the app to freeze/lag when picking multiple images. */
    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): android.graphics.Bitmap? {
        return try {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            var sampleSize = 1
            val (w, h) = boundsOptions.outWidth to boundsOptions.outHeight
            if (w > 0 && h > 0) {
                while ((w / sampleSize) > maxDimension || (h / sampleSize) > maxDimension) {
                    sampleSize *= 2
                }
            }
            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Two-pass extraction so far fewer numbers get missed:
     *  1) Plain digit runs (10-13 digits) exactly as OCR read them - covers the common case
     *     of a number printed with no separators.
     *  2) Digits allowed to have spaces/hyphens/dots/brackets between them (spaced-out or
     *     STD-code-style numbers), which the old pattern also caught.
     * Both passes tolerate the OCR noise (bullets, pipes, extra spacing) that was making real
     * numbers slip through before.
     */
    private fun extractPhoneNumbers(text: String): Set<String> {
        val results = linkedSetOf<String>()

        // Pass 1: clean, unbroken digit runs.
        Regex("\\d{10,13}").findAll(text).forEach { match ->
            results.add(match.value)
        }

        // Pass 2: numbers with spacing/punctuation mixed in (e.g. "+91 90750-34748",
        // "90750 34748", "(+91) 9075034748").
        val spacedRegex = Regex("(\\+?\\d[\\d\\-\\s.()]{7,16}\\d)")
        spacedRegex.findAll(text).forEach { match ->
            val cleaned = match.value.replace(Regex("[\\s\\-.()]"), "")
            val digitsOnly = cleaned.replace("+", "")
            if (digitsOnly.length in 10..13 && digitsOnly.all { it.isDigit() }) {
                results.add(cleaned)
            }
        }

        return results
    }

    private fun onImagesProcessed(numbers: Set<String>, failedCount: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (numbers.isEmpty()) {
                val msg = if (failedCount > 0)
                    "Couldn't read $failedCount image(s) - try a plain screenshot instead of a cropped/rotated photo"
                else "No numbers found in the image(s)"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val existing = binding.etNumbers.text.toString()
            val newText = numbers.joinToString("\n")
            val merged = if (existing.isBlank()) newText else "$existing\n$newText"
            binding.etNumbers.setText(merged)
            val summary = StringBuilder("${numbers.size} number(s) found (duplicates auto-removed)")
            if (failedCount > 0) summary.append(", $failedCount image(s) could not be read")
            summary.append(", now tap the ⬇ icon to load")
            Toast.makeText(this, summary.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun setLoadButtonActive(active: Boolean) {
        val drawableRes = if (active) R.drawable.bg_pill_load_active else R.drawable.bg_pill_load_idle
        binding.btnLoadListTop.background = androidx.core.content.ContextCompat.getDrawable(this, drawableRes)
    }

    /**
     * Asks the OS to stop applying battery-saving restrictions to this app. On phones with
     * aggressive background-app killers (common on Xiaomi/Vivo/Oppo/Realme), this is what
     * most often causes the app to get force-closed mid-list, making a loaded list look like
     * it "disappeared" even though it's safely saved - this removes the OS's main reason to
     * kill it in the first place, on top of the app already saving the list at every step.
     * (Kept here too since SettingsActivity has its own copy for its "Keep App Running" row,
     * so that screen doesn't need to round-trip back to MainActivity for it.)
     */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed to run in the background", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEMs block this screen - send them to the general battery settings instead.
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Couldn't open battery settings on this phone", Toast.LENGTH_SHORT).show()
            }
        }
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

    /**
     * Finds every phone-number-shaped token on one line of pasted/typed/imported text, using
     * the same robust two-pass regex already proven for OCR (see extractPhoneNumbers) - so
     * paste, file-import, and photo-import all get identical, strong detection instead of
     * three different (and differently buggy) parsing rules.
     *
     * Handles, correctly:
     *  - "Name, 9876543210" and "9876543210, Name" (name can be on either side)
     *  - CSV rows with extra columns in any order ("9876543210,Name,email@x.com")
     *  - A number that itself contains a hyphen ("9075-034748") - previously this got wrongly
     *    split in half at the hyphen and half the number was silently lost
     *  - More than one number on the same line - every one is kept, none dropped
     *  - A line with no name at all - gets an auto label ("Lead 7") so every single lead
     *    always has a label, never blank
     */
    private fun extractLeadsFromLine(line: String, startIndex: Int): List<Lead> {
        val candidates = extractPhoneNumbers(line).toList()
        if (candidates.isEmpty()) return emptyList()

        var leftover = line
        candidates.forEach { leftover = leftover.replace(it, " ") }
        leftover = leftover.trim(' ', ',', '-', '|', '\t', ':', ';').trim()
        val label = leftover.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() && it.any { c -> c.isLetter() } }

        return candidates.mapIndexedNotNull { i, token ->
            val digitsOnly = token.filter { it.isDigit() }
            // Reject anything that can't possibly be a real phone number (a stray 4-digit
            // code, a partial OCR fragment, etc) - never load/dial something that isn't
            // actually a phone number.
            if (digitsOnly.length !in 10..13) return@mapIndexedNotNull null
            val finalLabel = when {
                !label.isNullOrBlank() && candidates.size == 1 -> label
                !label.isNullOrBlank() -> "$label ${i + 1}"
                else -> "Lead ${startIndex + i}"
            }
            Lead(finalLabel, token)
        }
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
        val unparsableLines = mutableListOf<String>()
        var runningIndex = 1
        for (line in rawLines) {
            val leadsFromLine = extractLeadsFromLine(line, runningIndex)
            if (leadsFromLine.isEmpty()) {
                unparsableLines.add(line)
            } else {
                parsedLeads.addAll(leadsFromLine)
                runningIndex += leadsFromLine.size
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
            val normalized = PhoneUtils.normalize(lead.phone)
            if (seen.contains(normalized)) {
                duplicates.add(lead.phone)
            } else {
                seen.add(normalized)
                deduped.add(lead)
            }
        }

        val warnings = mutableListOf<String>()
        if (duplicates.isNotEmpty()) warnings.add("${duplicates.size} duplicate number(s) found and removed.")
        if (unparsableLines.isNotEmpty()) warnings.add("${unparsableLines.size} line(s) had no valid phone number and were skipped.")

        // Permanent, all-time exclusion: once a number has ever been called through this app
        // (on any day, in any past list), it can never be loaded into a list again - this is
        // the hard guarantee that no number gets called twice, not just a recent-days window.
        val alreadyCalledPhones = callLogStore.allCalledPhones()
        val neverCalled = deduped.filter { !alreadyCalledPhones.contains(PhoneUtils.normalize(it.phone)) }
        val alreadyCalledCount = deduped.size - neverCalled.size
        if (alreadyCalledCount > 0) {
            warnings.add("$alreadyCalledCount number(s) already called before (ever) have been excluded.")
        }
        binding.tvDuplicateInfo.text = warnings.joinToString("\n")

        if (neverCalled.isEmpty()) {
            Toast.makeText(this, "All these numbers have already been called before", Toast.LENGTH_LONG).show()
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
                binding.etNumbers.setText("") // these numbers are now a committed list, not a draft
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
                engine.markAwaitingWhatsAppReturn()
                openWhatsAppWithMessage(lead.phone)
            } else {
                engine.selectOutcome(outcomeId)
            }
        } else {
            engine.selectOutcome(outcomeId)
        }
    }

    /** Opens WhatsApp Business directly on this number's chat with the info message pre-filled
     *  (editable, just needs Send). Falls back to regular WhatsApp only if Business isn't installed. */
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

        val packagesInPriorityOrder = listOf("com.whatsapp.w4b", "com.whatsapp")
        for (pkg in packagesInPriorityOrder) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage(pkg)
                startActivity(intent)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // try next package
            }
        }
        // Neither WhatsApp Business nor regular WhatsApp found under those package names -
        // let Android pick whatever handles wa.me links (last resort).
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e2: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp Business not found - install it to use this feature", Toast.LENGTH_SHORT).show()
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
        // Catches the case where OCR/file-import finished AFTER this Activity got recreated
        // (e.g. app was killed in the background while picking images) - the result was saved
        // straight to the draft store, so pull it in now instead of it looking "missing".
        val draft = numberDraftStore.getDraft()
        if (draft != binding.etNumbers.text.toString()) {
            binding.etNumbers.setText(draft)
        }
        if (pendingInfoOutcome && ::engine.isInitialized) {
            pendingInfoOutcome = false
            engine.selectOutcome(Outcome.INFO.id)
        }
        authManager.checkSessionInBackground {
            runOnUiThread {
                Toast.makeText(this, "Logged out - this account was used on another device", Toast.LENGTH_LONG).show()
                authManager.logout()
                if (::engine.isInitialized) engine.stop()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Guarded: if this Activity is being torn down before `engine` was ever set up (e.g.
        // it redirected straight to SubscriptionActivity because there's no active plan yet -
        // right after login, before engine = CallEngine(...) runs), engine.teardown() would
        // throw and crash the app every single time. This is exactly that safety check.
        if (::engine.isInitialized) engine.teardown()
    }

    override fun onPause() {
        super.onPause()
        // Final safety net: force a save the instant the app leaves the foreground (which
        // happens on every single call, since dialing switches to the Phone app) - so even in
        // the worst case, timing-wise, nothing is ever left un-saved.
        if (::engine.isInitialized) engine.persist()
    }
}
