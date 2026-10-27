package com.mcpanel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.mcpanel.core.JavaManager
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.core.ServerOps
import com.mcpanel.core.ServerProfile
import com.mcpanel.core.ServerRunner
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.ServerAdapter
import com.mcpanel.ui.Ui
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setTitle(R.string.app_name)
        toolbar.subtitle = getString(R.string.app_subtitle)

        adapter = ServerAdapter(
            onClick = { profile -> openServer(profile) },
            onQuickStart = { profile -> quickToggle(profile) },
            onLongClick = { profile -> showServerMenu(profile) }
        )
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { showNewServerDialog() }
        findViewById<MaterialButton>(R.id.emptyAdd).setOnClickListener { showNewServerDialog() }

        ensureStoragePermission()
        observeRunner()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun observeRunner() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerRunner.state.collect { state ->
                    adapter.runState = state
                }
            }
        }
    }

    private fun reload() {
        val list = Prefs.servers().sortedByDescending { it.lastStart }
        adapter.submit(list)
        findViewById<View>(R.id.emptyState).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fab).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_java -> {
                startActivity(Intent(this, JavaActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_import_dir -> {
                importExistingServer()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNewServerDialog() {
        Ui.input(this, "新建服务器", "服务器名称", "我的服务器") { name ->
            val safeName = name.ifBlank { "我的服务器" }
            val root = File(Prefs.serverRoot)
            val suggested = File(root, safeName)
            FilePicker.show(this, FilePicker.Mode.DIR, suggested.absolutePath, null, "选择服务器目录") { dir ->
                val target = if (dir.name == safeName) dir else dir
                if (!target.exists() && !target.mkdirs()) {
                    Ui.toast(this, "无法创建目录，请检查存储权限")
                    return@show
                }
                if (target.listFiles()?.isNotEmpty() == true) {
                    Ui.confirm(this, "目录非空", "该目录已有文件，确定为服务器目录吗？") {
                        createAndOpen(safeName, target)
                    }
                } else {
                    createAndOpen(safeName, target)
                }
            }
        }
    }

    private fun createAndOpen(name: String, dir: File) {
        val profile = ServerOps.create(this, name, dir)
        ServerOps.prepareDirectory(profile)
        profile.motd = name
        Prefs.upsert(profile)
        openSetup(profile)
    }

    private fun openSetup(profile: ServerProfile) {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ID, profile.id))
    }

    private fun openServer(profile: ServerProfile) {
        val runtime = Prefs.runtime(profile.javaId)
        if (!profile.jarFile.exists() || runtime == null) {
            openSetup(profile)
            return
        }
        startActivity(Intent(this, ServerActivity::class.java).putExtra(ServerActivity.EXTRA_ID, profile.id))
    }

    private fun showServerMenu(profile: ServerProfile) {
        val items = listOf("继续配置 / 更换 Java", "重命名服务器", "打开服务器目录（MT）", "删除服务器")
        Ui.list(this, profile.displayTitle, items) { index ->
            when (index) {
                0 -> openSetup(profile)
                1 -> Ui.input(this, "重命名服务器", "服务器名称", profile.displayTitle) { name ->
                    if (name.isNotBlank()) {
                        profile.name = name
                        Prefs.upsert(profile)
                        reload()
                        Ui.toast(this, "已重命名")
                    }
                }
                2 -> FilePicker.openWithMt(this, File(profile.dir))
                3 -> {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("删除服务器")
                        .setMessage("将从面板中移除「${profile.displayTitle}」。\n如需保留存档，请选择「仅删除记录」。")
                        .setPositiveButton("删除记录和文件") { _, _ ->
                            ServerOps.deleteServer(profile, true)
                            reload()
                            Ui.toast(this, "已删除")
                        }
                        .setNeutralButton("仅删除记录") { _, _ ->
                            ServerOps.deleteServer(profile, false)
                            reload()
                            Ui.toast(this, "已移除（文件保留）")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun quickToggle(profile: ServerProfile) {
        if (ServerRunner.isRunning(profile.id)) {
            ServerRunner.stop()
            Ui.toast(this, "正在停止 ${profile.displayTitle}")
            return
        }
        val runtime = Prefs.runtime(profile.javaId)
        if (runtime == null) {
            Ui.confirm(this, "尚未配置 Java", "请先进入服务器设置安装 Java 运行环境。") {
                openServer(profile)
            }
            return
        }
        if (!profile.jarFile.exists()) {
            Ui.confirm(this, "尚未导入服务端核心", "请先进入服务器设置导入服务端核心 (.jar)。") {
                openServer(profile)
            }
            return
        }
        val result = ServerRunner.start(this, profile, runtime)
        result.onSuccess {
            profile.lastStart = System.currentTimeMillis()
            Prefs.upsert(profile)
            Ui.toast(this, "正在启动 ${profile.displayTitle}")
        }.onFailure { e ->
            Ui.toast(this, "启动失败：${e.message}")
        }
    }

    private fun importExistingServer() {
        FilePicker.show(this, FilePicker.Mode.DIR, Prefs.serverRoot, null, "选择已有服务器目录") { dir ->
            val hasFiles = dir.listFiles()?.any { it.isFile && it.name.endsWith(".jar") } == true
            if (!hasFiles) {
                Ui.confirm(this, "目录中没有 .jar", "该目录下未找到服务端核心，仍要导入吗？") {
                    importDir(dir)
                }
            } else {
                importDir(dir)
            }
        }
    }

    private fun importDir(dir: File) {
        val profile = ServerOps.importExisting(this, dir)
        reload()
        Ui.toast(this, "已导入 ${profile.displayTitle}")
        if (profile.jar.isBlank()) {
            openSetup(profile)
        } else {
            openServer(profile)
        }
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQ_STORAGE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) {
                Ui.confirm(
                    this,
                    "需要存储权限",
                    "读取和写入服务器目录需要存储权限，请在系统设置中授予。"
                ) {
                    try {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        if (ServerRunner.isRunning()) {
            Ui.confirm(this, "服务器正在运行", "服务器将在后台继续运行，确定退出面板吗？") {
                finish()
            }
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
