/** ArkPets — Arknights desktop pet for Android. @author Scaenifex */
package com.arkpets.mobile

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkpets.mobile.model.CharacterData

class MainActivity : AppCompatActivity() {

    private lateinit var charList: ListView
    private lateinit var searchInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var gravitySwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var modelCount: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var prefs: SharedPreferences
    private var allNames = listOf<String>()
    private var filteredIndices = listOf<Int>()
    private var allChars = listOf<com.arkpets.mobile.model.ModelAsset>()
    private var selectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("arkpets", MODE_PRIVATE)

        charList = findViewById(R.id.character_list)
        searchInput = findViewById(R.id.search_input)
        startBtn = findViewById(R.id.start_button)
        stopBtn = findViewById(R.id.stop_button)
        gravitySwitch = findViewById(R.id.gravity_switch)
        statusText = findViewById(R.id.status_text)
        modelCount = findViewById(R.id.model_count)
        selectedLabel = findViewById(R.id.selected_label)

        startBtn.isEnabled = false
        statusText.text = "Loading..."
        Thread {
            loadCharacters()
            runOnUiThread {
                allChars = CharacterData.characters.toList()
                allNames = allChars.map { it.displayName }
                filteredIndices = allNames.indices.toList()
                updateList()
                startBtn.isEnabled = CharacterData.characters.isNotEmpty()
                modelCount.text = "${CharacterData.characters.size}"
                statusText.text = if (CharacterData.characters.isNotEmpty()) "就绪" else "无数据"
            }
        }.start()

        gravitySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("gravity", checked).apply()
        }

        val touchSwitch = findViewById<Switch>(R.id.touch_switch)
        touchSwitch.isChecked = prefs.getBoolean("touchEnabled", true)
        touchSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("touchEnabled", checked).apply()
        }

        // Search filter
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filterList(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        charList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            selectedIndex = if (pos in filteredIndices.indices) filteredIndices[pos] else -1
            if (selectedIndex >= 0 && selectedIndex < allChars.size) {
                selectedLabel.text = "已选: ${allChars[selectedIndex].displayName}"
            }
            updateList() // refresh to show highlight
        }

        startBtn.setOnClickListener {
            if (selectedIndex < 0 || selectedIndex >= CharacterData.characters.size) {
                Toast.makeText(this, "请先选择角色", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startOverlay()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, PetOverlayService::class.java))
            statusText.text = "已停止"
        }

        // Speed & gravity sliders
        val speedBar = findViewById<SeekBar>(R.id.speed_seekbar)
        val gravityBar = findViewById<SeekBar>(R.id.gravity_seekbar)
        val speedLabel = findViewById<TextView>(R.id.speed_label)
        val gravityLabel = findViewById<TextView>(R.id.gravity_label)
        speedBar.progress = prefs.getInt("speedProgress", 175)
        gravityBar.progress = prefs.getInt("gravityProgress", 100)
        speedLabel.text = "行走速度: ${speedBar.progress * 2}"
        gravityLabel.text = "重力: " + "%.2f".format(gravityBar.progress / 100f) + "x"
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("speedProgress", p).putFloat("walkSpeed", p.toFloat() * 2f).apply()
                speedLabel.text = "行走速度: ${p * 2}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        gravityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("gravityProgress", p).putFloat("gravityScale", p / 100f).apply()
                gravityLabel.text = "重力: " + "%.2f".format(p / 100f) + "x"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun loadCharacters() {
        try { CharacterData.loadFromAssets(this) } catch (e: Exception) { android.util.Log.e("ArkPets", "load failed", e) }
    }

    private fun filterList(query: String) {
        filteredIndices = allNames.indices.filter {
            query.isEmpty() || allNames[it].contains(query, ignoreCase = true)
        }
        updateList()
    }

    private var listAdapter: ArrayAdapter<String>? = null

    private fun updateList() {
        val names = filteredIndices.map { allNames[it] }
        if (listAdapter == null) {
            listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
                override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = super.getView(pos, v, parent) as TextView
                    val idx = filteredIndices.getOrNull(pos)
                    val isSelected = idx != null && idx == selectedIndex
                    view.setTextColor(0xFFFFFFFF.toInt())
                    view.setBackgroundColor(if (isSelected) 0xFF444444.toInt() else 0xFF000000.toInt())
                    view.textSize = 14f
                    view.setPadding(12, 12, 12, 12)
                    return view
                }
            }
            charList.adapter = listAdapter
        } else {
            listAdapter!!.clear()
            listAdapter!!.addAll(names)
            listAdapter!!.notifyDataSetChanged()
        }
    }

    private fun startOverlay() {
        if (selectedIndex < 0) return
        prefs.edit().putBoolean("gravity", gravitySwitch.isChecked).apply()
        val intent = Intent(this, PetOverlayService::class.java).apply {
            putExtra(PetOverlayService.EXTRA_CHARACTER_INDEX, selectedIndex)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "悬浮窗已启动"
    }
}
