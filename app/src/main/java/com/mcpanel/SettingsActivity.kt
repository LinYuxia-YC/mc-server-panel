package com.mcpanel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mcpanel.core.IntentUtils
import com.mcpanel.core.JavaManager
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.Ui
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.rowRoot).setOnClickListener {
            FilePicker.show(this, FilePicker.Mode.DIR, Prefs.serverRoot, null, "选择默认服务器根目录") { dir ->
                Prefs.serverRoot = dir.absolutePath
                updateValues()
                Ui.toast(this, "已设置为 ${Ui.prettyPath(dir.absolutePath)}")
            }
        }
        findViewById<View>(R.id.rowSource).setOnClickListener {
            val options = arrayOf("中国大陆镜像源（清华 TUNA）", "官方源（Adoptium / Ubuntu 官方）")
            MaterialAlertDialogBuilder(this)
                .setTitle("默认 Java 下载源")
                .setSingleChoiceItems(
                    options,
                    if (Prefs.downloadSource == Prefs.SOURCE_OFFICIAL) 1 else 0
                ) { dialog, which ->
                    Prefs.downloadSource = if (which == 0) Prefs.SOURCE_CN else Prefs.SOURCE_OFFICIAL
                    updateValues()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        findViewById<View>(R.id.rowJava).setOnClickListener {
            startActivity(Intent(this, JavaActivity::class.java))
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener {
            IntentUtils.requestIgnoreBatteryOptimizations(this)
        }
        findViewById<View>(R.id.rowLicense).setOnClickListener { showLicenses() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { showAbout() }
        updateValues()
    }

    override fun onResume() {
        super.onResume()
        updateValues()
    }

    private fun updateValues() {
        findViewById<TextView>(R.id.rootPath).text = Ui.prettyPath(Prefs.serverRoot)
        findViewById<TextView>(R.id.sourceValue).text =
            if (Prefs.downloadSource == Prefs.SOURCE_OFFICIAL) "官方源（Adoptium / Ubuntu）"
            else "中国大陆镜像源（清华 TUNA）"
        val runtimes = JavaManager.installed()
        findViewById<TextView>(R.id.javaValue).text = buildString {
            append(runtimes.size)
            append(" 个运行时")
            if (JavaManager.envReady()) append(" · 运行环境已就绪") else append(" · 运行环境未安装")
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) {
            "1.0.0"
        }
        findViewById<TextView>(R.id.versionText).text =
            "版本 $version · arm64 · Android ${android.os.Build.VERSION.RELEASE}\n" +
                "Java 支持：${McVersions.supportedJavaMajors.joinToString(" / ") { "Java $it" }}"
    }

    private fun showLicenses() {
        val text = """
            本应用使用了以下开源组件：

            • AndroidX / Material Components — Apache License 2.0
            • Kotlin Coroutines — Apache License 2.0
            • Gson — Apache License 2.0
            • Apache Commons Compress — Apache License 2.0
            • XZ for Java — Public Domain
            • proot — GNU GPL v2（https://proot-me.github.io/）
            • libtalloc — GNU LGPL v3
            • libandroid-shmem — Apache License 2.0
            • Ubuntu Base rootfs — 由用户按需下载，遵循 Ubuntu 许可
            • Eclipse Temurin (OpenJDK) — GNU GPL v2 + Classpath Exception，由用户按需下载

            本应用不包含任何 Minecraft 游戏文件或服务端核心，
            服务端核心由用户自行导入，Minecraft 相关权利归 Mojang / Microsoft 所有。
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle("开源组件与许可")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showAbout() {
        val text = """
            方块开服 是一个面向 arm64 安卓手机（Android 7.0+）的 Minecraft Java 版服务器面板。

            • 支持导入任意服务端核心（Paper / Spigot / 原版 / Forge / Fabric 等）
            • 根据所选 MC 版本自动准备 Java 8 / 17 / 21 / 25
            • 内置 proot + Ubuntu 便携运行环境，兼容 Android 10+ 的可执行限制
            • 一键配置端口、种子、随机刻、难度、内存、正版验证
            • 一键重置 / 导出世界，导出错误日志
            • 与 MT 管理器联动，快速打开服务器目录

            提示：
            1. 首次安装 Java 需要联网下载（国内镜像约 70MB）。
            2. 服务器运行时请保持通知栏常驻，并在系统设置中允许后台运行。
            3. 若服务器崩溃，可在服务器页面「导出错误日志」把日志发给管理员排查。
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle("使用说明")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }
}
