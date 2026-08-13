package com.autodialer.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NumberAdapter(private val leads: MutableList<Lead>) :
    RecyclerView.Adapter<NumberAdapter.ViewHolder>() {

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_number, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lead = leads[position]
        val label = if (lead.name.isNullOrBlank()) lead.phone else "${lead.name} — ${lead.phone}"
        val statusText = statusLabel(lead.status)
        holder.textView.text = "${position + 1}. $label   [$statusText]"
        holder.textView.setBackgroundColor(colorFor(lead.status))
    }

    override fun getItemCount(): Int = leads.size

    fun refreshRow(position: Int) {
        if (position in leads.indices) notifyItemChanged(position)
    }

    fun setLeads(newLeads: List<Lead>) {
        leads.clear()
        leads.addAll(newLeads)
        notifyDataSetChanged()
    }

    private fun statusLabel(status: CallSequencer.Status): String = when (status) {
        CallSequencer.Status.PENDING -> "Pending"
        CallSequencer.Status.CALLING -> "Calling"
        CallSequencer.Status.COMPLETED -> "Completed"
        CallSequencer.Status.SKIPPED -> "Skipped"
    }

    private fun colorFor(status: CallSequencer.Status): Int = when (status) {
        CallSequencer.Status.PENDING -> Color.WHITE
        CallSequencer.Status.CALLING -> Color.parseColor("#FFF3CD")
        CallSequencer.Status.COMPLETED -> Color.parseColor("#D4EDDA")
        CallSequencer.Status.SKIPPED -> Color.parseColor("#F1F1F1")
    }
}
