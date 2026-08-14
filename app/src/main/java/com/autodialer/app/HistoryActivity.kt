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

        val calledLeads = leads.filter {
            it.status == CallSequencer.Status.COMPLETED || it.status == CallSequencer.Status.SKIPPED
        }

        val resume = calledLeads.count { it.outcome == OutcomeTag.RESUME }
        val no = calledLeads.count { it.outcome == OutcomeTag.NO }
        val positive = calledLeads.count { it.outcome == OutcomeTag.POSITIVE }
        val info = calledLeads.count { it.outcome == OutcomeTag.INFO }
        val untagged = calledLeads.count { it.outcome == null }

        binding.tvHistorySummary.text =
            "Total calls: ${calledLeads.size}   Resume: $resume   No: $no   Positive: $positive   Info: $info   Untagged: $untagged"

        val adapter = NumberAdapter(calledLeads.toMutableList())
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter
    }
}
