package com.autodialer.app

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CallLogAdapter(
    private val entries: MutableList<CallLogEntry>,
    private val outcomeStore: OutcomeStore,
    private val onDelete: (Int) -> Unit,
    private val onToggleCollected: (Int) -> Unit,
    private val onCall: (Int) -> Unit
) : RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.tvLogTime)
        val name: TextView = view.findViewById(R.id.tvLogName)
        val status: TextView = view.findViewById(R.id.tvLogStatus)
        val outcome: TextView = view.findViewById(R.id.tvLogOutcome)
        val collected: TextView = view.findViewById(R.id.tvCollected)
        val call: TextView = view.findViewById(R.id.tvCallRow)
        val delete: TextView = view.findViewById(R.id.tvDeleteRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.time.text = entry.time
        holder.name.text = if (entry.name.isNullOrBlank()) entry.phone else "${entry.name} — ${entry.phone}"
        holder.status.text = entry.status

        val tag = outcomeStore.findById(entry.outcome)
        if (tag != null) {
            holder.outcome.visibility = View.VISIBLE
            holder.outcome.text = tag.label
            holder.outcome.setTextColor(tag.textColor())
            val pill = GradientDrawable()
            pill.cornerRadius = 40f
            pill.setColor(tag.color())
            holder.outcome.background = pill
        } else {
            holder.outcome.visibility = View.GONE
        }

        // Manual "Collected" mark - only relevant for Resume-tagged entries, since that's
        // the outcome you'd be following up on via WhatsApp. Toggled by hand, not automatic.
        if (tag?.id == Outcome.RESUME.id) {
            holder.collected.visibility = View.VISIBLE
            if (entry.collected) {
                holder.collected.text = "✓ Collected"
                val bg = GradientDrawable()
                bg.cornerRadius = 40f
                bg.setColor(android.graphics.Color.parseColor("#2ED47A"))
                holder.collected.background = bg
                holder.collected.setTextColor(android.graphics.Color.parseColor("#06341A"))
            } else {
                holder.collected.text = "Mark Collected"
                val bg = GradientDrawable()
                bg.cornerRadius = 40f
                bg.setColor(android.graphics.Color.parseColor("#2C2C2A"))
                holder.collected.background = bg
                holder.collected.setTextColor(android.graphics.Color.parseColor("#B4B2A9"))
            }
            holder.collected.setOnClickListener { onToggleCollected(position) }
        } else {
            holder.collected.visibility = View.GONE
        }

        holder.call.setOnClickListener { onCall(position) }
        holder.delete.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount(): Int = entries.size

    fun setEntries(newEntries: List<CallLogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
}
