package com.mcpanel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mcpanel.R
import com.mcpanel.core.JavaManager
import com.mcpanel.core.Prefs
import com.mcpanel.core.RunState
import com.mcpanel.core.RunStatus
import com.mcpanel.core.ServerProfile
import com.mcpanel.core.ServerRunner
import java.io.File

class ServerAdapter(
    private val onClick: (ServerProfile) -> Unit,
    private val onQuickStart: (ServerProfile) -> Unit,
    private val onLongClick: (ServerProfile) -> Unit = {}
) : RecyclerView.Adapter<ServerAdapter.Holder>() {

    private val items = mutableListOf<ServerProfile>()
    var runState: RunState = RunState()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<ServerProfile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val profile = items[position]
        val context = holder.itemView.context
        holder.name.text = profile.displayTitle
        val typeLabel = profile.coreType
            .replace("原版 Vanilla", "原版")
            .replace("Cauldron/Thermos", "Cauldron")
            .ifBlank { "未识别核心" }
        val subtitle = buildString {
            append(if (profile.mcVersion.isBlank()) "版本未设置" else profile.mcVersion)
            append(" · ")
            append(typeLabel)
            append(" · ")
            append(profile.port)
        }
        holder.subtitle.text = subtitle
        holder.path.text = Ui.prettyPath(profile.dir)
        holder.ramChip.text = if (profile.ramMb >= 1024) {
            String.format("%.1fGB 内存", profile.ramMb / 1024f).replace(".0GB", "GB")
        } else {
            "${profile.ramMb}MB 内存"
        }
        val runtime = Prefs.runtime(profile.javaId)
        holder.javaChip.text = runtime?.let { "Java ${it.major}" } ?: "未配置 Java"

        val running = runState.active && runState.serverId == profile.id
        val statusText: String
        val colorRes: Int
        when {
            running && runState.status == RunStatus.STARTING -> {
                statusText = "启动中"
                colorRes = R.color.warning
            }
            running -> {
                statusText = "运行中"
                colorRes = R.color.success
            }
            runState.serverId == profile.id && runState.status == RunStatus.CRASHED -> {
                statusText = "已崩溃"
                colorRes = R.color.danger
            }
            else -> {
                statusText = "未启动"
                colorRes = R.color.text_faint
            }
        }
        holder.statusText.text = statusText
        holder.statusText.setTextColor(ContextCompat.getColor(context, colorRes))
        holder.statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))

        holder.quickStart.text = if (running) "停止" else "启动"
        holder.quickStart.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        holder.quickStart.setOnClickListener { onQuickStart(profile) }
        holder.itemView.setOnClickListener { onClick(profile) }
        holder.itemView.setOnLongClickListener {
            onLongClick(profile)
            true
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val path: TextView = view.findViewById(R.id.path)
        val statusDot: View = view.findViewById(R.id.statusDot)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val ramChip: TextView = view.findViewById(R.id.ramChip)
        val javaChip: TextView = view.findViewById(R.id.javaChip)
        val quickStart: MaterialButton = view.findViewById(R.id.quickStart)
    }
}

data class ModEntry(val file: File, val enabled: Boolean) {
    val displayName: String
        get() = file.name
            .removeSuffix(".disabled")
            .removeSuffix(".disable")
            .removeSuffix(".dsible")
            .removeSuffix(".off")
            .removeSuffix(".jar")
    val size: Long get() = file.length()
}

class ModAdapter(
    private val onToggle: (ModEntry, Boolean) -> Unit,
    private val onMore: (ModEntry, View) -> Unit
) : RecyclerView.Adapter<ModAdapter.Holder>() {

    private val items = mutableListOf<ModEntry>()

    fun submit(list: List<ModEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.name.text = entry.displayName
        holder.meta.text = buildString {
            append(if (entry.enabled) "已启用" else "已禁用")
            append(" · ")
            append(com.mcpanel.core.FileUtils.humanSize(entry.size))
        }
        holder.switch.isChecked = entry.enabled
        holder.switch.setOnCheckedChangeListener { _, checked ->
            if (checked != entry.enabled) onToggle(entry, checked)
        }
        holder.more.setOnClickListener { onMore(entry, holder.itemView) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val meta: TextView = view.findViewById(R.id.meta)
        val switch: com.google.android.material.materialswitch.MaterialSwitch =
            view.findViewById(R.id.switchView)
        val more: View = view.findViewById(R.id.btnMore)
    }
}

class RuntimeAdapter(
    private val onDelete: (com.mcpanel.core.JavaRuntime) -> Unit
) : RecyclerView.Adapter<RuntimeAdapter.Holder>() {

    private val items = mutableListOf<com.mcpanel.core.JavaRuntime>()

    fun submit(list: List<com.mcpanel.core.JavaRuntime>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_runtime, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rt = items[position]
        holder.major.text = rt.major.toString()
        holder.label.text = rt.label
        holder.detail.text = buildString {
            append(JavaManager.describe(rt))
            append(" · ")
            append(com.mcpanel.core.FileUtils.humanSize(rt.sizeBytes))
            if (rt.versionText.isNotBlank()) {
                append("\n")
                append(rt.versionText)
            }
        }
        holder.delete.setOnClickListener { onDelete(rt) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val major: TextView = view.findViewById(R.id.major)
        val label: TextView = view.findViewById(R.id.label)
        val detail: TextView = view.findViewById(R.id.detail)
        val delete: View = view.findViewById(R.id.btnDelete)
    }
}
