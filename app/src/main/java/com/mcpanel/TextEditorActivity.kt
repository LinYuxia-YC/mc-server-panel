package com.mcpanel

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mcpanel.core.FileUtils
import com.mcpanel.core.IntentUtils
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TextEditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var editor: EditText
    private lateinit var info: TextView
    private var dirty = false
    private var wrap = false
    private var original = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_editor)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            Ui.toast(this, "文件路径无效")
            finish()
            return
        }
        file = File(path)
        editor = findViewById(R.id.editor)
        info = findViewById(R.id.info)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: file.name
        toolbar.setNavigationOnClickListener { confirmExit() }
        findViewById<TextView>(R.id.pathText).text = Ui.prettyPath(file.absolutePath)
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dirty = s?.toString() != original
                updateInfo()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        load()
    }

    private fun load() {
        val progress = Ui.progress(this, "正在读取文件")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                if (file.exists()) FileUtils.readText(file) else ""
            }
            progress.dismiss()
            original = text
            editor.setText(text)
            dirty = false
            updateInfo()
            if (!file.exists()) {
                Ui.toast(this@TextEditorActivity, "文件不存在，保存后将创建")
            }
        }
    }

    private fun updateInfo() {
        val text = editor.text?.toString() ?: ""
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        info.text = "$lines 行 · ${text.length} 字符" + if (dirty) " · 未保存" else ""
        info.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (dirty) R.color.warning else R.color.text_faint
            )
        )
    }

    private fun save() {
        val text = editor.text?.toString() ?: return
        val ok = FileUtils.writeText(file, text)
        if (ok) {
            original = text
            dirty = false
            updateInfo()
            Ui.toast(this, "已保存 ${file.name}")
        } else {
            Ui.toast(this, "保存失败，请检查存储权限")
        }
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("有未保存的修改")
            .setMessage("是否保存后再退出？")
            .setPositiveButton("保存并退出") { _, _ ->
                save()
                finish()
            }
            .setNegativeButton("放弃修改") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_open_external -> {
                IntentUtils.openFile(this, file, "text/plain")
                true
            }
            R.id.action_wrap -> {
                wrap = !wrap
                editor.setHorizontallyScrolling(!wrap)
                editor.setSingleLine(false)
                Ui.toast(this, if (wrap) "已开启自动换行" else "已关闭自动换行")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmExit()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }
}
