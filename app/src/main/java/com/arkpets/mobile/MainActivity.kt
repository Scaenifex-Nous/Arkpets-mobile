/** ArkPets — Arknights desktop pet for Android. @author Scaenifex */
package com.arkpets.mobile

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arkpets.mobile.databinding.ActivityMainBinding
import com.arkpets.mobile.model.CharacterData
import com.arkpets.mobile.model.ModelAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    // ---- ViewBinding ----
    private lateinit var binding: ActivityMainBinding

    // ---- State ----
    private lateinit var prefs: SharedPreferences
    private var allChars = listOf<ModelAsset>()
    private var allNames = listOf<String>()
    private var filteredIndices = listOf<Int>()
    private var selectedIndex = -1

    // ---- Adapter (reused) ----
    private var listAdapter: ArrayAdapter<String>? = null

    // ---- Search debounce ----
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        initUI()
        loadCharactersAsync()
    }

    // ========================================================================
    // Character loading (coroutine, off main thread)
    // ========================================================================

    private fun loadCharactersAsync() {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.loading)
            binding.startButton.isEnabled = false

            val loaded = withContext(Dispatchers.IO) {
                CharacterData.loadFromAssets(this@MainActivity)
            }

            allChars = loaded
            allNames = loaded.map { it.displayName }
            filteredIndices = allNames.indices.toList()
            updateList()

            binding.startButton.isEnabled = allChars.isNotEmpty()
            binding.modelCount.text = allChars.size.toString()
            binding.statusText.text = if (allChars.isNotEmpty())
                getString(R.string.ready) else getString(R.string.no_data)
        }
    }

    // ========================================================================
    // UI Initialization
    // ========================================================================

    private fun initUI() {
        // ---- Gravity / Touch toggles ----
        binding.gravitySwitch.isChecked = prefs.getBoolean(KEY_GRAVITY, true)
        binding.gravitySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_GRAVITY, checked).apply()
        }
        binding.touchSwitch.isChecked = prefs.getBoolean(KEY_TOUCH, true)
        binding.touchSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_TOUCH, checked).apply()
        }
        binding.flightSwitch.isChecked = false  // always start OFF
        prefs.edit().putBoolean(KEY_FLIGHT, false).apply()  // force save OFF state
        binding.flightSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_FLIGHT, checked).apply()
            if (checked) {
                // Save gravity state, force switch OFF + slider 0, disable both
                prefs.edit().putInt(KEY_GRAVITY_SAVED, binding.gravitySeekbar.progress).apply()
                prefs.edit().putBoolean(KEY_GRAVITY_SAVED_SW, binding.gravitySwitch.isChecked).apply()
                binding.gravitySwitch.isChecked = false
                binding.gravitySwitch.isEnabled = false
                binding.gravitySeekbar.progress = 0
                binding.gravitySeekbar.isEnabled = false
                binding.gravityLabel.text = getString(R.string.gravity_scale, 0f)
                prefs.edit().putInt(KEY_GRAVITY_PROGRESS, 0).putFloat(KEY_GRAVITY_SCALE, 0f)
                    .putBoolean(KEY_GRAVITY, false).apply()
            } else {
                // Restore gravity state, re-enable both
                val saved = prefs.getInt(KEY_GRAVITY_SAVED, DEFAULT_GRAVITY_PROGRESS)
                val savedSw = prefs.getBoolean(KEY_GRAVITY_SAVED_SW, true)
                binding.gravitySwitch.isChecked = savedSw
                binding.gravitySwitch.isEnabled = true
                binding.gravitySeekbar.progress = saved
                binding.gravitySeekbar.isEnabled = true
                binding.gravityLabel.text = getString(R.string.gravity_scale, saved / 100f)
                prefs.edit().putInt(KEY_GRAVITY_PROGRESS, saved).putFloat(KEY_GRAVITY_SCALE, saved / 100f)
                    .putBoolean(KEY_GRAVITY, savedSw).apply()
            }
        }

        // ---- Search with debounce ----
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                scheduleFilter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        // ---- Character list selection ----
        binding.characterList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            selectedIndex = filteredIndices.getOrElse(pos) { -1 }
            if (selectedIndex >= 0 && selectedIndex < allChars.size) {
                binding.selectedLabel.text = getString(R.string.selected, allChars[selectedIndex].displayName)
            }
            updateList() // refresh highlighting
        }

        // ---- Start / Stop buttons ----
        binding.startButton.setOnClickListener { onStartClicked() }
        binding.stopButton.setOnClickListener { onStopClicked() }

        // ---- Speed slider ----
        binding.speedSeekbar.progress = prefs.getInt(KEY_SPEED_PROGRESS, DEFAULT_SPEED_PROGRESS)
        binding.speedLabel.text = getString(R.string.walk_speed, binding.speedSeekbar.progress * 2)
        binding.speedSeekbar.setOnSeekBarChangeListener(
            createSeekBarListener { progress ->
                prefs.edit()
                    .putInt(KEY_SPEED_PROGRESS, progress)
                    .putFloat(KEY_WALK_SPEED, progress.toFloat() * 2f)
                    .apply()
                binding.speedLabel.text = getString(R.string.walk_speed, progress * 2)
            }
        )

        // ---- Gravity slider ----
        binding.gravitySeekbar.progress = prefs.getInt(KEY_GRAVITY_PROGRESS, DEFAULT_GRAVITY_PROGRESS)
        binding.gravityLabel.text = getString(R.string.gravity_scale, binding.gravitySeekbar.progress / 100f)
        binding.gravitySeekbar.setOnSeekBarChangeListener(
            createSeekBarListener { progress ->
                prefs.edit()
                    .putInt(KEY_GRAVITY_PROGRESS, progress)
                    .putFloat(KEY_GRAVITY_SCALE, progress / 100f)
                    .apply()
                binding.gravityLabel.text = getString(R.string.gravity_scale, progress / 100f)
            }
        )
    }

    // ========================================================================
    // Search with 300ms debounce
    // ========================================================================

    private fun scheduleFilter(query: String) {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = Runnable { filterList(query) }
        searchHandler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
    }

    private fun filterList(query: String) {
        filteredIndices = if (query.isEmpty()) {
            allNames.indices.toList()
        } else {
            allNames.indices.filter { idx ->
                allNames[idx].contains(query, ignoreCase = true)
            }
        }
        updateList()
    }

    // ========================================================================
    // List management
    // ========================================================================

    private fun updateList() {
        val names = filteredIndices.map { allNames[it] }
        if (listAdapter == null) {
            listAdapter = CharacterListAdapter(names)
            binding.characterList.adapter = listAdapter
        } else {
            listAdapter!!.clear()
            listAdapter!!.addAll(names)
            listAdapter!!.notifyDataSetChanged()
        }
    }

    /** Custom adapter with selected-item highlighting */
    private inner class CharacterListAdapter(items: List<String>) :
        ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, items) {

        override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = super.getView(pos, convertView, parent) as TextView
            val idx = filteredIndices.getOrNull(pos)
            val isSelected = idx != null && idx == selectedIndex

            view.setTextColor(TEXT_COLOR)
            view.setBackgroundColor(if (isSelected) SELECTED_BG else UNSELECTED_BG)
            view.textSize = TEXT_SIZE
            view.setPadding(PADDING, PADDING, PADDING, PADDING)
            return view
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================

    private fun onStartClicked() {
        if (selectedIndex < 0 || selectedIndex >= allChars.size) {
            Toast.makeText(this, R.string.select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            startOverlay()
        }
    }

    private fun onStopClicked() {
        stopService(Intent(this, PetOverlayService::class.java))
        binding.statusText.text = getString(R.string.stopped)
    }

    private fun startOverlay() {
        if (selectedIndex < 0) return
        prefs.edit().putBoolean(KEY_GRAVITY, binding.gravitySwitch.isChecked).apply()

        val intent = Intent(this, PetOverlayService::class.java).apply {
            putExtra(PetOverlayService.EXTRA_CHARACTER_INDEX, selectedIndex)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.statusText.text = getString(R.string.overlay_started)
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun createSeekBarListener(onChanged: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChanged(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    // ========================================================================
    // Constants
    // ========================================================================

    companion object {
        private const val PREFS_NAME = "arkpets"
        private const val KEY_GRAVITY = "gravity"
        private const val KEY_TOUCH = "touchEnabled"
        private const val KEY_FLIGHT = "flightMode"
        private const val KEY_SPEED_PROGRESS = "speedProgress"
        private const val KEY_WALK_SPEED = "walkSpeed"
        private const val KEY_GRAVITY_PROGRESS = "gravityProgress"
        private const val KEY_GRAVITY_SCALE = "gravityScale"
        private const val KEY_GRAVITY_SAVED = "gravitySaved"
        private const val KEY_GRAVITY_SAVED_SW = "gravitySavedSw"

        private const val DEFAULT_SPEED_PROGRESS = 175
        private const val DEFAULT_GRAVITY_PROGRESS = 100
        private const val SEARCH_DEBOUNCE_MS = 300L

        private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val SELECTED_BG = 0xFF444444.toInt()
        private const val UNSELECTED_BG = 0xFF000000.toInt()
        private const val TEXT_SIZE = 14f
        private const val PADDING = 12
    }
}
