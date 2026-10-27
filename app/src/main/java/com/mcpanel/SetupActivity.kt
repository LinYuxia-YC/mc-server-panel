package com.mcpanel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.mcpanel.core.CoreDetector
import com.mcpanel.core.CoreInfo
import com.mcpanel.core.CoreType
import com.mcpanel.core.FileUtils
import com.mcpanel.core.JavaCatalog
import com.mcpanel.core.JavaManager
import com.mcpanel.core.JavaRuntime
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.core.ServerOps
import com.mcpanel.core.ServerProfile
import com.mcpanel.ui.FilePicker
import com.mcpanel.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SetupActivity : AppCompatActivity() {

    private lateinit var profile: ServerProfile
    private var coreFile: File? = null
    private var coreInfo: CoreInfo? = null
    private var version: String = ""
    private var runtime: JavaRuntime? = null
    private var option: Int = 0
    private var installJob: Job? = null

    private lateinit var coreDetected: TextView
    private lateinit var cardVersion: MaterialCardView
    private lateinit var cardJava: MaterialCardView
    private lateinit var cardBasics: MaterialCardView
    private lateinit var versionDropdown: MaterialAutoCompleteTextView
    private lateinit var versionHint: TextView
    private lateinit var javaRequired: TextView
    private lateinit var javaStatus: TextView
    private lateinit var btnInstallJava: MaterialButton
    private lateinit var javaProgressBar: android.widget.ProgressBar
    private lateinit var optionCn: MaterialCardView
    private lateinit var optionOfficial: MaterialCardView
    private lateinit var optionCustom: MaterialCardView
    private lateinit var radioCn: RadioButton
    private lateinit var radioOfficial: RadioButton
    private lateinit var radioCustom: RadioButton
    private lateinit var ramSlider: Slider
    private lateinit var ramLabel: TextView
    private lateinit var ramMax: TextView
    private lateinit var portInput: TextInputEditText
    private lateinit var onlineSwitch: MaterialSwitch
    private lateinit var eulaCheck: MaterialCheckBox
    private lateinit var btnFinish: MaterialButton
    private lateinit var customJavaDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = Prefs.server(id)
        if (found == null) {
            Ui.toast(this, "服务器不存在")
            finish()
            return
        }
        profile = found

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = "新建服务器"
        toolbar.subtitle = profile.displayTitle
        toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        setupVersionDropdown()
        setupListeners()
        restoreState()
        updateAll()
    }

    private fun bindViews() {
        coreDetected = findViewById(R.id.coreDetected)
        cardVersion = findViewById(R.id.cardVersion)
        cardJava = findViewById(R.id.cardJava)
        cardBasics = findViewById(R.id.cardBasics)
        versionDropdown = findViewById(R.id.versionDropdown)
        versionHint = findViewById(R.id.versionHint)
        javaRequired = findViewById(R.id.javaRequired)
        javaStatus = findViewById(R.id.javaStatus)
        btnInstallJava = findViewById(R.id.btnInstallJava)
        javaProgressBar = findViewById(R.id.javaProgressBar)
        optionCn = findViewById(R.id.optionCn)
        optionOfficial = findViewById(R.id.optionOfficial)
        optionCustom = findViewById(R.id.optionCustom)
        radioCn = findViewById(R.id.radioCn)
        radioOfficial = findViewById(R.id.radioOfficial)
        radioCustom = findViewById(R.id.radioCustom)
        ramSlider = findViewById(R.id.ramSlider)
        ramLabel = findViewById(R.id.ramLabel)
        ramMax = findViewById(R.id.ramMax)
        portInput = findViewById(R.id.portInput)
        onlineSwitch = findViewById(R.id.onlineSwitch)
        eulaCheck = findViewById(R.id.eulaCheck)
        btnFinish = findViewById(R.id.btnFinish)
        customJavaDesc = findViewById(R.id.customJavaDesc)
    }

    private fun setupVersionDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            McVersions.versions
        )
        versionDropdown.setAdapter(adapter)
        versionDropdown.setOnItemClickListener { _, _, position, _ ->
            onVersionSelected(McVersions.versions[position])
        }
        findViewById<View>(R.id.btnCustomVersion).setOnClickListener {
            Ui.input(this, "手动输入版本", "例如 1.20.1 / 1.12.2 / 26.1", version) { input ->
                if (input.isBlank()) return@input
                onVersionSelected(input)
            }
        }
    }

    private fun setupListeners() {
        findViewById<MaterialButton>(R.id.btnPickCore).setOnClickListener { pickCore() }

        optionCn.setOnClickListener { selectOption(1) }
        optionOfficial.setOnClickListener { selectOption(2) }
        optionCustom.setOnClickListener { selectOption(3) }
        radioCn.setOnClickListener { optionCn.performClick() }
        radioOfficial.setOnClickListener { optionOfficial.performClick() }
        radioCustom.setOnClickListener { optionCustom.performClick() }

        btnInstallJava.setOnClickListener { installJava() }

        val maxRam = ((ServerOps.maxRamForDevice() / 256) * 256).coerceAtLeast(1024)
        ramSlider.valueTo = maxRam.toFloat()
        ramMax.text = "上限 ${maxRam}MB"
        ramSlider.addOnChangeListener { _, value, _ ->
            ramLabel.text = "分配内存：${value.toInt()} MB"
            updateFinishState()
        }
        onlineSwitch.setOnCheckedChangeListener { _, _ -> updateFinishState() }
        eulaCheck.setOnCheckedChangeListener { _, _ -> updateFinishState() }
        btnFinish.setOnClickListener { finishSetup() }
    }

    private fun restoreState() {
        // Importing an existing directory should keep its server.properties values.
        if (profile.propertiesFile.exists()) {
            ServerOps.readFromProperties(profile)
            Prefs.upsert(profile)
        }
        if (profile.jar.isNotBlank() && profile.jarFile.exists()) {
            coreFile = profile.jarFile
            coreInfo = CoreDetector.inspect(profile.jarFile)
            coreDetected.text = describeCore(profile.jarFile, coreInfo!!)
        }
        if (profile.mcVersion.isNotBlank()) {
            version = profile.mcVersion
            versionDropdown.setText(version, false)
        } else {
            val detected = coreInfo?.version
            if (!detected.isNullOrBlank()) {
                version = detected
                versionDropdown.setText(version, false)
            }
        }
        val savedRuntime = Prefs.runtime(profile.javaId)
        if (savedRuntime != null) {
            runtime = savedRuntime
            option = 3
            customJavaDesc.text = "当前：${savedRuntime.label}"
        }
        ramSlider.value = (profile.ramMb.coerceIn(512, ramSlider.valueTo.toInt()) / 256 * 256)
            .coerceAtLeast(512).toFloat()
        ramLabel.text = "分配内存：${ramSlider.value.toInt()} MB"
        portInput.setText(if (profile.port in 1..65535) profile.port.toString() else "25565")
        onlineSwitch.isChecked = profile.onlineMode
        eulaCheck.isChecked = profile.eula
    }

    private fun updateAll() {
        updateCoreAndVersion()
        updateJavaSection()
        updateFinishState()
    }

    private fun describeCore(file: File, info: CoreInfo): String {
        val versionText = info.version ?: profile.mcVersion.ifBlank { "版本未识别" }
        val builder = StringBuilder()
        builder.append("已导入：").append(info.type.label).append(" · ").append(versionText)
        if (info.classPath.isNotEmpty()) builder.append("（启动器核心）")
        builder.append("\n").append(file.name).append(" · ").append(FileUtils.humanSize(file.length()))
        val missing = missingDependencies(info)
        if (missing.isNotEmpty()) {
            builder.append("\n⚠ 缺少依赖：").append(missing.joinToString("、"))
        }
        return builder.toString()
    }

    private fun pickCore() {
        FilePicker.pickJar(this, profile.dir) { picked ->
            val info = CoreDetector.inspect(picked)
            if (!info.validJar) {
                Ui.confirm(
                    this,
                    "不是有效的 jar 文件",
                    "无法读取 ${picked.name} 的 zip 结构，请确认这是一个完整的 .jar 文件。"
                ) { }
                return@pickJar
            }
            if (info.isClient) {
                Ui.confirm(this, "这是客户端核心", "检测到这是 Minecraft 客户端 jar，无法用于开服。是否仍要导入？") {
                    copyCore(picked, info)
                }
            } else {
                copyCore(picked, info)
            }
        }
    }

    private fun copyCore(source: File, info: CoreInfo) {
        val progress = Ui.progress(this, "正在导入服务端核心", cancelable = false)
        lifecycleScope.launch {
            val dest = File(profile.dir, "server.jar")
            val ok = withContext(Dispatchers.IO) {
                if (source.absolutePath == dest.absolutePath) true else FileUtils.copy(source, dest, true)
            }
            progress.dismiss()
            if (!ok) {
                Ui.toast(this@SetupActivity, "复制失败，请检查存储权限")
                return@launch
            }
            coreFile = dest
            coreInfo = info
            profile.jar = "server.jar"
            profile.coreType = info.type.label
            if (info.version != null) {
                profile.mcVersion = info.version
                version = info.version
                versionDropdown.setText(version, false)
            }
            Prefs.upsert(profile)
            updateAll()
            val missing = missingDependencies(info)
            if (missing.isNotEmpty()) {
                Ui.confirm(
                    this@SetupActivity,
                    "这是启动器核心，缺少依赖文件",
                    "该核心的 MANIFEST 通过 Class-Path 加载真正的服务端：\n\n" +
                        missing.joinToString("\n") +
                        "\n\n请把原服务器目录中的 bin/、libraries/、asm/ 等依赖一起放到服务器目录，否则启动会失败。\n\n可先用 MT 管理器把原服务器整个目录复制过来，再导入核心。"
                ) { }
            }
            if (info.version == null) {
                Ui.toast(this@SetupActivity, "未能自动识别版本，请手动选择")
            }
        }
    }

    private fun missingDependencies(info: CoreInfo): List<String> {
        if (info.classPath.isEmpty()) return emptyList()
        return info.classPath.filter { !File(profile.dir, it).exists() }
    }

    private fun onVersionSelected(value: String) {
        val v = value.trim()
        if (v.isBlank()) return
        version = v
        versionDropdown.setText(v, false)
        val required = McVersions.requiredJava(v)
        javaRequired.text = "Minecraft $v 需要 ${McVersions.javaLabel(required)}"
        if (runtime != null && runtime!!.major != required) {
            runtime = null
            option = 0
            javaStatus.text = ""
        }
        val installed = JavaManager.find(required)
        versionHint.text = buildString {
            append("该版本推荐 ${McVersions.javaLabel(required)}")
            if (installed != null) append("，本机已安装 ${installed.label}")
            append("。版本决定 Java 版本，选错会导致服务器无法启动。")
        }
        updateAll()
    }

    private fun selectOption(n: Int) {
        if (version.isBlank()) {
            Ui.toast(this, "请先选择 Minecraft 版本")
            return
        }
        option = n
        radioCn.isChecked = n == 1
        radioOfficial.isChecked = n == 2
        radioCustom.isChecked = n == 3
        setCardSelected(optionCn, n == 1)
        setCardSelected(optionOfficial, n == 2)
        setCardSelected(optionCustom, n == 3)
        val required = McVersions.requiredJava(version)
        when (n) {
            1, 2 -> {
                btnInstallJava.visibility = View.VISIBLE
                btnInstallJava.text = if (runtime == null) {
                    "下载并配置 ${McVersions.javaLabel(required)}"
                } else {
                    "重新下载并安装 ${McVersions.javaLabel(required)}"
                }
                if (runtime == null) {
                    val installed = JavaManager.find(required)
                    if (installed != null) {
                        runtime = installed
                        javaStatus.text = "已检测到 ${installed.label}，将直接使用（无需重复下载）"
                    } else {
                        javaStatus.text = if (n == 1) {
                            "将从清华大学 TUNA 镜像下载 ${McVersions.javaLabel(required)} + Ubuntu 运行环境"
                        } else {
                            "将从 Adoptium 官方源下载 ${McVersions.javaLabel(required)} + Ubuntu 运行环境"
                        }
                    }
                } else {
                    javaStatus.text = "已就绪：${runtime!!.label}（${runtime!!.versionText}）"
                }
            }
            3 -> {
                btnInstallJava.visibility = View.GONE
                pickInstalledRuntime()
            }
        }
        updateJavaSection()
        updateFinishState()
    }

    private fun pickInstalledRuntime() {
        val list = JavaManager.installed()
        if (list.isEmpty()) {
            Ui.confirm(
                this,
                "还没有已配置的 Java",
                "请先在「Java 环境」中安装一个运行时，然后再回来选择。"
            ) {
                startActivity(Intent(this, JavaActivity::class.java))
            }
            runtime = null
            updateJavaSection()
            updateFinishState()
            return
        }
        val labels = list.map { "${it.label}（${it.versionText}）" }
        Ui.list(this, "选择已配置好的 Java", labels) { index ->
            val picked = list[index]
            val required = McVersions.requiredJava(version)
            val warn = !McVersions.isJavaEnough(required, picked.major)
            if (warn) {
                Ui.confirm(
                    this,
                    "Java 版本可能不匹配",
                    "Minecraft $version 推荐 ${McVersions.javaLabel(required)}，你选择的是 ${McVersions.javaLabel(picked.major)}，服务器可能无法启动。仍要使用吗？"
                ) {
                    applyRuntime(picked)
                }
            } else {
                applyRuntime(picked)
            }
        }
    }

    private fun applyRuntime(rt: JavaRuntime) {
        runtime = rt
        customJavaDesc.text = "当前：${rt.label}"
        javaStatus.text = "已选择：${rt.label}（${rt.versionText}）"
        javaStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        updateJavaSection()
        updateFinishState()
    }

    private fun updateCoreAndVersion() {
        val hasCore = coreFile?.exists() == true
        cardVersion.alpha = if (hasCore || version.isNotBlank()) 1f else 0.45f
        if (hasCore) {
            val info = coreInfo ?: CoreDetector.inspect(coreFile!!)
            coreDetected.text = describeCore(coreFile!!, info)
            coreDetected.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (missingDependencies(info).isEmpty()) R.color.success else R.color.warning
                )
            )
        } else if (profile.jar.isNotBlank() && profile.jarFile.exists()) {
            coreDetected.text = "服务器核心：${profile.jar}"
        } else {
            coreDetected.text = "尚未导入服务端核心"
            coreDetected.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        }
        if (version.isNotBlank()) {
            val required = McVersions.requiredJava(version)
            javaRequired.text = "Minecraft $version 需要 ${McVersions.javaLabel(required)}"
            javaRequired.setTextColor(ContextCompat.getColor(this, R.color.primary))
            cardJava.alpha = 1f
        } else {
            javaRequired.text = "请先选择 Minecraft 版本"
            javaRequired.setTextColor(ContextCompat.getColor(this, R.color.warning))
            cardJava.alpha = 0.45f
        }
        cardBasics.alpha = if (runtime != null) 1f else 0.45f
    }

    private fun updateJavaSection() {
        if (runtime != null) {
            javaStatus.text = "已就绪：${runtime!!.label}（${runtime!!.versionText}）"
            javaStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            cardBasics.alpha = 1f
        } else if (option == 0) {
            javaStatus.text = "请从上面三种方式中选择一种"
            javaStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        } else if (option != 3) {
            javaStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            val required = McVersions.requiredJava(version)
            javaStatus.text = "将安装 ${McVersions.javaLabel(required)}"
        }
        setCardSelected(optionCn, option == 1)
        setCardSelected(optionOfficial, option == 2)
        setCardSelected(optionCustom, option == 3)
        radioCn.isChecked = option == 1
        radioOfficial.isChecked = option == 2
        radioCustom.isChecked = option == 3
        val required = if (version.isBlank()) 21 else McVersions.requiredJava(version)
        btnInstallJava.text = if (runtime == null) {
            "下载并配置 ${McVersions.javaLabel(required)}"
        } else {
            "重新下载并安装 ${McVersions.javaLabel(required)}"
        }
        btnInstallJava.visibility = if (option == 1 || option == 2) View.VISIBLE else View.GONE
    }

    private fun installJava() {
        if (version.isBlank()) {
            Ui.toast(this, "请先选择 Minecraft 版本")
            return
        }
        if (installJob?.isActive == true) return
        val required = McVersions.requiredJava(version)
        val source = if (option == 2) Prefs.SOURCE_OFFICIAL else Prefs.SOURCE_CN
        javaProgressBar.visibility = View.VISIBLE
        javaProgressBar.progress = 0

        val progress = Ui.progress(this, "准备安装 ${McVersions.javaLabel(required)}")
        progress.setDetail(JavaCatalog.sourceLabel(source))
        installJob = lifecycleScope.launch {
            try {
                val rt = JavaManager.install(this@SetupActivity, required, source) { stage, percent ->
                    runOnUiThread {
                        progress.setStage(stage)
                        progress.setProgress(percent)
                        javaProgressBar.progress = percent
                        javaStatus.text = stage
                    }
                }
                runtime = rt
                option = if (source == Prefs.SOURCE_OFFICIAL) 2 else 1
                progress.dismiss()
                javaProgressBar.visibility = View.GONE
                javaStatus.text = "已就绪：${rt.label}（${rt.versionText}）"
                javaStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.success))
                customJavaDesc.text = "当前：${rt.label}"
                updateJavaSection()
                Ui.toast(this@SetupActivity, "${McVersions.javaLabel(required)} 安装完成")
            } catch (e: Throwable) {
                android.util.Log.e("McPanel", "Java install failed", e)
                progress.dismiss()
                javaProgressBar.visibility = View.GONE
                javaStatus.text = "安装失败：${e.message}"
                javaStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.danger))
                Ui.toast(this@SetupActivity, "安装失败：${e.message}")
            } finally {
                updateFinishState()
            }
        }
        progress.setCancelHandler {
            installJob?.cancel()
            progress.dismiss()
            javaProgressBar.visibility = View.GONE
            javaStatus.text = "已取消安装"
        }
    }

    private fun updateFinishState() {
        val hasCore = coreFile?.exists() == true
        val ready = hasCore && version.isNotBlank() && runtime != null && eulaCheck.isChecked
        btnFinish.isEnabled = ready
    }

    private fun finishSetup() {
        val hasCore = coreFile?.exists() == true
        if (!hasCore) {
            Ui.toast(this, "请先导入服务端核心")
            return
        }
        if (version.isBlank()) {
            Ui.toast(this, "请选择 Minecraft 版本")
            return
        }
        val rt = runtime
        if (rt == null) {
            Ui.toast(this, "请先安装或选择 Java")
            return
        }
        if (!eulaCheck.isChecked) {
            Ui.toast(this, "请先同意 Minecraft EULA")
            return
        }
        val port = portInput.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Ui.toast(this, "端口需在 1-65535 之间")
            return
        }
        profile.jar = "server.jar"
        profile.mcVersion = version
        profile.coreType = coreInfo?.type?.label ?: profile.coreType
        profile.javaId = rt.id
        profile.ramMb = ramSlider.value.toInt()
        profile.port = port
        profile.onlineMode = onlineSwitch.isChecked
        if (profile.motd.isBlank()) profile.motd = profile.displayTitle
        profile.eula = true
        profile.lastStart = 0L
        ServerOps.prepareDirectory(profile)
        ServerOps.applyToProperties(profile)
        ServerOps.setEula(profile, true)
        Prefs.upsert(profile)

        startActivity(Intent(this, ServerActivity::class.java).putExtra(ServerActivity.EXTRA_ID, profile.id))
        finish()
    }

    private fun setCardSelected(card: MaterialCardView, selected: Boolean) {
        card.strokeColor = ContextCompat.getColor(
            this,
            if (selected) R.color.primary else R.color.surface_stroke
        )
        card.strokeWidth = Ui.dp(this, if (selected) 2f else 1f)
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, if (selected) R.color.surface_high else R.color.bg_elevated)
        )
    }

    override fun onDestroy() {
        installJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
