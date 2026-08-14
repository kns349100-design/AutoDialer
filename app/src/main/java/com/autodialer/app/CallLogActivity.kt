package com.autodialer.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityCallLogBinding

class CallLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallLogBinding
    private lateinit var store: CallLogStore
    private lateinit var adapter: CallLogAdapter
    private var days: List<String> = emptyList()
    private var selectedDay: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CallLogStore(this)

        adapter = CallLogAdapter(mutableListOf()) { position -> confirmDeleteRow(position) }
        binding.rvCallLog.layoutManager = LinearLayoutManager(this)
        binding.rvCallLog.adapter = adapter

        binding.btnDeleteSheet.setOnClickListener { confirmDeleteSheet() }
        binding.btnExportCsv.setOnClickListener { exportCurrentDayCsv() }

        loadDays()
    }

    private fun loadDays() {
        days = store.getDays()
        if (days.isEmpty()) {
            binding.tvDaySummary.text = "Koi call log abhi tak nahi hai"
            binding.rvCallLog.visibility = android.view.View.GONE
            binding.tvEmptyState.visibility = android.view.View.VISIBLE
            binding.spinnerDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("(koi din nahi)"))
            return
        }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, days)
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

    private fun loadEntriesForSelectedDay() {
        val day = selectedDay ?: return
        val entries = store.loadDay(day)
        adapter.setEntries(entries)
        binding.tvDaySummary.text = "${entries.size} calls on $day"
        binding.rvCallLog.visibility = if (entries.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvEmptyState.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun exportCurrentDayCsv() {
        val day = selectedDay ?: return
        val entries = store.loadDay(day)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Is din koi data nahi hai export karne ke liye", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = StringBuilder()
            csv.append("Time,Name,Phone,Status,Outcome\n")
            entries.forEach { e ->
                val name = (e.name ?: "").replace(",", " ")
                val outcome = e.outcome ?: ""
                csv.append("${e.time},$name,${e.phone},${e.status},$outcome\n")
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
            startActivity(android.content.Intent.createChooser(intent, "Call log share karo"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export fail hua: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteRow(position: Int) {
        val day = selectedDay ?: return
        AlertDialog.Builder(this)
            .setTitle("Ye call record delete karein?")
            .setMessage("Ye action wapas nahi ho sakta.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteEntry(day, position)
                loadEntriesForSelectedDay()
                Toast.makeText(this, "Record delete ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSheet() {
        val day = selectedDay ?: return
        AlertDialog.Builder(this)
            .setTitle("Poora sheet delete karein?")
            .setMessage("$day ka poora call log delete ho jayega. Ye action wapas nahi ho sakta.")
            .setPositiveButton("Delete Sheet") { _, _ ->
                store.deleteDay(day)
                Toast.makeText(this, "Sheet delete ho gayi", Toast.LENGTH_SHORT).show()
                loadDays()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
