package com.autodialer.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityCallLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallLogBinding
    private lateinit var store: CallLogStore
    private lateinit var adapter: CallLogAdapter
    private var days: List<String> = emptyList()
    private var selectedDay: String? = null

    private val storageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault())

    /** Turns a raw "yyyy-MM-dd" sheet key into a nicely formatted label, e.g. "18 Aug 2026, Tuesday". */
    private fun formatSheetName(rawDay: String): String {
        return try {
            val date = storageDateFormat.parse(rawDay) ?: return rawDay
            displayDateFormat.format(date)
        } catch (e: Exception) {
            rawDay
        }
    }

    // Maps each currently displayed (sorted) row back to its real index in storage,
    // so delete/collected actions always affect the correct record even after grouping.
    private var displayToStorageIndex: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CallLogStore(this)

        adapter = CallLogAdapter(
            mutableListOf(),
            OutcomeStore(this),
            onDelete = { position -> confirmDeleteRow(position) },
            onToggleCollected = { position -> toggleCollected(position) }
        )
        binding.rvCallLog.layoutManager = LinearLayoutManager(this)
        binding.rvCallLog.adapter = adapter

        binding.btnDeleteSheet.setOnClickListener { confirmDeleteSheet() }
        binding.btnExportCsv.setOnClickListener { exportCurrentDayCsv() }

        loadDays()
    }

    private fun loadDays() {
        // Today's calls live in the Dashboard (still in progress) - Sheets only holds
        // finished, past days, neatly organized one sheet per date.
        days = store.getDays().filter { it != store.todayKey() }.sortedDescending()
        if (days.isEmpty()) {
            binding.tvDaySummary.text = "No past sheets yet - today's calls are on the Dashboard"
            binding.rvCallLog.visibility = android.view.View.GONE
            binding.tvEmptyState.visibility = android.view.View.VISIBLE
            binding.spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("(no past sheets)"))
            return
        }

        val labels = days.map { formatSheetName(it) }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerDay.adapter = spinnerAdapter
        binding.spinnerDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedDay = days.getOrNull(position)
                loadEntriesForSelectedDay()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        selectedDay = days.first()
        loadEntriesForSelectedDay()
    }

    /** Resume first, then Positive, then the other defaults, then any custom outcomes, untagged last. */
    private fun outcomePriority(outcome: String?): Int = when (outcome) {
        Outcome.RESUME.id -> 0
        Outcome.POSITIVE.id -> 1
        Outcome.INFO.id -> 2
        Outcome.NO.id -> 3
        null -> 100
        else -> 50 // custom outcomes
    }

    private fun loadEntriesForSelectedDay() {
        val day = selectedDay ?: return
        val rawEntries = store.loadDay(day)

        // Keep each entry's real storage index attached while we sort for display,
        // so delete/collected taps still hit the correct underlying record.
        val indexed = rawEntries.withIndex().toList()
        val sorted = indexed.sortedWith(compareBy({ outcomePriority(it.value.outcome) }, { it.value.time }))

        displayToStorageIndex = sorted.map { it.index }
        adapter.setEntries(sorted.map { it.value })

        binding.tvDaySummary.text = "${rawEntries.size} calls on ${formatSheetName(day)}"
        binding.rvCallLog.visibility = if (rawEntries.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvEmptyState.visibility = if (rawEntries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toggleCollected(displayPosition: Int) {
        val day = selectedDay ?: return
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        store.toggleCollected(day, storageIndex)
        loadEntriesForSelectedDay()
    }

    private fun exportCurrentDayCsv() {
        val day = selectedDay ?: return
        val entries = store.loadDay(day)
        if (entries.isEmpty()) {
            Toast.makeText(this, "No data to export for this day", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = StringBuilder()
            csv.append("Time,Name,Phone,Status,Outcome,Collected\n")
            entries.forEach { e ->
                val name = (e.name ?: "").replace(",", " ")
                val outcome = e.outcome ?: ""
                csv.append("${e.time},$name,${e.phone},${e.status},$outcome,${e.collected}\n")
            }

            val file = java.io.File(cacheDir, "call_log_${day}.csv")
            file.writeText(csv.toString())

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share call log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteRow(displayPosition: Int) {
        val day = selectedDay ?: return
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this call record?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteEntry(day, storageIndex)
                loadEntriesForSelectedDay()
                Toast.makeText(this, "Record deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSheet() {
        val day = selectedDay ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete the whole sheet?")
            .setMessage("The entire call log for ${formatSheetName(day)} will be deleted. This action cannot be undone.")
            .setPositiveButton("Delete Sheet") { _, _ ->
                store.deleteDay(day)
                Toast.makeText(this, "Sheet deleted", Toast.LENGTH_SHORT).show()
                loadDays()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
