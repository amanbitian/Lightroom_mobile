package com.lightroom.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** SQLite-backed local catalog. Preset preferences stay in SharedPreferences because they are tiny. */
class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_library_v2", Context.MODE_PRIVATE)
    private val database = CatalogDatabase(context)

    init { migrateLegacyCatalogOnce() }

    fun loadPhotos(): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        database.readableDatabase.query("photos", null, null, null, null, null, "imported_at DESC").use { cursor ->
            val uri = cursor.getColumnIndexOrThrow("uri")
            val name = cursor.getColumnIndexOrThrow("name")
            val rating = cursor.getColumnIndexOrThrow("rating")
            val flagged = cursor.getColumnIndexOrThrow("flagged")
            val importedAt = cursor.getColumnIndexOrThrow("imported_at")
            while (cursor.moveToNext()) result += PhotoItem(
                uri = cursor.getString(uri), name = cursor.getString(name), rating = cursor.getInt(rating),
                flagged = cursor.getInt(flagged) == 1, importedAt = cursor.getLong(importedAt),
            )
        }
        return result
    }

    fun savePhotos(photos: List<PhotoItem>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            photos.forEach { photo ->
                db.insertWithOnConflict("photos", null, photo.values(), SQLiteDatabase.CONFLICT_REPLACE)
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
            Preset(item.getString("id"), item.getString("name"), "Yours", developStateFromJson(item.getJSONObject("state")), false)
        }
    }.getOrDefault(emptyList())

    fun saveCustomPresets(presets: List<Preset>) {
        val array = JSONArray()
        presets.forEach { preset -> array.put(JSONObject().apply { put("id", preset.id); put("name", preset.name); put("state", preset.state.toJson()) }) }
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
                PhotoItem(item.getString("uri"), item.optString("name", "Photo ${index + 1}"), item.optInt("rating"), item.optBoolean("flagged"), item.optLong("importedAt", System.currentTimeMillis()))
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
        put("uri", uri); put("name", name); put("rating", rating); put("flagged", if (flagged) 1 else 0); put("imported_at", importedAt)
    }
}

private class CatalogDatabase(context: Context) : SQLiteOpenHelper(context, "lumaroom_catalog.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, rating INTEGER NOT NULL DEFAULT 0, flagged INTEGER NOT NULL DEFAULT 0, imported_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE edits(uri TEXT PRIMARY KEY NOT NULL, recipe TEXT NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(uri) REFERENCES photos(uri) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX photos_imported_at ON photos(imported_at DESC)")
        db.execSQL("CREATE INDEX photos_rating ON photos(rating DESC)")
        db.execSQL("CREATE INDEX edits_updated_at ON edits(updated_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
}
