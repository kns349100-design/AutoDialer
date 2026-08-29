package com.autodialer.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SheetSummary(val dayKey: String, val displayName: String, val count: Int)

/** One row per date sheet in the "All Sheets" list (see activity_call_log.xml). */
class SheetSummaryAdapter(
    private var items: List<SheetSummary>,
    private val onClick: (SheetSummary) -> Unit
) : RecyclerView.Adapter<SheetSummaryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSheetTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSheetSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sheet_summary, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.displayName
        holder.subtitle.text = "${item.count} contacts"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<SheetSummary>) {
        items = newItems
        notifyDataSetChanged()
    }
}
