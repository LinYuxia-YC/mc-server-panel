package com.mcpanel

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.mcpanel.core.FileUtils
import com.mcpanel.core.Prefs
import com.mcpanel.core.ServerProfile
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.ModAdapter
import com.mcpanel.ui.ModEntry
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModsActivity : AppCompatActivity() {

    private lateinit var profile: ServerProfile
    private lateinit var adapter: ModAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var hint: TextView
    private var pluginMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mods)
        val found = Prefs.server(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            Ui.toast(this, "服务器不存在")
            finish()
            return
        }
        profile = found
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        hint = findViewById(R.id.hint)
        empty = findViewById(R.id.empty)
        recycler = findViewById(R.id.recycler)
        adapter = ModAdapter(
            onToggle = { entry, enabled -> toggle(entry, enabled) },
            onMore = { entry, _ -> showEntryMenu(entry) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggleGroup)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            pluginMode = checkedId == R.id.btnPluginsTab
            refresh()
        }
        toggle.check(R.id.btnModsTab)

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { importFiles() }
    }

    private fun currentDir(): File {
        val dir = File(profile.dir, if (pluginMode) "plugins" else "mods")
        FileUtils.ensureDir(dir)
        return dir
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val dir = currentDir()
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.contains(".jar") }
            .map { file ->
                val enabled = file.name.endsWith(".jar", true)
                ModEntry(file, enabled)
            }
            .sortedWith(compareByDescending<ModEntry> { it.enabled }.thenBy { it.displayName.lowercase() })
        adapter.submit(entries)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        val enabledCount = entries.count { it.enabled }
        hint.text = buildString {
            append(if (pluginMode) "插件目录" else "MOD 目录")
            append("：")
            append(Ui.prettyPath(dir.absolutePath))
            append("\n共 ")
            append(entries.size)
            append(" 个文件，已启用 ")
            append(enabledCount)
            append(" 个。开关切换后会重命名文件（.disabled 表示禁用），重启服务器生效。")
        }
    }

    private fun toggle(entry: ModEntry, enabled: Boolean) {
        val file = entry.file
        val target: File = if (enabled) {
            val idx = file.name.indexOf(".jar.", ignoreCase = true)
            val name = if (idx >= 0) file.name.substring(0, idx + 4) else file.name
            File(file.parentFile, name)
        } else {
            File(file.parentFile, file.name + ".disabled")
        }
        if (target.exists()) {
            Ui.toast(this, "目标文件已存在：${target.name}")
            refresh()
            return
        }
        if (file.renameTo(target)) {
            Ui.toast(this, if (enabled) "已启用 ${entry.displayName}" else "已禁用 ${entry.displayName}")
        } else {
            Ui.toast(this, "重命名失败，请检查存储权限")
        }
        refresh()
    }

    private fun showEntryMenu(entry: ModEntry) {
        val items = arrayOf(
            if (entry.enabled) "禁用" else "启用",
            "删除文件",
            "打开所在目录"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggle(entry, !entry.enabled)
                    1 -> {
                        Ui.confirm(this, "删除文件", "确定删除 ${entry.file.name} 吗？此操作不可撤销。") {
                            if (entry.file.delete()) {
                                Ui.toast(this, "已删除")
                            } else {
                                Ui.toast(this, "删除失败")
                            }
                            refresh()
                        }
                    }
                    2 -> FilePicker.openWithMt(this, currentDir())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFiles() {
        FilePicker.show(
            this,
            FilePicker.Mode.FILE,
            currentDir().absolutePath,
            listOf(".jar", ".jar.disabled", ".dsible", ".zip"),
            "选择要导入的文件"
        ) { picked ->
            val progress = Ui.progress(this, "正在导入", cancelable = false)
            lifecycleScope.launch {
                val dest = File(currentDir(), picked.name)
                val ok = withContext(Dispatchers.IO) {
                    if (dest.exists()) dest.delete()
                    FileUtils.copy(picked, dest, true)
                }
                progress.dismiss()
                if (ok) {
                    Ui.toast(this@ModsActivity, "已导入 ${picked.name}")
                    refresh()
                } else {
                    Ui.toast(this@ModsActivity, "导入失败，请检查存储权限")
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_mods, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import -> {
                importFiles()
                true
            }
            R.id.action_open_dir -> {
                FilePicker.openWithMt(this, currentDir())
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
