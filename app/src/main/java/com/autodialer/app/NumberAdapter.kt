package com.autodialer.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NumberAdapter(private val leads: MutableList<Lead>) :
    RecyclerView.Adapter<NumberAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val label: TextView = view.findViewById(R.id.tvNumberLabel)
        val badge: TextView = view.findViewById(R.id.tvBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_number, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lead = leads[position]
        val displayName = lead.name?.takeIf { it.isNotBlank() } ?: lead.phone
        val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        holder.avatar.text = initial

        val label = if (lead.name.isNullOrBlank()) lead.phone else "${lead.name} — ${lead.phone}"
        holder.label.text = "${position + 1}. $label  [${statusLabel(lead.status)}]"
        holder.label.setTextColor(
            if (lead.status == CallSequencer.Status.PENDING) Color.parseColor("#5F5E5A")
            else Color.parseColor("#D3D1C7")
        )

        val outcome = lead.outcome
        if (outcome != null) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = outcome.shortTag
            holder.badge.setTextColor(outcome.textColor())
            val pill = GradientDrawable()
            pill.cornerRadius = 40f
            pill.setColor(outcome.color())
            holder.badge.background = pill

            val avatarBg = GradientDrawable()
            avatarBg.cornerRadius = 40f
            avatarBg.setColor(outcome.color())
            holder.avatar.background = avatarBg
            holder.avatar.setTextColor(outcome.textColor())
        } else {
            holder.badge.visibility = View.GONE
            holder.avatar.setBackgroundResource(R.drawable.bg_avatar)
            holder.avatar.setTextColor(Color.parseColor("#F5F5F5"))
        }
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
}
