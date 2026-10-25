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
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val previous = loadEdit(uri)
            if (previous != state) {
                if (history(uri).isEmpty()) insertVersion(uri, if(previous==DevelopState()) "Original" else "Starting edit (before history was enabled)", previous, false)
                insertVersion(uri, editDescription(previous, state), state, false)
            }
            if (state.copy(presetId = null, presetBase = null, presetFields = null) == DevelopState()) {
                db.delete("edits", "uri = ?", arrayOf(uri))
            } else db.insertWithOnConflict("edits", null, ContentValues().apply {
                put("uri", uri); put("recipe", state.toJson().toString()); put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Drops a photo's saved edit (used when the recipe is reverted to the original). */
    fun deleteEdit(uri: String) { saveEdit(uri, DevelopState()) }

    fun history(uri: String): List<EditVersion> = database.readableDatabase.query("history", null, "uri = ?", arrayOf(uri), null, null, "id DESC").use { c ->
        buildList { while (c.moveToNext()) add(EditVersion(c.getLong(0), c.getString(2), c.getLong(3), developStateFromJson(JSONObject(c.getString(4))), c.getInt(5) == 1, if(c.isNull(6))null else PhotoMetadata.fromJson(JSONObject(c.getString(6))))) }
    }
    private fun insertVersion(uri: String, label: String, state: DevelopState, named: Boolean): Long =
        database.writableDatabase.insertOrThrow("history", null, ContentValues().apply {
            put("uri", uri); put("label", label); put("created_at", System.currentTimeMillis()); put("recipe", state.toJson().toString()); put("named", if (named) 1 else 0)
            put("metadata", (loadMetadata(uri) ?: PhotoMetadataIO.read(context,android.net.Uri.parse(uri))).toJson().toString())
        })
    fun snapshot(uri: String, name: String, state: DevelopState): Long {
        require(name.isNotBlank()) { "Enter a version name" }
        return insertVersion(uri, name.trim().take(100), state, true)
    }
    fun renameVersion(id: Long, name: String) {
        require(name.isNotBlank())
        database.writableDatabase.update("history", ContentValues().apply { put("label", name.trim().take(100)) }, "id = ? AND named = 1", arrayOf(id.toString()))
    }
    fun deleteVersion(id: Long) { database.writableDatabase.delete("history", "id = ? AND named = 1", arrayOf(id.toString())) }
    fun loadMetadata(uri: String): PhotoMetadata? = database.readableDatabase.query("metadata", arrayOf("value"), "uri = ?", arrayOf(uri), null, null, null).use { c ->
        if (c.moveToFirst()) PhotoMetadata.fromJson(JSONObject(c.getString(0))) else null
    }
    fun allMetadata(): Map<String, PhotoMetadata> = database.readableDatabase.query("metadata", null, null, null, null, null, null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), PhotoMetadata.fromJson(JSONObject(c.getString(1)))) }
    }
    fun saveMetadata(uri: String, metadata: PhotoMetadata) {
        val updated=metadata.validated();val db=database.writableDatabase
        db.beginTransaction()
        try {
            val previous=loadMetadata(uri)
            if(previous!=null && previous!=updated) insertVersion(uri,"Before metadata/profile change",loadEdit(uri),false)
            db.insertWithOnConflict("metadata", null, ContentValues().apply { put("uri", uri); put("value", updated.toJson().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
            if(previous!=null && previous!=updated) insertVersion(uri,"Metadata/profiles",loadEdit(uri),false)
            db.setTransactionSuccessful()
        } finally{db.endTransaction()}
    }
    fun applyBatch(uris: List<String>, source: DevelopState, includeLocal: Boolean, preset: Preset? = null) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            uris.distinct().forEach { uri ->
                val before = loadEdit(uri)
                snapshot(uri, "Before batch edit", before)
                val after = if (preset == null) batchRecipe(before, source, includeLocal) else {
                    val photo = loadPhotos().first { it.uri == uri }
                    val (look, fields) = preset.lookFor(photo.isRaw())
                    blendPreset(before, look, 1f, fields).copy(presetId = null, presetBase = null, presetFields = null)
                }
                saveEdit(uri, after)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun enqueueExports(photos: List<PhotoItem>, options: ExportOptions): List<Long> {
        val db = database.writableDatabase
        val ids = mutableListOf<Long>()
        db.beginTransaction()
        try {
            photos.forEach { photo -> ids += db.insertOrThrow("export_jobs", null, ContentValues().apply {
                put("uri", photo.uri); put("name", photo.name); put("recipe", loadEdit(photo.uri).toJson().toString())
                put("options", JSONObject().put("quality", options.quality).put("maxSide", options.maxSide).put("format", options.format)
                    .put("includeMetadata", options.includeMetadata).put("includeLocation", options.includeLocation).toString())
                put("metadata", (loadMetadata(photo.uri) ?: PhotoMetadataIO.read(context, android.net.Uri.parse(photo.uri))).toJson().toString()); put("status", "queued")
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ids
    }
    data class ExportJob(val id: Long, val photo: PhotoItem, val state: DevelopState, val options: ExportOptions, val metadata: PhotoMetadata)
    fun nextExport(): ExportJob? = database.readableDatabase.query("export_jobs", null, "status = 'queued'", null, null, null, "id ASC", "1").use { c ->
        if (!c.moveToFirst()) null else {
            val o = JSONObject(c.getString(4))
            ExportJob(c.getLong(0), PhotoItem(c.getString(1), c.getString(2)), developStateFromJson(JSONObject(c.getString(3))),
                ExportOptions(o.getInt("quality"), o.getInt("maxSide"), o.getString("format"), o.optBoolean("includeMetadata", true), o.optBoolean("includeLocation", false)),
                PhotoMetadata.fromJson(JSONObject(c.getString(5))))
        }
    }
    fun finishExport(id: Long, result: String) { database.writableDatabase.update("export_jobs", ContentValues().apply { put("status", result) }, "id = ?", arrayOf(id.toString())) }
    fun cancelExports() { database.writableDatabase.execSQL("UPDATE export_jobs SET status = 'cancelled' WHERE status = 'queued'") }
    fun exportCounts(): Map<String, Int> = database.readableDatabase.rawQuery("SELECT status, COUNT(*) FROM export_jobs GROUP BY status", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
    fun exportStatus(id: Long): String? = database.readableDatabase.rawQuery("SELECT status FROM export_jobs WHERE id = ?",arrayOf(id.toString())).use{if(it.moveToFirst())it.getString(0) else null}
    fun exportFailures(): List<String> = database.readableDatabase.rawQuery("SELECT name, status FROM export_jobs WHERE status LIKE 'failed:%' ORDER BY id DESC LIMIT 20",null).use{c->buildList{while(c.moveToNext())add("${c.getString(0)}: ${c.getString(1).removePrefix("failed: ")}")}}
    fun retryFailedExports() { database.writableDatabase.execSQL("UPDATE export_jobs SET status = 'queued' WHERE status LIKE 'failed:%'") }
    fun removeExportJob(id: Long) { database.writableDatabase.delete("export_jobs","id = ?",arrayOf(id.toString())) }
    fun close() { database.close() }

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
        includeMetadata = prefs.getBoolean("exportMetadata", true), includeLocation = prefs.getBoolean("exportLocation", false),
    )

    fun saveExportOptions(options: ExportOptions) {
        prefs.edit().putInt("exportQuality", options.quality).putInt("exportMaxSide", options.maxSide)
            .putString("exportFormat", options.format).putBoolean("exportMetadata", options.includeMetadata).putBoolean("exportLocation", options.includeLocation).apply()
    }
}

private class CatalogDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, rating INTEGER NOT NULL DEFAULT 0, flagged INTEGER NOT NULL DEFAULT 0, rejected INTEGER NOT NULL DEFAULT 0, imported_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE edits(uri TEXT PRIMARY KEY NOT NULL, recipe TEXT NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(uri) REFERENCES photos(uri) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX photos_imported_at ON photos(imported_at DESC)")
        db.execSQL("CREATE INDEX photos_rating ON photos(rating DESC)")
        db.execSQL("CREATE INDEX edits_updated_at ON edits(updated_at DESC)")
        createWorkflowTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN rejected INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) createWorkflowTables(db)
        else if (oldVersion < 4) db.execSQL("ALTER TABLE history ADD COLUMN metadata TEXT")
    }
    private fun createWorkflowTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL REFERENCES photos(uri) ON DELETE CASCADE, label TEXT NOT NULL, created_at INTEGER NOT NULL, recipe TEXT NOT NULL, named INTEGER NOT NULL DEFAULT 0, metadata TEXT)")
        db.execSQL("CREATE INDEX history_photo ON history(uri, id DESC)")
        db.execSQL("CREATE TABLE metadata(uri TEXT PRIMARY KEY NOT NULL REFERENCES photos(uri) ON DELETE CASCADE, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE export_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL, name TEXT NOT NULL, recipe TEXT NOT NULL, options TEXT NOT NULL, metadata TEXT NOT NULL, status TEXT NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
}
