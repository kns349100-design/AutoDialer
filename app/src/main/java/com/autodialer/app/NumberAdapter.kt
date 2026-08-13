package com.autodialer.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NumberAdapter(private val numbers: MutableList<String>) :
    RecyclerView.Adapter<NumberAdapter.ViewHolder>() {

    var currentIndex: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_number, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val number = numbers[position]
        val prefix = "${position + 1}. "
        holder.textView.text = prefix + number

        when {
            position == currentIndex -> holder.textView.setBackgroundColor(Color.parseColor("#FFF3CD"))
            position < currentIndex -> holder.textView.setBackgroundColor(Color.parseColor("#D4EDDA"))
            else -> holder.textView.setBackgroundColor(Color.WHITE)
        }
    }

    override fun getItemCount(): Int = numbers.size

    fun setNumbers(newNumbers: List<String>) {
        numbers.clear()
        numbers.addAll(newNumbers)
        currentIndex = -1
        notifyDataSetChanged()
    }
}
