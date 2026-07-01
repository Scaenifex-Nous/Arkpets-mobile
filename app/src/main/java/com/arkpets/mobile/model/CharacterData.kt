package com.arkpets.mobile.model

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class ModelAsset(
    val atlasPath: String,
    val skelPath: String,
    val pngPath: String,
    val characterKey: String,
    val displayName: String,
    val type: String
)

object CharacterData {
    private const val TAG = "CharData"
    val characters = mutableListOf<ModelAsset>()

    fun loadFromAssets(ctx: Context) {
        characters.clear()

        // First, load the JSON to get Chinese names
        val nameMap = mutableMapOf<String, String>()
        try {
            val json = ctx.assets.open("models_data.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: JSONObject()
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val info = data.optJSONObject(key) ?: continue
                val name = info.optString("name", "")
                val skinName = info.optString("skinGroupName", "")
                if (name.isNotEmpty()) {
                    nameMap[key] = if (skinName.isNotEmpty() && skinName != "默认服装") "$name·$skinName" else name
                }
            }
            Log.i(TAG, "Loaded ${nameMap.size} names from JSON")
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed, using directory names: ${e.message}")
        }

        // Now scan model directories
        for (baseDir in listOf("models", "models_enemies", "models_illust")) {
            try {
                val entries = ctx.assets.list(baseDir) ?: continue
                for (entry in entries) {
                    try {
                        val subDir = "$baseDir/$entry"
                        val files = ctx.assets.list(subDir) ?: continue
                        val atlasFile = files.find { it.endsWith(".atlas") }
                        val skelFile = files.find { it.endsWith(".skel") }
                        val pngFile = files.find { it.endsWith(".png") }
                        if (atlasFile == null || skelFile == null || pngFile == null) continue

                        val displayName = nameMap[entry] ?: entry.replace("_", " ")
                        val type = when (baseDir) {
                            "models_enemies" -> "Enemy"
                            "models_illust" -> "DynIllust"
                            else -> "Operator"
                        }
                        characters.add(ModelAsset(
                            atlasPath = "$subDir/$atlasFile",
                            skelPath = "$subDir/$skelFile",
                            pngPath = "$subDir/$pngFile",
                            characterKey = entry,
                            displayName = displayName,
                            type = type
                        ))
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        characters.sortBy { it.displayName }
        Log.i(TAG, "Total: ${characters.size} characters")
    }

    fun getCharacter(index: Int): ModelAsset? =
        if (index in characters.indices) characters[index] else null
}
