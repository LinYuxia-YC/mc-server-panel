package com.mcpanel.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mcpanel.R
import com.mcpanel.core.FileUtils
import com.mcpanel.core.IntentUtils
import com.mcpanel.core.Paths
import com.mcpanel.core.Prefs
import java.io.File

object FilePicker {

    enum class Mode { DIR, FILE }

    fun show(
        activity: Activity,
        mode: Mode,
        startPath: String? = null,
        extensions: List<String>? = null,
        title: String? = null,
        onPick: (File) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_file_picker, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            activity.resources.displayMetrics.widthPixels - Ui.dp(activity, 24f),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val titleView = view.findViewById<TextView>(R.id.title)
        val pathView = view.findViewById<TextView>(R.id.path)
        val listView = view.findViewById<RecyclerView>(R.id.list)
        val btnUp = view.findViewById<ImageView>(R.id.btnUp)
        val btnNewFolder = view.findViewById<ImageView>(R.id.btnNewFolder)
        val btnSelect = view.findViewById<TextView>(R.id.btnSelect)
        val btnCancel = view.findViewById<TextView>(R.id.btnCancel)
        val quickRow = view.findViewById<android.widget.LinearLayout>(R.id.quickRow)

        titleView.text = title ?: if (mode == Mode.DIR) "选择目录" else "选择文件"
        btnSelect.text = if (mode == Mode.DIR) "选择此目录" else "选择文件"
        if (mode == Mode.FILE) btnSelect.visibility = View.GONE

        var current = File(
            when {
                startPath.isNullOrBlank() -> Paths.externalRoot.absolutePath
                File(startPath).exists() -> startPath
                else -> Paths.externalRoot.absolutePath
            }
        )
        if (current.isFile) current = current.parentFile ?: Paths.externalRoot
        var selected: File? = null

        lateinit var adapter: FileEntryAdapter

        fun refresh() {
            pathView.text = Ui.prettyPath(current.absolutePath)
            val entries = mutableListOf<FileEntry>()
            val dirs = current.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            dirs.forEach { entries.add(FileEntry(it, true)) }
            val files = current.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
                ?.filter { f ->
                    extensions == null || extensions.any { f.name.endsWith(it, true) }
                }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            files.forEach { entries.add(FileEntry(it, false)) }
            adapter.submit(entries)
            if (entries.isEmpty()) {
                Ui.toast(activity, "该目录为空或无法读取")
            }
        }

        adapter = FileEntryAdapter(
            onClick = { entry ->
                if (entry.isDir) {
                    current = entry.file
                    selected = null
                    refresh()
                } else {
                    if (mode == Mode.FILE) {
                        dialog.dismiss()
                        onPick(entry.file)
                    } else {
                        selected = entry.file
                        Ui.toast(activity, "已选择文件：${entry.file.name}")
                    }
                }
            }
        )
        listView.layoutManager = LinearLayoutManager(activity)
        listView.adapter = adapter

        fun quick(label: String, target: File) {
            val tv = TextView(activity).apply {
                text = label
                setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_dim))
                textSize = 11f
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(Ui.dp(activity, 12f), Ui.dp(activity, 6f), Ui.dp(activity, 12f), Ui.dp(activity, 6f))
                setOnClickListener {
                    if (target.exists()) {
                        current = target
                        refresh()
                    } else {
                        Ui.toast(activity, "目录不存在：${target.absolutePath}")
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = Ui.dp(activity, 6f)
            quickRow.addView(tv, lp)
        }

        quick("内部存储", Paths.externalRoot)
        quick("Download", File(Paths.externalRoot, "Download"))
        quick("服务器根目录", File(Prefs.serverRoot))
        if (!startPath.isNullOrBlank()) {
            File(startPath).parentFile?.let { quick("上级目录", it) }
        }

        btnUp.setOnClickListener {
            val parent = current.parentFile
            if (parent != null && parent.exists()) {
                current = parent
                refresh()
            } else {
                Ui.toast(activity, "已到顶")
            }
        }
        btnNewFolder.setOnClickListener {
            Ui.input(activity, "新建文件夹", "文件夹名称") { name ->
                if (name.isBlank()) return@input
                val target = File(current, FileUtils.sanitizeName(name))
                if (target.exists()) {
                    Ui.toast(activity, "同名文件夹已存在")
                } else if (target.mkdirs()) {
                    refresh()
                    Ui.toast(activity, "已创建 ${target.name}")
                } else {
                    Ui.toast(activity, "创建失败，请检查存储权限")
                }
            }
        }
        btnSelect.setOnClickListener {
            dialog.dismiss()
            onPick(current)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        refresh()
    }

    data class FileEntry(val file: File, val isDir: Boolean)

    class FileEntryAdapter(private val onClick: (FileEntry) -> Unit) :
        RecyclerView.Adapter<FileEntryAdapter.Holder>() {

        private val items = mutableListOf<FileEntry>()

        fun submit(list: List<FileEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.name.text = entry.file.name
            if (entry.isDir) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.meta.text = "文件夹"
                holder.chevron.visibility = View.VISIBLE
            } else {
                holder.icon.setImageResource(R.drawable.ic_description)
                holder.meta.text = FileUtils.humanSize(entry.file.length())
                holder.chevron.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val meta: TextView = view.findViewById(R.id.meta)
            val chevron: ImageView = view.findViewById(R.id.chevron)
        }
    }

    /** Convenience: picks a .jar core file; also allows the user to fall back to the built-in picker. */
    fun pickJar(activity: Activity, startPath: String?, onPick: (File) -> Unit) {
        show(activity, Mode.FILE, startPath, listOf(".jar"), "选择服务端核心 (.jar)", onPick)
    }

    fun openWithMt(activity: Activity, dir: File) {
        if (!IntentUtils.openInMt(activity, dir)) {
            Ui.confirm(activity, "未找到 MT 管理器", "是否打开系统文件管理器？") {
                IntentUtils.openFolder(activity, dir)
            }
        }
    }
}
