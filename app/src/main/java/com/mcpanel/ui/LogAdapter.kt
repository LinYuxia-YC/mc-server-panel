package com.mcpanel.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mcpanel.R

class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {

    private val lines = mutableListOf<String>()
    private val colorInfo = Color.parseColor("#C9D8CC")
    private val colorDim = Color.parseColor("#8FA396")
    private val colorWarn = Color.parseColor("#FFC55C")
    private val colorError = Color.parseColor("#FF6B6B")
    private val colorChat = Color.parseColor("#7FC4FF")
    private val colorPanel = Color.parseColor("#62D98D")

    fun append(line: String) {
        lines.add(line)
        if (lines.size > 3000) {
            lines.subList(0, 500).clear()
            notifyDataSetChanged()
            return
        }
        notifyItemInserted(lines.size - 1)
    }

    fun appendAll(list: List<String>) {
        lines.addAll(list)
        if (lines.size > 3000) lines.subList(0, lines.size - 3000).clear()
        notifyDataSetChanged()
    }

    fun clear() {
        lines.clear()
        notifyDataSetChanged()
    }

    fun lastLines(count: Int): List<String> = lines.takeLast(count)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        holder.text.text = line
        holder.text.setTextColor(colorFor(line))
    }

    private fun colorFor(line: String): Int {
        val upper = line.uppercase()
        return when {
            line.startsWith("[面板]") -> colorPanel
            upper.contains("ERROR") || upper.contains("SEVERE") || upper.contains("FATAL") ||
                upper.contains("EXCEPTION") || upper.contains("FAILED") -> colorError
            upper.contains("WARN") -> colorWarn
            line.contains("joined the game") || line.contains("left the game") ||
                line.contains("<") && line.contains(">") -> colorChat
            upper.contains("INFO") -> colorInfo
            else -> colorDim
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.line)
    }
}
