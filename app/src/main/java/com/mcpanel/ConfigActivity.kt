package com.mcpanel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.mcpanel.core.JavaManager
import com.mcpanel.core.McVersions
import com.mcpanel.core.Prefs
import com.mcpanel.core.PropertiesFile
import com.mcpanel.core.ServerOps
import com.mcpanel.core.ServerProfile
import com.mcpanel.core.ServerRunner
import com.mcpanel.ui.Ui

class ConfigActivity : AppCompatActivity() {

    private lateinit var profile: ServerProfile
    private lateinit var portInput: TextInputEditText
    private lateinit var seedInput: TextInputEditText
    private lateinit var randomTickInput: TextInputEditText
    private lateinit var motdInput: TextInputEditText
    private lateinit var maxPlayersInput: TextInputEditText
    private lateinit var viewDistanceInput: TextInputEditText
    private lateinit var gamemodeInput: MaterialAutoCompleteTextView
    private lateinit var difficultyInput: MaterialAutoCompleteTextView
    private lateinit var ramSlider: Slider
    private lateinit var ramLabel: TextView
    private lateinit var ramMax: TextView
    private lateinit var onlineSwitch: MaterialSwitch
    private lateinit var onlineDesc: TextView
    private lateinit var restartHint: TextView

    private val gamemodes = listOf(
        "survival" to "生存模式",
        "creative" to "创造模式",
        "adventure" to "冒险模式",
        "spectator" to "旁观模式"
    )
    private val difficulties = listOf(
        "peaceful" to "和平",
        "easy" to "简单",
        "normal" to "普通",
        "hard" to "困难"
    )
    private var gamemodeIndex = 0
    private var difficultyIndex = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        val found = Prefs.server(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            Ui.toast(this, "服务器不存在")
            finish()
            return
        }
        profile = found
        bindViews()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = "服务器配置 · ${profile.displayTitle}"
        toolbar.setNavigationOnClickListener { finish() }

