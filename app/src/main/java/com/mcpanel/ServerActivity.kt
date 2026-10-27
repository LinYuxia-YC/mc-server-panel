package com.mcpanel

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mcpanel.core.CoreDetector
import com.mcpanel.core.FileUtils
import com.mcpanel.core.IntentUtils
import com.mcpanel.core.JavaManager
import com.mcpanel.core.LogCollector
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.core.RunStatus
import com.mcpanel.core.ServerOps
import com.mcpanel.core.ServerProfile
import com.mcpanel.core.ServerRunner
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServerActivity : AppCompatActivity() {

    private lateinit var profile: ServerProfile
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var uptime: TextView
    private lateinit var serverMeta: TextView
    private lateinit var serverPath: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var statusMessage: TextView
    private lateinit var consolePreview: TextView

    private val previewLines = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        val found = Prefs.server(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            Ui.toast(this, "服务器不存在")
            finish()
            return
        }
        profile = found
        bindViews()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        btnToggle.setOnClickListener { toggleServer() }
        findViewById<View>(R.id.btnConsole).setOnClickListener { openConsole() }
        findViewById<View>(R.id.btnConfig).setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java).putExtra(ConfigActivity.EXTRA_ID, profile.id))
        }
        findViewById<View>(R.id.btnMods).setOnClickListener {
            startActivity(Intent(this, ModsActivity::class.java).putExtra(ModsActivity.EXTRA_ID, profile.id))
        }
        findViewById<View>(R.id.btnWorld).setOnClickListener { showWorldMenu() }
        findViewById<View>(R.id.btnEula).setOnClickListener { showEulaDialog() }
        findViewById<View>(R.id.btnOpenDir).setOnClickListener { openDirectory() }
        findViewById<View>(R.id.btnExportLog).setOnClickListener { exportLogs() }
        findViewById<View>(R.id.btnJava).setOnClickListener {
            startActivity(Intent(this, JavaActivity::class.java))
        }
        findViewById<View>(R.id.consolePreview).setOnClickListener { openConsole() }

        observeRunner()
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        uptime = findViewById(R.id.uptime)
        serverMeta = findViewById(R.id.serverMeta)
        serverPath = findViewById(R.id.serverPath)
        btnToggle = findViewById(R.id.btnToggle)
        statusMessage = findViewById(R.id.statusMessage)
        consolePreview = findViewById(R.id.consolePreview)
    }

    override fun onResume() {
        super.onResume()
        profile = Prefs.server(profile.id) ?: profile
        updateStaticInfo()
        updateStatus()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun updateStaticInfo() {
        val runtime = Prefs.runtime(profile.javaId)
        val typeLabel = profile.coreType.replace("原版 Vanilla", "原版").ifBlank { "未知核心" }
        val lanIp = IntentUtils.wifiIp()
        serverMeta.text = buildString {
            append(McVersions.pretty(profile.mcVersion))
            append(" · ")
            append(typeLabel)
            append("\n端口 ")
            append(profile.port)
            append(" · 内存 ")
            append(profile.ramMb)
            append("MB · ")
            append(runtime?.label ?: "未配置 Java")
            if (profile.onlineMode) append(" · 正版验证已开启")
            else append(" · 离线模式")
            if (lanIp != null) {
                append("\n局域网地址 ")
                append(lanIp)
                append(":")
                append(profile.port)
            }
            append("\nEULA：")
            append(if (ServerOps.isEulaAccepted(profile)) "已同意" else "未同意")
        }
        serverPath.text = Ui.prettyPath(profile.dir)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title =
            profile.displayTitle
    }

    private fun updateStatus() {
        val state = ServerRunner.state.value
        val isThis = state.serverId == profile.id
        val running = isThis && state.active
        val (text, colorRes) = when {
            isThis && state.status == RunStatus.STARTING -> "启动中…" to R.color.warning
            isThis && state.status == RunStatus.RUNNING -> "运行中" to R.color.success
            isThis && state.status == RunStatus.STOPPING -> "正在停止…" to R.color.warning
            isThis && state.status == RunStatus.CRASHED -> "异常退出" to R.color.danger
            else -> "未启动" to R.color.text_faint
        }
        statusText.text = text
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
        statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        uptime.text = if (running) "已运行 " + ServerOps.humanUptime(state.startedAt) else ""

        if (running) {
            btnToggle.text = if (state.status == RunStatus.STOPPING) "正在停止…" else "停止服务器"
            btnToggle.setIconResource(R.drawable.ic_stop)
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.black))
            btnToggle.isEnabled = state.status != RunStatus.STOPPING
        } else {
            btnToggle.text = "启动服务器"
            btnToggle.setIconResource(R.drawable.ic_play)
            btnToggle.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
            btnToggle.isEnabled = true
        }
        val message = if (isThis) state.message else null
        statusMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        statusMessage.text = message
        statusMessage.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.status == RunStatus.CRASHED) R.color.danger else R.color.warning
            )
        )
    }

    private fun toggleServer() {
        val state = ServerRunner.state.value
        if (ServerRunner.isRunning(profile.id)) {
            ServerRunner.stop()
            Ui.toast(this, "正在停止服务器，请稍候…")
            return
        }
        if (state.active) {
            Ui.toast(this, "已有其他服务器在运行")
            return
        }
        if (!profile.jarFile.exists()) {
            Ui.confirm(this, "缺少服务端核心", "请先导入服务端核心 (.jar)。") {
                pickCore()
            }
            return
        }
        val runtime = Prefs.runtime(profile.javaId)
        if (runtime == null) {
            Ui.confirm(this, "尚未配置 Java", "请先安装或选择一个 Java 运行环境。") {
                startActivity(Intent(this, JavaActivity::class.java))
            }
            return
        }
        if (!ServerOps.isEulaAccepted(profile)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要同意 EULA")
                .setMessage("Minecraft 服务器启动前必须同意最终用户许可协议（EULA）。是否现在同意？")
                .setPositiveButton("同意并启动") { _, _ ->
                    ServerOps.setEula(profile, true)
                    updateStaticInfo()
                    startServer(runtime)
                }
                .setNeutralButton("查看协议") { _, _ ->
                    IntentUtils.openUrl(this, "https://aka.ms/MinecraftEULA")
                    showEulaDialog()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startServer(runtime)
    }

    private fun startServer(runtime: com.mcpanel.core.JavaRuntime) {
        val result = ServerRunner.start(this, profile, runtime)
        result.onSuccess {
            profile.lastStart = System.currentTimeMillis()
            Prefs.upsert(profile)
            openConsole()
        }.onFailure { e ->
            MaterialAlertDialogBuilder(this)
                .setTitle("启动失败")
                .setMessage(e.message ?: "未知错误")
                .setPositiveButton("导出错误日志") { _, _ -> exportLogs() }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun openConsole() {
        startActivity(Intent(this, ConsoleActivity::class.java).putExtra(ConsoleActivity.EXTRA_ID, profile.id))
    }

    private fun showEulaDialog() {
        val accepted = ServerOps.isEulaAccepted(profile)
        val view = layoutInflater.inflate(R.layout.dialog_eula, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.show()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - Ui.dp(this, 40f),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val status = view.findViewById<TextView>(R.id.eulaStatus)
        status.text = if (accepted) {
            "当前已同意 EULA，服务器可以正常启动。\n可以随时打开 eula.txt 查看或修改（eula=true）。"
        } else {
            "当前尚未同意 EULA，服务器将无法启动。\nMinecraft 要求启动服务端前必须同意最终用户许可协议。"
        }
        val accept = view.findViewById<View>(R.id.btnAcceptEula)
        accept.visibility = if (accepted) View.GONE else View.VISIBLE
        accept.setOnClickListener {
            ServerOps.setEula(profile, true)
            updateStaticInfo()
            Ui.toast(this, "已同意 EULA")
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnOpenEula).setOnClickListener {
            dialog.dismiss()
            openText(profile.eulaFile, "eula.txt")
        }
        view.findViewById<View>(R.id.btnOpenEulaWeb).setOnClickListener {
            IntentUtils.openUrl(this, "https://aka.ms/MinecraftEULA")
        }
        view.findViewById<View>(R.id.btnEulaClose).setOnClickListener { dialog.dismiss() }
    }

    private fun openText(file: File, title: String) {
        if (!file.exists()) {
            ServerOps.setEula(profile, false)
        }
        startActivity(
            Intent(this, TextEditorActivity::class.java)
                .putExtra(TextEditorActivity.EXTRA_PATH, file.absolutePath)
                .putExtra(TextEditorActivity.EXTRA_TITLE, title)
        )
    }

    private fun openDirectory() {
        FilePicker.openWithMt(this, profile.dirFile)
    }

    private fun showWorldMenu() {
        val worlds = ServerOps.worldDirs(profile)
        val view = layoutInflater.inflate(R.layout.dialog_world, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.show()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - Ui.dp(this, 40f),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val info = view.findViewById<TextView>(R.id.worldInfo)
        info.text = if (worlds.isEmpty()) {
            "尚未生成世界（首次启动服务器后会出现世界文件夹）"
        } else {
            val size = FileUtils.humanSize(worlds.sumOf { FileUtils.sizeOf(it) })
            "当前世界：" + worlds.joinToString("、") { it.name } + "\n大小：" + size
        }
        view.findViewById<View>(R.id.btnExportWorld).setOnClickListener {
            dialog.dismiss()
            exportWorld()
        }
        view.findViewById<View>(R.id.btnResetWorld).setOnClickListener {
            dialog.dismiss()
            resetWorld()
        }
        view.findViewById<View>(R.id.btnOpenWorldDir).setOnClickListener {
            dialog.dismiss()
            val first = worlds.firstOrNull() ?: profile.dirFile
            FilePicker.openWithMt(this, first)
        }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
    }

    private fun exportWorld() {
        if (ServerRunner.isRunning(profile.id)) {
            Ui.toast(this, "请先停止服务器再导出世界")
            return
        }
        if (ServerOps.worldDirs(profile).isEmpty()) {
            Ui.toast(this, "没有找到世界文件夹")
            return
        }
        FilePicker.show(this, FilePicker.Mode.DIR, Prefs.serverRoot, null, "选择世界导出目录") { dest ->
            val progress = Ui.progress(this, "正在导出世界")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ServerOps.exportWorld(profile, dest) { pct ->
                        runOnUiThread { progress.setProgress(pct) }
                    }
                }
                progress.dismiss()
                result.onSuccess { zip ->
                    Ui.confirm(this@ServerActivity, "导出完成", "已保存到：\n${zip.absolutePath}") {
                        IntentUtils.openFolder(this@ServerActivity, zip.parentFile ?: dest)
                    }
                }.onFailure { e ->
                    Ui.toast(this@ServerActivity, "导出失败：${e.message}")
                }
            }
        }
    }

    private fun resetWorld() {
        if (ServerRunner.isRunning(profile.id)) {
            Ui.toast(this, "请先停止服务器再重置世界")
            return
        }
        val worlds = ServerOps.worldDirs(profile)
        if (worlds.isEmpty()) {
            Ui.toast(this, "没有找到世界文件夹")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重置世界")
            .setMessage("将删除以下世界文件夹：\n" + worlds.joinToString("\n") { it.name } +
                "\n\n此操作不可撤销，建议先备份。")
            .setPositiveButton("备份并重置") { _, _ -> doReset(true) }
            .setNeutralButton("直接重置") { _, _ -> doReset(false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doReset(backup: Boolean) {
        val progress = Ui.progress(this, if (backup) "正在备份并重置世界" else "正在重置世界", cancelable = false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ServerOps.resetWorld(profile, backup) }
            progress.dismiss()
            result.onSuccess { msg -> Ui.toast(this@ServerActivity, msg) }
                .onFailure { e -> Ui.toast(this@ServerActivity, "重置失败：${e.message}") }
        }
    }

    private fun exportLogs() {
        FilePicker.show(this, FilePicker.Mode.DIR, Prefs.serverRoot, null, "选择日志导出目录") { dest ->
            val progress = Ui.progress(this, "正在打包日志", cancelable = false)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { LogCollector.export(profile, dest) }
                progress.dismiss()
                result.onSuccess { zip ->
                    MaterialAlertDialogBuilder(this@ServerActivity)
                        .setTitle("日志已导出")
                        .setMessage(zip.absolutePath)
                        .setPositiveButton("打开所在目录") { _, _ ->
                            IntentUtils.openFolder(this@ServerActivity, zip.parentFile ?: dest)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }.onFailure { e ->
                    Ui.toast(this@ServerActivity, "导出失败：${e.message}")
                }
            }
        }
    }

    private fun pickCore() {
        FilePicker.pickJar(this, profile.dir) { picked ->
            val progress = Ui.progress(this, "正在导入服务端核心", cancelable = false)
            lifecycleScope.launch {
                val dest = File(profile.dir, "server.jar")
                val ok = withContext(Dispatchers.IO) {
                    if (picked.absolutePath == dest.absolutePath) true else FileUtils.copy(picked, dest, true)
                }
                val info = withContext(Dispatchers.IO) { CoreDetector.inspect(dest) }
                progress.dismiss()
                if (!ok) {
                    Ui.toast(this@ServerActivity, "复制失败")
                    return@launch
                }
                profile.jar = "server.jar"
                profile.coreType = info.type.label
                if (!info.version.isNullOrBlank()) profile.mcVersion = info.version
                Prefs.upsert(profile)
                updateStaticInfo()
                Ui.toast(this@ServerActivity, "已导入 ${info.type.label} ${info.version ?: ""}")
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_server, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_rename -> {
                Ui.input(this, "重命名服务器", "服务器名称", profile.displayTitle) { name ->
                    if (name.isNotBlank()) {
                        profile.name = name
                        Prefs.upsert(profile)
                        updateStaticInfo()
                        Ui.toast(this, "已重命名")
                    }
                }
                true
            }
            R.id.action_change_java -> {
                changeJava()
                true
            }
            R.id.action_reimport_core -> {
                pickCore()
                true
            }
            R.id.action_delete -> {
                deleteServer()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun changeJava() {
        val list = JavaManager.installed()
        if (list.isEmpty()) {
            Ui.confirm(this, "还没有 Java", "请先安装 Java 运行环境。") {
                startActivity(Intent(this, JavaActivity::class.java))
            }
            return
        }
        val labels = list.map { "${it.label}（${it.versionText}）" }
        Ui.list(this, "选择 Java 环境", labels) { index ->
            profile.javaId = list[index].id
            Prefs.upsert(profile)
            updateStaticInfo()
            Ui.toast(this, "已切换为 ${list[index].label}")
        }
    }

    private fun deleteServer() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除服务器")
            .setMessage("将从面板中移除「${profile.displayTitle}」。是否同时删除服务器文件？")
            .setPositiveButton("删除记录和文件") { _, _ ->
                if (ServerRunner.isRunning(profile.id)) ServerRunner.stop()
                ServerOps.deleteServer(profile, true)
                Ui.toast(this, "已删除")
                finish()
            }
            .setNeutralButton("仅删除记录") { _, _ ->
                ServerOps.deleteServer(profile, false)
                Ui.toast(this, "已移除（文件保留）")
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderPreview() {
        consolePreview.text = if (previewLines.isEmpty()) {
            "尚未启动服务器。点击上方按钮开始。"
        } else {
            previewLines.joinToString("\n")
        }
    }

    private fun observeRunner() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ServerRunner.state.collect {
                        updateStatus()
                        updateStaticInfo()
                    }
                }
                launch {
                    ServerRunner.logs.collect { line ->
                        previewLines.addLast(line.text)
                        while (previewLines.size > 6) previewLines.removeFirst()
                        renderPreview()
                    }
                }
            }
        }
    }

    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
