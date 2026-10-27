package com.mcpanel

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.mcpanel.core.JavaCatalog
import com.mcpanel.core.JavaManager
import com.mcpanel.core.JavaRuntime
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.RuntimeAdapter
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class JavaActivity : AppCompatActivity() {

    private lateinit var adapter: RuntimeAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var envStatus: TextView
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_java)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        envStatus = findViewById(R.id.envStatus)
        empty = findViewById(R.id.empty)
        recycler = findViewById(R.id.recycler)
        adapter = RuntimeAdapter { rt -> confirmDelete(rt) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { chooseVersion() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = JavaManager.installed()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val envReady = JavaManager.envReady()
        envStatus.text = if (envReady) {
            "运行环境：已就绪（proot + Ubuntu 22.04，arm64）\n已安装 ${list.size} 个 Java 运行时"
        } else {
            "运行环境：未安装（首次安装 Java 时自动下载 Ubuntu 运行环境，约 27MB）"
        }
        envStatus.setTextColor(
            ContextCompat.getColor(this, if (envReady) R.color.success else R.color.text_dim)
        )
    }

    private fun chooseVersion() {
        val majors = McVersions.supportedJavaMajors
        val labels = majors.map { major ->
            val spec = JavaCatalog.spec(major)
            "Java $major · ${spec.label}（约 ${spec.approxMb}MB）"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择要安装的 Java 版本")
            .setItems(labels) { _, which ->
                chooseSource(majors[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun chooseSource(major: Int) {
        val options = arrayOf(
            "中国大陆镜像源（清华大学 TUNA）",
            "官方源（Adoptium / Ubuntu 官方）"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("选择下载源")
            .setItems(options) { _, which ->
                install(major, if (which == 0) Prefs.SOURCE_CN else Prefs.SOURCE_OFFICIAL)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun install(major: Int, source: String) {
        if (installJob?.isActive == true) return
        val progress = Ui.progress(this, "准备安装 Java $major")
        progress.setDetail(JavaCatalog.sourceLabel(source))
        installJob = lifecycleScope.launch {
            try {
                val rt = JavaManager.install(this@JavaActivity, major, source) { stage, percent ->
                    runOnUiThread {
                        progress.setStage(stage)
                        progress.setProgress(percent)
                    }
                }
                progress.dismiss()
                Ui.toast(this@JavaActivity, "${rt.label} 安装完成")
            } catch (e: Throwable) {
                progress.dismiss()
                MaterialAlertDialogBuilder(this@JavaActivity)
                    .setTitle("安装失败")
                    .setMessage(e.message ?: "未知错误")
                    .setPositiveButton("重试") { _, _ -> install(major, source) }
                    .setNegativeButton("关闭", null)
                    .show()
            } finally {
                refresh()
            }
        }
        progress.setCancelHandler {
            installJob?.cancel()
            progress.dismiss()
            Ui.toast(this, "已取消安装")
        }
    }

    private fun confirmDelete(rt: JavaRuntime) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 ${rt.label}")
            .setMessage("删除后使用该 Java 的服务器需要重新选择运行时。确定删除吗？")
            .setPositiveButton("删除") { _, _ ->
                val ok = JavaManager.delete(rt)
                Ui.toast(this, if (ok) "已删除" else "删除失败")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importLocalJava() {
        FilePicker.show(
            this,
            FilePicker.Mode.FILE,
            Prefs.serverRoot,
            listOf(".tar.gz", ".tgz", ".tar.xz", ".zip"),
            "选择 Java 压缩包（已适配安卓的 JRE）"
        ) { file ->
            val progress = Ui.progress(this, "正在导入 Java")
            installJob = lifecycleScope.launch {
                try {
                    val rt = JavaManager.importNative(this@JavaActivity, file) { stage, percent ->
                        runOnUiThread {
                            progress.setStage(stage)
                            progress.setProgress(percent)
                        }
                    }
                    progress.dismiss()
                    Ui.toast(this@JavaActivity, "已导入 ${rt.label}")
                } catch (e: Throwable) {
                    progress.dismiss()
                    Ui.toast(this@JavaActivity, "导入失败：${e.message}")
                } finally {
                    refresh()
                }
            }
            progress.setCancelHandler {
                installJob?.cancel()
                progress.dismiss()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_java, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import_java -> {
                importLocalJava()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        installJob?.cancel()
        super.onDestroy()
    }
}
