package com.autodialer.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Dashboard" - shows every call made TODAY, straight from the call log, so it's always
 * accurate even after a list finishes and auto-clears mid-day (a new list can be loaded
 * right after, and this still shows all of today's calls combined). Once the day rolls
 * over, that date's calls move over to a proper sheet under "Sheets" instead.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: CallLogStore
    private lateinit var outcomeStore: OutcomeStore
    private lateinit var adapter: CallLogAdapter
    private var displayToStorageIndex: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CallLogStore(this)
        outcomeStore = OutcomeStore(this)

        adapter = CallLogAdapter(
            mutableListOf(),
            outcomeStore,
            onDelete = { position -> confirmDeleteRow(position) },
            onToggleCollected = { position -> toggleCollected(position) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        loadToday()
    }

    override fun onResume() {
        super.onResume()
        loadToday()
    }

    private fun loadToday() {
        val today = store.todayKey()
        val entries = store.loadDay(today)

        val completedOrSkipped = entries.filter { it.status == "Completed" || it.status == "Skipped" }
        val untagged = completedOrSkipped.count { it.outcome == null }
        val perOutcomeSummary = outcomeStore.allOutcomes()
            .map { o -> o.label to completedOrSkipped.count { it.outcome == o.id } }
            .filter { it.second > 0 }
            .joinToString("   ") { "${it.first}: ${it.second}" }

        val todayLabel = SimpleDateFormat("dd MMM yyyy, EEEE", Locale.getDefault()).format(Date())
        binding.tvHistorySummary.text =
            "Today - $todayLabel\nTotal calls: ${entries.size}   $perOutcomeSummary   Untagged: $untagged"

        // Most recent call first.
        val indexed = entries.withIndex().toList()
        val sorted = indexed.sortedByDescending { it.value.time }
        displayToStorageIndex = sorted.map { it.index }
        adapter.setEntries(sorted.map { it.value })
    }

    private fun toggleCollected(displayPosition: Int) {
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        store.toggleCollected(store.todayKey(), storageIndex)
        loadToday()
    }

    private fun confirmDeleteRow(displayPosition: Int) {
        val storageIndex = displayToStorageIndex.getOrNull(displayPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this call record?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteEntry(store.todayKey(), storageIndex)
                loadToday()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