        setupDropdowns()
        loadValues()
        setupListeners()
    }

    private fun bindViews() {
        portInput = findViewById(R.id.portInput)
        seedInput = findViewById(R.id.seedInput)
        randomTickInput = findViewById(R.id.randomTickInput)
        motdInput = findViewById(R.id.motdInput)
        maxPlayersInput = findViewById(R.id.maxPlayersInput)
        viewDistanceInput = findViewById(R.id.viewDistanceInput)
        gamemodeInput = findViewById(R.id.gamemodeInput)
        difficultyInput = findViewById(R.id.difficultyInput)
        ramSlider = findViewById(R.id.ramSlider)
        ramLabel = findViewById(R.id.ramLabel)
        ramMax = findViewById(R.id.ramMax)
        onlineSwitch = findViewById(R.id.onlineSwitch)
        onlineDesc = findViewById(R.id.onlineDesc)
        restartHint = findViewById(R.id.restartHint)
    }

    private fun setupDropdowns() {
        gamemodeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, gamemodes.map { it.second })
        )
        gamemodeInput.setOnItemClickListener { _, _, position, _ ->
            gamemodeIndex = position
            gamemodeInput.setText(gamemodes[position].second, false)
        }
        difficultyInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, difficulties.map { it.second })
        )
        difficultyInput.setOnItemClickListener { _, _, position, _ ->
            difficultyIndex = position
            difficultyInput.setText(difficulties[position].second, false)
        }
    }

    private fun loadValues() {
        ServerOps.readFromProperties(profile)
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        portInput.setText(profile.port.toString())
        seedInput.setText(props.get("level-seed", profile.seed))
        randomTickInput.setText(profile.randomTickSpeed.toString())
        motdInput.setText(props.get("motd", profile.displayTitle))
        maxPlayersInput.setText(props.getInt("max-players", 20).toString())
        viewDistanceInput.setText(props.getInt("view-distance", 8).toString())
        gamemodeIndex = gamemodes.indexOfFirst { it.first == profile.gamemode }.coerceAtLeast(0)
        difficultyIndex = difficulties.indexOfFirst { it.first == profile.difficulty }.coerceAtLeast(2)
        gamemodeInput.setText(gamemodes[gamemodeIndex].second, false)
        difficultyInput.setText(difficulties[difficultyIndex].second, false)

        val maxRam = ((ServerOps.maxRamForDevice() / 256) * 256).coerceAtLeast(1024)
        ramSlider.valueTo = maxRam.toFloat()
        ramMax.text = "上限 ${maxRam}MB"
        val ram = ((profile.ramMb.coerceIn(512, maxRam) - 512) / 256 * 256 + 512)
        ramSlider.value = ram.toFloat()
        ramLabel.text = "分配内存：$ram MB"
        onlineSwitch.isChecked = profile.onlineMode
        updateOnlineDesc()
        updateRestartHint()
    }

    private fun setupListeners() {
        ramSlider.addOnChangeListener { _, value, _ ->
            ramLabel.text = "分配内存：${value.toInt()} MB"
        }
        onlineSwitch.setOnCheckedChangeListener { _, checked ->
            updateOnlineDesc()
            updateRestartHint()
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnRaw).setOnClickListener {
            startActivity(
                Intent(this, TextEditorActivity::class.java)
                    .putExtra(TextEditorActivity.EXTRA_PATH, profile.propertiesFile.absolutePath)
                    .putExtra(TextEditorActivity.EXTRA_TITLE, "server.properties")
            )
        }
    }

    private fun updateOnlineDesc() {
        if (onlineSwitch.isChecked) {
            onlineDesc.text = "已开启，仅正版账号可进入（需联网验证）"
            onlineDesc.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            onlineDesc.text = "已关闭，离线玩家可进入"
            onlineDesc.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        }
    }

    private fun updateRestartHint() {
        restartHint.visibility = if (ServerRunner.isRunning(profile.id)) View.VISIBLE else View.GONE
        restartHint.text = "服务器正在运行，修改端口 / 在线验证 / 内存后需要重启服务器才会生效。"
    }

    private fun save() {
        val port = portInput.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Ui.toast(this, "端口需在 1-65535 之间")
            return
        }
        val randomTick = randomTickInput.text?.toString()?.trim()?.toIntOrNull() ?: 3
        if (randomTick < 0) {
            Ui.toast(this, "随机刻不能为负数")
            return
        }
        val maxPlayers = maxPlayersInput.text?.toString()?.trim()?.toIntOrNull() ?: 20
        val viewDistance = (viewDistanceInput.text?.toString()?.trim()?.toIntOrNull() ?: 8).coerceIn(4, 32)

        val wasOnline = profile.onlineMode
        profile.port = port
        profile.seed = seedInput.text?.toString()?.trim().orEmpty()
        profile.randomTickSpeed = randomTick
        profile.motd = motdInput.text?.toString()?.trim().orEmpty()
        profile.maxPlayers = maxPlayers.coerceIn(1, 1000)
        profile.viewDistance = viewDistance
        profile.gamemode = gamemodes[gamemodeIndex].first
        profile.difficulty = difficulties[difficultyIndex].first
        profile.ramMb = ramSlider.value.toInt()
        profile.onlineMode = onlineSwitch.isChecked
        profile.eula = ServerOps.isEulaAccepted(profile)

        ServerOps.prepareDirectory(profile)
        ServerOps.applyToProperties(profile)
        val props = PropertiesFile(profile.propertiesFile)
        props.load()
        props.set("max-players", profile.maxPlayers.toString())
        props.set("view-distance", profile.viewDistance.toString())
        props.save()
        Prefs.upsert(profile)
        updateRestartHint()

        val message = buildString {
            append("配置已保存。")
            if (wasOnline != profile.onlineMode) {
                append("\n正版验证已")
                append(if (profile.onlineMode) "开启" else "关闭")
                append("，重启服务器后生效。")
            }
            append("\n随机刻将在服务器启动后自动应用（gamerule randomTickSpeed ${profile.randomTickSpeed}）。")
        }
        Ui.confirm(this, "保存成功", message) { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (::profile.isInitialized && profile.propertiesFile.exists()) {
            val props = PropertiesFile(profile.propertiesFile)
            props.load()
            portInput.setText(props.getInt("server-port", profile.port).toString())
            motdInput.setText(props.get("motd", profile.motd))
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
