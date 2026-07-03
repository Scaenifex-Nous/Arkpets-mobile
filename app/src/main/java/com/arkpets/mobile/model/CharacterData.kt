package com.arkpets.mobile.model

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ModelAsset(
    val atlasPath: String,
    val skelPath: String,
    val pngPath: String,
    val characterKey: String,
    val displayName: String,
    val type: String // "Operator" | "Enemy" | "DynIllust"
)

object CharacterData {
    private const val TAG = "CharData"
    private const val JSON_FILE = "models_data.json"

    /** Thread-safe loaded result — null until first successful load */
    @Volatile
    private var loaded: Boolean = false

    @Volatile
    var characters: List<ModelAsset> = emptyList()
        private set

    // ---- Public API ----

    /** Load model list from assets. Idempotent — returns cached result after first call. */
    fun loadFromAssets(ctx: Context): List<ModelAsset> {
        if (loaded) return characters
        synchronized(this) {
            if (loaded) return characters // double-check

            val nameMap = loadNameMap(ctx)
            val models = scanModelDirectories(ctx, nameMap)
            characters = models.sortedBy { it.displayName }
            loaded = true

            Log.i(TAG, "Loaded ${characters.size} characters (${countByType()})")
            return characters
        }
    }

    /** Force reload (e.g. after app update with new models) */
    fun reload(ctx: Context): List<ModelAsset> {
        loaded = false
        characters = emptyList()
        return loadFromAssets(ctx)
    }

    fun getCharacter(index: Int): ModelAsset? =
        characters.getOrNull(index)

    fun characterCount(): Int = characters.size

    // ---- Private helpers ----

    private fun countByType(): String {
        val ops = characters.count { it.type == "Operator" }
        val enemies = characters.count { it.type == "Enemy" }
        val illusts = characters.count { it.type == "DynIllust" }
        return "$ops operators, $enemies enemies, $illusts dyn-illusts"
    }

    /** Parse models_data.json → map of directory-key → display-name */
    private fun loadNameMap(ctx: Context): Map<String, String> {
        val map = ConcurrentHashMap<String, String>()
        try {
            val json = ctx.assets.open(JSON_FILE).bufferedReader().use { it.readText() }
            val data = JSONObject(json).optJSONObject("data") ?: return map
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val info = data.optJSONObject(key) ?: continue
                val name = info.optString("name", "").takeIf { it.isNotEmpty() } ?: continue
                val skin = info.optString("skinGroupName", "")
                map[key] = if (skin.isNotEmpty() && skin != "默认服装") "$name·$skin" else name
            }
            Log.i(TAG, "Parsed ${map.size} display names from JSON")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse $JSON_FILE, falling back to dir names: ${e.message}")
        }
        return map
    }

    /** Walk assets/models*, find .atlas+.skel+.png triplets */
    private fun scanModelDirectories(
        ctx: Context,
        nameMap: Map<String, String>
    ): List<ModelAsset> {
        val results = mutableListOf<ModelAsset>()

        val baseDirs = listOf(
            "models" to "Operator",
            "models_enemies" to "Enemy",
            "models_illust" to "DynIllust"
        )

        for ((baseDir, type) in baseDirs) {
            val entries = ctx.assets.list(baseDir) ?: continue
            for (entry in entries) {
                try {
                    val subDir = "$baseDir/$entry"
                    val files = ctx.assets.list(subDir) ?: continue

                    // Find required triplet
                    val atlasFile = files.firstOrNull { it.endsWith(".atlas") }
                    val skelFile = files.firstOrNull { it.endsWith(".skel") }
                    val pngFile = files.firstOrNull { it.endsWith(".png") }

                    if (atlasFile == null || skelFile == null || pngFile == null) {
                        // Skip incomplete model directories
                        continue
                    }

                    val displayName = nameMap.getOrDefault(
                        entry,
                        entry.replace('_', ' ')
                    )

                    results.add(
                        ModelAsset(
                            atlasPath = "$subDir/$atlasFile",
                            skelPath = "$subDir/$skelFile",
                            pngPath = "$subDir/$pngFile",
                            characterKey = entry,
                            displayName = displayName,
                            type = type
                        )
                    )
                } catch (_: Exception) {
                    // Skip corrupt entries silently
                }
            }
        }

        return results
    }
}
