package com.autodialer.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autodialer.app.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val store = SessionStore(this)
        val saved = store.load()
        val leads = saved?.leads ?: emptyList()

        val outcomeStore = OutcomeStore(this)
        val calledLeads = leads.filter {
            it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED
        }

        val untagged = calledLeads.count { it.outcome == null }
        val perOutcomeSummary = outcomeStore.allOutcomes()
            .map { o -> o.label to calledLeads.count { it.outcome == o.id } }
            .filter { it.second > 0 }
            .joinToString("   ") { "${it.first}: ${it.second}" }

        binding.tvHistorySummary.text =
            "Total calls: ${calledLeads.size}   $perOutcomeSummary   Untagged: $untagged"

        val adapter = NumberAdapter(calledLeads.toMutableList(), outcomeStore)
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }
}
