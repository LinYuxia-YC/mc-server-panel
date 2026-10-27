package com.mcpanel

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mcpanel.core.IntentUtils
import com.mcpanel.core.LogCollector
import com.mcpanel.core.Prefs
import com.mcpanel.core.RunStatus
import com.mcpanel.core.ServerOps
import com.mcpanel.core.ServerProfile
import com.mcpanel.core.ServerRunner
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.LogAdapter
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConsoleActivity : AppCompatActivity() {

    private lateinit var profile: ServerProfile
    private lateinit var adapter: LogAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var autoScrollView: TextView
    private lateinit var input: EditText
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)
        val found = Prefs.server(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            Ui.toast(this, "服务器不存在")
            finish()
            return
        }
        profile = found
        adapter = LogAdapter()
        recycler = findViewById(R.id.logList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        autoScrollView = findViewById(R.id.autoScroll)
        input = findViewById(R.id.commandInput)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = "控制台 · ${profile.displayTitle}"
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }
        autoScrollView.setOnClickListener {
            autoScroll = !autoScroll
            autoScrollView.text = if (autoScroll) "自动滚动: 开" else "自动滚动: 关"
            autoScrollView.setTextColor(
                ContextCompat.getColor(this, if (autoScroll) R.color.primary else R.color.text_dim)
            )
            if (autoScroll) scrollToBottom()
        }
        buildQuickCommands()
        observe()
    }

    private fun buildQuickCommands() {
        val row = findViewById<android.widget.LinearLayout>(R.id.quickCommands)
        val commands = listOf("list", "save-all", "say 你好", "op ", "gamerule randomTickSpeed 3", "whitelist on", "stop")
        commands.forEach { cmd ->
            val tv = TextView(this).apply {
                text = cmd
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@ConsoleActivity, R.color.text_dim))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(Ui.dp(this@ConsoleActivity, 12f), Ui.dp(this@ConsoleActivity, 6f), Ui.dp(this@ConsoleActivity, 12f), Ui.dp(this@ConsoleActivity, 6f))
                setOnClickListener {
                    if (cmd.endsWith(" ")) {
                        input.setText(cmd)
                        input.setSelection(input.text?.length ?: 0)
                        input.requestFocus()
                    } else {
                        input.setText(cmd)
                    }
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = Ui.dp(this, 6f)
            row.addView(tv, lp)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var first = true
                    ServerRunner.logs.collect { line ->
                        if (first) {
                            first = false
                        }
                        adapter.append(line.text)
                        if (autoScroll) scrollToBottom()
                    }
                }
                launch {
                    ServerRunner.state.collect { updateStatus() }
                }
            }
        }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) recycler.scrollToPosition(count - 1)
    }

    private fun updateStatus() {
        val state = ServerRunner.state.value
        val isThis = state.serverId == profile.id
        val text: String
        val color: Int
        when {
            isThis && state.status == RunStatus.STARTING -> {
                text = "启动中… " + if (profile.port > 0) "端口 ${profile.port}" else ""
                color = R.color.warning
            }
            isThis && state.status == RunStatus.RUNNING -> {
                text = "运行中 · 已运行 ${ServerOps.humanUptime(state.startedAt)} · 端口 ${profile.port}"
                color = R.color.success
            }
            isThis && state.status == RunStatus.STOPPING -> {
                text = "正在停止…"
                color = R.color.warning
            }
            isThis && state.status == RunStatus.CRASHED -> {
                text = "服务器已退出（代码 ${state.exitCode ?: "-"}）"
                color = R.color.danger
            }
            else -> {
                text = "未启动"
                color = R.color.text_faint
            }
        }
        statusText.text = text
        statusText.setTextColor(ContextCompat.getColor(this, color))
        statusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun send() {
        val command = input.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return
        if (!ServerRunner.sendCommand(command)) {
            Ui.toast(this, "服务器未运行")
            return
        }
        input.setText("")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_console, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> {
                adapter.clear()
                true
            }
            R.id.action_export -> {
                exportLog()
                true
            }
            R.id.action_toggle_run -> {
                if (ServerRunner.isRunning(profile.id)) {
                    ServerRunner.stop()
                    Ui.toast(this, "正在停止服务器")
                } else {
                    Ui.toast(this, "请返回服务器页面启动")
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportLog() {
        FilePicker.show(this, FilePicker.Mode.DIR, Prefs.serverRoot, null, "选择日志导出目录") { dest ->
            val progress = Ui.progress(this, "正在导出日志", cancelable = false)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { LogCollector.export(profile, dest) }
                progress.dismiss()
                result.onSuccess { zip ->
                    Ui.confirm(this@ConsoleActivity, "日志已导出", zip.absolutePath) {
                        IntentUtils.openFolder(this@ConsoleActivity, zip.parentFile ?: dest)
                    }
                }.onFailure { e ->
                    Ui.toast(this@ConsoleActivity, "导出失败：${e.message}")
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
