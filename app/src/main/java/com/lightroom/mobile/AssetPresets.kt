package com.lightroom.mobile

import android.content.Context

/** Loads the bundled Adobe preset packs (xmp files under assets/presets, one folder per category) at startup. */
object AssetPresets {
    private const val ROOT = "presets"

    fun load(context: Context): List<Preset> {
        val assets = context.assets
        val categories = runCatching { assets.list(ROOT)?.toList() }.getOrNull().orEmpty()
        val result = mutableListOf<Preset>()
        categories.sorted().forEach { category ->
            val files = runCatching { assets.list("$ROOT/$category") }.getOrNull().orEmpty()
            files.filter { it.endsWith(".xmp", ignoreCase = true) }.sorted().forEach { file ->
                val text = runCatching { assets.open("$ROOT/$category/$file").bufferedReader().use { it.readText() } }.getOrNull() ?: return@forEach
                val parsed = PresetImporter.parse(text) ?: return@forEach
                val name = file.substringBeforeLast('.').substringAfter('_').ifBlank { file }.replace('_', ' ').take(26)
                result.add(Preset("asset-$category-$file", name, category, parsed.state, builtIn = true, fields = parsed.fields, rawWhiteBalance = parsed.rawWhiteBalance))
            }
        }
        return result
    }
}
