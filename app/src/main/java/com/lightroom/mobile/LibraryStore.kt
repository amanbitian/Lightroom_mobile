package com.lightroom.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** SQLite-backed local catalog. Preset preferences stay in SharedPreferences because they are tiny. */
class LibraryStore(
    private val context: Context,
    databaseName: String = "lumaroom_catalog.db",
    prefsName: String = "local_library_v2",
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val database = CatalogDatabase(context, databaseName)

    init { migrateLegacyCatalogOnce() }

    fun loadPhotos(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        database.readableDatabase.query("photos", null, null, null, null, null, "imported_at DESC").use { cursor ->
            val uri = cursor.getColumnIndexOrThrow("uri")
            val name = cursor.getColumnIndexOrThrow("name")
            val rating = cursor.getColumnIndexOrThrow("rating")
            val flagged = cursor.getColumnIndexOrThrow("flagged")
            val rejected = cursor.getColumnIndex("rejected")
            val importedAt = cursor.getColumnIndexOrThrow("imported_at")
            while (cursor.moveToNext()) result += PhotoItem(
                uri = cursor.getString(uri), name = cursor.getString(name), rating = cursor.getInt(rating),
                flagged = cursor.getInt(flagged) == 1,
                rejected = rejected >= 0 && cursor.getInt(rejected) == 1,
                importedAt = cursor.getLong(importedAt),
            )
        }
        return result
    }

    fun savePhotos(photos: List<PhotoItem>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            // Update in place: CONFLICT_REPLACE deletes the old row, which cascades and wipes the photo's saved edit.
            photos.forEach { photo ->
                val values = photo.values()
                if (db.update("photos", values, "uri = ?", arrayOf(photo.uri)) == 0) db.insert("photos", null, values)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun loadEdit(uri: String): DevelopState {
        database.readableDatabase.query("edits", arrayOf("recipe"), "uri = ?", arrayOf(uri), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) return runCatching { developStateFromJson(JSONObject(cursor.getString(0))) }.getOrDefault(DevelopState())
        }
        return DevelopState()
    }

    fun saveEdit(uri: String, state: DevelopState) {
        database.writableDatabase.insertWithOnConflict("edits", null, ContentValues().apply {
            put("uri", uri); put("recipe", state.toJson().toString()); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drops a photo's saved edit (used when the recipe is reverted to the original). */
    fun deleteEdit(uri: String) { database.writableDatabase.delete("edits", "uri = ?", arrayOf(uri)) }

    /** Every photo with a saved edit, read once so the grid doesn't query per thumbnail. */
    fun editedUris(): Set<String> = database.readableDatabase.rawQuery("SELECT uri FROM edits", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun hasEdit(uri: String): Boolean = database.readableDatabase.rawQuery("SELECT 1 FROM edits WHERE uri = ? LIMIT 1", arrayOf(uri)).use { it.moveToFirst() }

    fun removePhoto(uri: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("edits", "uri = ?", arrayOf(uri)); db.delete("photos", "uri = ?", arrayOf(uri)); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun loadFavoritePresets(): Set<String> = prefs.getStringSet("favoritePresets", emptySet())?.toSet() ?: emptySet()
    fun saveFavoritePresets(ids: Set<String>) { prefs.edit().putStringSet("favoritePresets", ids).apply() }

    fun loadCustomPresets(): List<Preset> = runCatching {
        val array = JSONArray(prefs.getString("customPresets", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val fields = item.optJSONArray("fields")?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }
            val rawWb = item.optJSONArray("rawWhiteBalance")?.takeIf { it.length() == 2 }?.let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
            Preset(item.getString("id"), item.getString("name"), "Yours", developStateFromJson(item.getJSONObject("state")), false, fields, rawWb)
        }
    }.getOrDefault(emptyList())

    fun saveCustomPresets(presets: List<Preset>) {
        val array = JSONArray()
        presets.forEach { preset -> array.put(JSONObject().apply { put("id", preset.id); put("name", preset.name); put("state", preset.state.toJson()); preset.fields?.let { put("fields", JSONArray(it.toList())) }
                preset.rawWhiteBalance?.let { (t, n) -> put("rawWhiteBalance", JSONArray().put(t.toDouble()).put(n.toDouble())) } }) }
        prefs.edit().putString("customPresets", array.toString()).apply()
    }

    fun copySettings(state: DevelopState) { prefs.edit().putString("settingsClipboard", state.toJson().toString()).apply() }
    fun pastedSettings(): DevelopState? = prefs.getString("settingsClipboard", null)?.let { runCatching { developStateFromJson(JSONObject(it)) }.getOrNull() }

    private fun migrateLegacyCatalogOnce() {
        if (prefs.getBoolean("sqliteMigrationComplete", false)) return
        runCatching {
            val array = JSONArray(prefs.getString("photos", "[]"))
            val legacy = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PhotoItem(item.getString("uri"), item.optString("name", "Photo ${index + 1}"), item.optInt("rating"), item.optBoolean("flagged"), importedAt = item.optLong("importedAt", System.currentTimeMillis()))
            }
            savePhotos(legacy)
            legacy.forEach { photo ->
                prefs.getString("edit:${photo.uri}", null)?.let { recipe ->
                    database.writableDatabase.insertWithOnConflict("edits", null, ContentValues().apply {
                        put("uri", photo.uri); put("recipe", recipe); put("updated_at", System.currentTimeMillis())
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        }
        prefs.edit().putBoolean("sqliteMigrationComplete", true).apply()
    }

    private fun PhotoItem.values() = ContentValues().apply {
        put("uri", uri); put("name", name); put("rating", rating); put("flagged", if (flagged) 1 else 0)
        put("rejected", if (rejected) 1 else 0); put("imported_at", importedAt)
    }

    // Albums are a light many-to-many membership kept in preferences: { "Album": ["uri", ...] }.
    fun loadAlbums(): Map<String, Set<String>> = runCatching {
        val root = JSONObject(prefs.getString("albums", null) ?: "{}")
        root.keys().asSequence().associateWith { key ->
            val array = root.getJSONArray(key)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
    }.getOrDefault(emptyMap())

    fun saveAlbums(albums: Map<String, Set<String>>) {
        val root = JSONObject()
        albums.forEach { (name, uris) -> root.put(name, JSONArray().apply { uris.forEach { put(it) } }) }
        prefs.edit().putString("albums", root.toString()).apply()
    }

    fun loadExportOptions(): ExportOptions = ExportOptions(
        quality = prefs.getInt("exportQuality", 94).coerceIn(50, 100),
        maxSide = prefs.getInt("exportMaxSide", 3200).coerceIn(1024, 6000),
        format = prefs.getString("exportFormat", "JPEG") ?: "JPEG",
    )

    fun saveExportOptions(options: ExportOptions) {
        prefs.edit().putInt("exportQuality", options.quality).putInt("exportMaxSide", options.maxSide)
            .putString("exportFormat", options.format).apply()
    }
}

private class CatalogDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, rating INTEGER NOT NULL DEFAULT 0, flagged INTEGER NOT NULL DEFAULT 0, rejected INTEGER NOT NULL DEFAULT 0, imported_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE edits(uri TEXT PRIMARY KEY NOT NULL, recipe TEXT NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(uri) REFERENCES photos(uri) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX photos_imported_at ON photos(imported_at DESC)")
        db.execSQL("CREATE INDEX photos_rating ON photos(rating DESC)")
        db.execSQL("CREATE INDEX edits_updated_at ON edits(updated_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN rejected INTEGER NOT NULL DEFAULT 0")
    }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
}
