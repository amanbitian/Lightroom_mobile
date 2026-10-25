package com.lightroom.mobile

import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.flow.conflate
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiteRtModels.init(this)
        Inpainter.refresh()
        setContent { LumaRoomApp() }
    }
}

private enum class AppTab { LIBRARY, EDIT, SETTINGS }
private enum class ToolPanel(val label: String) {
    ACTIONS("Info"), QUICK("Quick"), PRESETS("Presets"), MASKS("Masks"), REMOVE("Retouch"), CROP("Crop"), GEOMETRY("Geometry"), LIGHT("Light"), CURVE("Curve"), COLOR("Color"), MIX("Mix"), GRADE("Grading"), CALIBRATION("Calibration"), OPTICS("Optics"), BLUR("Lens Blur"), EFFECTS("Effects"), DETAIL("Detail")
}

// The editor's bottom bar: a handful of groups instead of one long row of 16 tools.
private enum class ToolGroup(val label: String, val tools: List<ToolPanel>) {
    LOOKS("Looks", listOf(ToolPanel.PRESETS, ToolPanel.QUICK)),
    ADJUST("Adjust", listOf(ToolPanel.LIGHT, ToolPanel.COLOR, ToolPanel.EFFECTS, ToolPanel.DETAIL, ToolPanel.CURVE, ToolPanel.MIX, ToolPanel.GRADE, ToolPanel.OPTICS, ToolPanel.CALIBRATION)),
    LOCAL("Local", listOf(ToolPanel.MASKS, ToolPanel.REMOVE, ToolPanel.BLUR)),
    FRAME("Crop", listOf(ToolPanel.CROP, ToolPanel.GEOMETRY)),
    INFO("Info", listOf(ToolPanel.ACTIONS)),
}

private val ImportMimeTypes = arrayOf(
    "image/*", "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef",
    "image/x-sony-arw", "image/x-panasonic-rw2", "image/x-fuji-raf", "image/x-olympus-orf",
    "image/x-samsung-srw", "image/tiff", "application/octet-stream",
)

private val Ink = Color(0xFFF5F2ED)
private val Muted = Color(0xFF9C9A97)
private val Panel = Color(0xFF17181B)
private val CanvasBlack = Color(0xFF0C0D0F)
private val Accent = Color(0xFF52B7FF)
private val WarmAccent = Color(0xFFEAB46C)

// Dark scheme so stock Material chips, fields and buttons match the app instead of the default lavender.
private val LumaColors = darkColorScheme(
    primary = Accent, onPrimary = CanvasBlack,
    secondaryContainer = Color(0xFF233747), onSecondaryContainer = Ink,
    background = CanvasBlack, onBackground = Ink,
    surface = Panel, onSurface = Ink, onSurfaceVariant = Muted,
    outline = Color.White.copy(.16f), outlineVariant = Color.White.copy(.10f),
)

@Composable
private fun LumaRoomApp() {
    val context = LocalContext.current
    val store = remember { LibraryStore(context) }
    val photos = remember { mutableStateListOf<PhotoItem>().apply { addAll(store.loadPhotos()) } }
    val customPresets = remember { mutableStateListOf<Preset>().apply { addAll(store.loadCustomPresets()) } }
    val assetPresets = remember { mutableStateListOf<Preset>() }
    LaunchedEffect(Unit) {
        if (assetPresets.isEmpty()) {
            val loaded = withContext(Dispatchers.IO) { AssetPresets.load(context) }
            assetPresets.addAll(loaded)
        }
    }
    val favoritePresets = remember { mutableStateListOf<String>().apply { addAll(store.loadFavoritePresets()) } }
    var albums by remember { mutableStateOf(store.loadAlbums()) }
    var exportOptions by remember { mutableStateOf(store.loadExportOptions()) }
    var editedUris by remember { mutableStateOf(store.editedUris()) }
    // Bumped on every save so library thumbnails re-render the edited look.
    var editStamps by remember { mutableStateOf(mapOf<String, Long>()) }
    // Database writes run off the main thread, one at a time so they land in order.
    val dbScope = rememberCoroutineScope()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dbDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
    fun persist(write: () -> Unit) { dbScope.launch(dbDispatcher) { write() } }
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var editing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AppTab.LIBRARY) }

    val importScope = rememberCoroutineScope()
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = friendlyName(context, uri, displayName(context, uri, "Photo"))
                    // Keep access across restarts. Some grants (e.g. the photo picker on older Android) can't be
                    // persisted; those photos are copied into app storage so they still open later.
                    val kept = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); uri }.getOrNull()
                        ?: copyIntoApp(context, uri, name)
                        ?: return@mapNotNull null
                    PhotoItem(kept.toString(), name)
                }
            }
            added.filter { item -> photos.none { it.uri == item.uri } }.forEach { photos.add(0, it) }
            withContext(Dispatchers.IO) { store.savePhotos(photos.toList()) }
            if (added.isNotEmpty()) Toast.makeText(context, if (added.size == 1) "1 photo imported" else "${added.size} photos imported", Toast.LENGTH_SHORT).show()
        }
    }
    // Gallery: Android's photo picker shows every photo and album on the device (no storage permission needed).
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> addPhotos(uris) }
    // Files: the document browser, for RAW/TIFF and anything outside the media library.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addPhotos(uris) }
    val importGallery = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val importFiles = { filePicker.launch(ImportMimeTypes) }

    val presetImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val parsed = text?.let { PresetImporter.parse(it) }
        if (parsed != null) {
            val label = displayName(context, uri, "Imported").substringBeforeLast('.').ifBlank { "Imported" }
            customPresets.add(Preset("import-${System.currentTimeMillis()}", label, "Yours", parsed.state, false, parsed.fields, parsed.rawWhiteBalance))
            store.saveCustomPresets(customPresets)
            Toast.makeText(context, "Imported \"$label\"", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Couldn't read that preset (.xmp or .lrtemplate)", Toast.LENGTH_LONG).show()
        }
    }

    MaterialTheme(colorScheme = LumaColors) {
        Surface(color = CanvasBlack, modifier = Modifier.fillMaxSize()) {
            if (editing && selectedPhoto != null) {
                EditorScreen(
                    photo = selectedPhoto!!,
                    initialState = remember(selectedPhoto!!.uri) { store.loadEdit(selectedPhoto!!.uri) },
                    favoritePresets = favoritePresets,
                    customPresets = customPresets,
                    assetPresets = assetPresets,
                    onFavorite = { id ->
                        if (id in favoritePresets) favoritePresets.remove(id) else favoritePresets.add(id)
                        store.saveFavoritePresets(favoritePresets.toSet())
                    },
                    onCreatePreset = { name, state, fields ->
                        val look = state.copy(presetId = null, presetBase = null)
                        customPresets.add(Preset("custom-${System.currentTimeMillis()}", name, "Yours", look, false, fields))
                        store.saveCustomPresets(customPresets)
                        Toast.makeText(context, "Saved \"$name\" to Yours", Toast.LENGTH_SHORT).show()
                    },
                    onRenamePreset = { id, name ->
                        val index = customPresets.indexOfFirst { it.id == id }
                        if (index >= 0 && name.isNotBlank()) { customPresets[index] = customPresets[index].copy(name = name.trim()); store.saveCustomPresets(customPresets) }
                    },
                    onDeletePreset = { id ->
                        customPresets.removeAll { it.id == id }; store.saveCustomPresets(customPresets)
                        favoritePresets.remove(id); store.saveFavoritePresets(favoritePresets.toSet())
                    },
                    onImportPreset = { presetImporter.launch(arrayOf("application/octet-stream", "text/xml", "application/xml", "text/plain", "*/*")) },
                    onPhotoUpdate = { updated ->
                        val index = photos.indexOfFirst { it.uri == updated.uri }
                        if (index >= 0) photos[index] = updated
                        selectedPhoto = updated
                        val snapshot = photos.toList(); persist { store.savePhotos(snapshot) }
                    },
                    onCopySettings = store::copySettings,
                    onPasteSettings = store::pastedSettings,
                    albums = albums,
                    exportOptions = exportOptions,
                    onExportOptionsChange = { exportOptions = it; store.saveExportOptions(it) },
                    onToggleAlbum = { name, uri ->
                        if (name.isNotBlank()) {
                            val current = albums.toMutableMap()
                            val members = (current[name] ?: emptySet()).toMutableSet()
                            if (uri in members) members.remove(uri) else members.add(uri)
                            current[name] = members; albums = current; store.saveAlbums(current)
                        }
                    },
                    onCreateAlbum = { name ->
                        if (name.isNotBlank() && !albums.containsKey(name)) {
                            val current = albums.toMutableMap(); current[name] = emptySet(); albums = current; store.saveAlbums(current)
                        }
                    },
                    onDelete = {
                        val uri = selectedPhoto!!.uri
                        persist { store.removePhoto(uri) } // queued after any pending writes, so they can't re-add it
                        editedUris = editedUris - uri
                        // Copies made at import (see copyIntoApp) belong to the app; originals elsewhere are never touched.
                        Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let { path ->
                            if (path.startsWith(java.io.File(context.filesDir, "imports").path)) java.io.File(path).delete()
                        }
                        photos.removeAll { it.uri == uri }
                        selectedPhoto = null; editing = false; tab = AppTab.LIBRARY
                    },
                    onSave = { state ->
                        selectedPhoto?.uri?.let { uri ->
                            persist { store.saveEdit(uri, state) }
                            if (uri !in editedUris) editedUris = editedUris + uri
                            editStamps = editStamps + (uri to System.nanoTime())
                        }
                    },
                    onBack = { editing = false; tab = AppTab.LIBRARY },
                )
            } else {
                Scaffold(containerColor = CanvasBlack, bottomBar = { BottomNavigation(tab) { tab = it } }) { padding ->
                    when (tab) {
                        AppTab.LIBRARY -> LibraryScreen(
                            photos = photos,
                            albums = albums,
                            isEdited = { it in editedUris },
                            editStamp = { editStamps[it] ?: 0L },
                            loadEdit = { uri -> withContext(Dispatchers.IO) { store.loadEdit(uri) } },
                            modifier = Modifier.padding(padding),
                            onImportGallery = importGallery, onImportFiles = importFiles,
                            onPhotoClick = { selectedPhoto = it; editing = true; tab = AppTab.EDIT },
                        )
                        AppTab.EDIT -> EmptyEditState(Modifier.padding(padding), importGallery, importFiles)
                        AppTab.SETTINGS -> SettingsScreen(photos.size, modifier = Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

/** Copies a picked photo into app storage when its access grant can't be kept; returns the copy's Uri. */
private fun copyIntoApp(context: android.content.Context, uri: Uri, name: String): Uri? = runCatching {
    val directory = java.io.File(context.filesDir, "imports").apply { mkdirs() }
    val target = java.io.File(directory, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
    Uri.fromFile(target)
}.getOrNull()

/**
 * Android's photo picker reports names like "36.jpg" (a media id). For those, name the photo from its capture
 * date instead ("IMG_20261024_052310.jpg"), falling back to the picker's name if the photo has no EXIF date.
 */
private fun friendlyName(context: android.content.Context, uri: Uri, reported: String): String {
    if (!Regex("""^\d+(\.\w+)?$""").matches(reported)) return reported
    val extension = reported.substringAfterLast('.', "jpg")
    val taken = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = android.media.ExifInterface(stream)
            exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
        }
    }.getOrNull()
    val stamp = taken?.let { Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(it)?.destructured }
        ?.let { (y, mo, d, h, mi, se) -> "$y$mo${d}_$h$mi$se" }
    return if (stamp != null) "IMG_$stamp.$extension" else "Photo_${reported.substringBeforeLast('.')}.$extension"
}

private fun displayName(context: android.content.Context, uri: Uri, fallback: String): String {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor?.moveToFirst() == true) cursor.getString(0) else fallback
    } catch (_: Exception) { fallback } finally { cursor?.close() }
}

@Composable
private fun LibraryScreen(
    photos: List<PhotoItem>, albums: Map<String, Set<String>>, isEdited: (String) -> Boolean,
    editStamp: (String) -> Long, loadEdit: suspend (String) -> DevelopState, modifier: Modifier = Modifier,
    onImportGallery: () -> Unit, onImportFiles: () -> Unit, onPhotoClick: (PhotoItem) -> Unit,
) {
    var filter by remember { mutableStateOf("All Photos") }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Newest") }
    var density by remember { mutableIntStateOf(3) }
    val smartFilters = listOf("All Photos", "Recent", "Favorites", "Rejected", "Edited")
    if (filter !in smartFilters && !albums.containsKey(filter)) filter = "All Photos"
    val filtered = when (filter) {
        "Favorites" -> photos.filter { it.flagged || it.rating >= 4 }
        "Rejected" -> photos.filter { it.rejected }
        "Edited" -> photos.filter { isEdited(it.uri) }
        "Recent" -> photos.sortedByDescending { it.importedAt }.take(50)
        "All Photos" -> photos
        else -> albums[filter]?.let { members -> photos.filter { it.uri in members } } ?: photos
    }
    val searched = if (search.isBlank()) filtered else filtered.filter { it.name.contains(search.trim(), ignoreCase = true) }
    val visible = when (sort) {
        "Oldest" -> searched.sortedBy { it.importedAt }
        "Name" -> searched.sortedBy { it.name.lowercase() }
        "Rating" -> searched.sortedByDescending { it.rating }
        else -> searched.sortedByDescending { it.importedAt }
    }
    var searching by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(filter, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    (if (visible.size == 1) "1 photo" else "${visible.size} photos") + " · $sort",
                    color = Muted, fontSize = 12.sp,
                )
            }
            IconButton(onClick = { searching = !searching; if (!searching) search = "" }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search", tint = if (searching) Accent else Ink)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Sort and view", tint = Ink) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Panel) {
                    MenuHeader("Sort by")
                    listOf("Newest", "Oldest", "Name", "Rating").forEach { option ->
                        MenuOption(option, sort == option) { sort = option; menuOpen = false }
                    }
                    HorizontalDivider(color = Color.White.copy(.08f))
                    MenuHeader("Grid")
                    listOf("Large" to 2, "Medium" to 3, "Small" to 4).forEach { (label, columns) ->
                        MenuOption(label, density == columns) { density = columns; menuOpen = false }
                    }
                }
            }
            var importMenu by remember { mutableStateOf(false) }
            Box {
                FilledIconButton(onClick = { importMenu = true }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = CanvasBlack)) {
                    Icon(Icons.Filled.Add, "Import photos")
                }
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }, containerColor = Panel) {
                    MenuHeader("Import from")
                    DropdownMenuItem(text = { Column { Text("Gallery", color = Ink, fontSize = 14.sp); Text("All photos and albums", color = Muted, fontSize = 11.sp) } }, onClick = { importMenu = false; onImportGallery() })
                    DropdownMenuItem(text = { Column { Text("Files", color = Ink, fontSize = 14.sp); Text("RAW, TIFF, Drive and folders", color = Muted, fontSize = 11.sp) } }, onClick = { importMenu = false; onImportFiles() })
                }
            }
        }
        if (searching) OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search by filename", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).focusRequester(searchFocus),
        )
        LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            items(smartFilters + albums.keys.sorted()) { label ->
                FilterChip(
                    selected = filter == label, onClick = { filter = label },
                    label = { Text(label, fontSize = 12.sp) },
                    shape = RoundedCornerShape(50),
                    border = null,
                    colors = FilterChipDefaults.filterChipColors(containerColor = Panel, labelColor = Muted, selectedContainerColor = Ink, selectedLabelColor = CanvasBlack),
                )
            }
        }
        when {
            photos.isEmpty() -> EmptyLibrary(onImportGallery, onImportFiles, Modifier.weight(1f))
            visible.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (search.isNotBlank()) "No photos match \"${search.trim()}\"" else "Nothing in $filter yet", color = Muted, fontSize = 13.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(density),
                verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(visible, key = { it.uri }) { photo ->
                    PhotoTile(photo, isEdited(photo.uri), editStamp(photo.uri), loadEdit, showName = density == 2) { onPhotoClick(photo) }
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(label: String) {
    Text(label.uppercase(), color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun MenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (selected) Accent else Ink, fontSize = 14.sp) },
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, tint = Accent) },
        onClick = onClick,
    )
}

@Composable
private fun EmptyLibrary(onImportGallery: () -> Unit, onImportFiles: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        BrandLogo(Modifier.size(240.dp, 196.dp))
        Spacer(Modifier.height(24.dp)); Text("Your darkroom starts here", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text("Import photos to start editing. Originals are never modified.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onImportGallery, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = CanvasBlack), modifier = Modifier.padding(top = 22.dp)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import from Gallery")
        }
        Text("Browse files (RAW, TIFF)", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onImportFiles).padding(12.dp))
    }
}

@Composable
private fun PhotoTile(photo: PhotoItem, edited: Boolean, stamp: Long, loadEdit: suspend (String) -> DevelopState, showName: Boolean, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clickable(onClick = onClick), contentAlignment = Alignment.BottomStart) {
        val tileModifier = Modifier.fillMaxSize().then(if (photo.rejected) Modifier.graphicsLayer(alpha = .4f) else Modifier)
        // Edited photos show their edit, like Lightroom's grid (rendered small, cached until the next save).
        val context = LocalContext.current
        val editedThumb by produceState<Bitmap?>(EditedThumbnails.get(photo.uri, stamp), photo.uri, edited, stamp) {
            if (!edited) { value = null; return@produceState }
            EditedThumbnails.get(photo.uri, stamp)?.let { value = it; return@produceState }
            val source = BitmapRepository.load(context, Uri.parse(photo.uri), 384) ?: return@produceState
            value = runCatching { ImageExporter.renderEdited(source, loadEdit(photo.uri)) }.getOrNull()?.also { EditedThumbnails.put(photo.uri, stamp, it) }
        }
        val thumb = editedThumb
        if (thumb != null) Image(thumb.asImageBitmap(), photo.name, tileModifier, contentScale = ContentScale.Crop)
        else PhotoBitmap(photo, tileModifier)
        val hasBadges = photo.flagged || photo.rejected || photo.rating > 0
        if (showName || hasBadges || edited) {
            Box(Modifier.fillMaxWidth().height(34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.7f)))))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo.flagged) Text("⚑", color = WarmAccent, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            if (photo.rejected) Text("✕", color = Color(0xFFFF7777), fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            if (photo.rating > 0) Text("★".repeat(photo.rating), color = WarmAccent, fontSize = 9.sp, modifier = Modifier.padding(end = 4.dp))
            if (showName) Text(photo.name, color = Color.White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            if (edited) Box(Modifier.size(6.dp).background(Accent, CircleShape))
        }
    }
}

/** Small cache of rendered edited thumbnails, keyed by photo and save stamp. */
private object EditedThumbnails {
    private val cache = android.util.LruCache<String, Bitmap>(48)
    fun get(uri: String, stamp: Long): Bitmap? = cache.get("$uri#$stamp")
    fun put(uri: String, stamp: Long, bitmap: Bitmap) { cache.put("$uri#$stamp", bitmap) }
}

@Composable
private fun PhotoBitmap(photo: PhotoItem, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null, contentScale: ContentScale = ContentScale.Crop, requestedSide: Int = 512) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.uri, requestedSide) {
        value = BitmapRepository.load(context, Uri.parse(photo.uri), requestedSide)
    }
    // While decoding, a neutral tile (the illustrated placeholder looked like a wrong photo).
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), photo.name, modifier, contentScale = contentScale, colorFilter = colorFilter) else Box(modifier.background(Color(0xFF1C1D20)))
}

/** The LumaRoom logo on its cream paper card (it is drawn on cream, so it gets a card on the dark UI). */
@Composable
private fun BrandLogo(modifier: Modifier = Modifier) {
    Image(
        androidx.compose.ui.res.painterResource(R.drawable.lumaroom_logo), "LumaRoom",
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFF8F7F1)).padding(6.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun BottomNavigation(tab: AppTab, select: (AppTab) -> Unit) {
    NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
        listOf(Triple(AppTab.LIBRARY, Icons.Filled.Home, "Library"), Triple(AppTab.EDIT, Icons.Filled.Edit, "Edit"), Triple(AppTab.SETTINGS, Icons.Filled.Settings, "Settings")).forEach { (item, icon, label) ->
            NavigationBarItem(
                selected = tab == item, onClick = { select(item) },
                icon = { Icon(icon, label, Modifier.size(22.dp)) }, label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent, selectedTextColor = Ink, indicatorColor = Color.Transparent,
                    unselectedIconColor = Muted, unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun EmptyEditState(modifier: Modifier = Modifier, importGallery: () -> Unit, importFiles: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxSize().padding(32.dp)) {
        Icon(Icons.Filled.Edit, null, tint = Muted, modifier = Modifier.size(40.dp))
        Text("Choose a photo to edit", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Text("Open one from your Library, or import new photos.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        OutlinedButton(onClick = importGallery, shape = RoundedCornerShape(50)) { Text("Import from Gallery") }
        Text("Browse files (RAW, TIFF)", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = importFiles).padding(12.dp))
    }
}

@Composable
private fun SettingsScreen(photoCount: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(18.dp)); Text("SETTINGS", color = Accent, fontSize = 11.sp, letterSpacing = 2.6.sp, fontWeight = FontWeight.Bold)
        BrandLogo(Modifier.padding(top = 12.dp).size(170.dp, 139.dp)); Spacer(Modifier.height(20.dp))
        val context = LocalContext.current
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
        val lama by Inpainter.status.collectAsState()
        SettingsRow("Local catalog", "$photoCount photos · SQLite · edits saved automatically, originals never modified")
        SettingsRow("Export", "JPEG, PNG, 16-bit TIFF, 16-bit PNG · quality and size · Pictures/LumaRoom")
        SettingsRow(
            "On-device AI",
            "People masks (bundled) · Generative Remove: " + when (val st = lama) {
                Inpainter.Status.Ready -> "LaMa installed (174 MB)"
                is Inpainter.Status.Downloading -> "downloading ${(st.progress * 100).toInt()}%"
                else -> "not downloaded (download from Retouch)"
            },
        )
        SettingsRow("Privacy", "No account · no telemetry · photos never leave the device (network is used only for the optional AI model download)")
        SettingsRow("About", "Version $version · Kotlin + Jetpack Compose · LiteRT")
    }
}

@Composable
private fun SettingsRow(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(title, color = Ink, fontSize = 15.sp); Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 15.dp)); HorizontalDivider(color = Color.White.copy(.08f))
    }
}

@Composable
private fun EditorScreen(
    photo: PhotoItem, initialState: DevelopState, favoritePresets: List<String>, customPresets: List<Preset>, assetPresets: List<Preset>,
    onFavorite: (String) -> Unit, onCreatePreset: (String, DevelopState, Set<String>) -> Unit,
    onRenamePreset: (String, String) -> Unit, onDeletePreset: (String) -> Unit,
    onImportPreset: () -> Unit,
    onPhotoUpdate: (PhotoItem) -> Unit,
    onCopySettings: (DevelopState) -> Unit,
    onPasteSettings: () -> DevelopState?,
    albums: Map<String, Set<String>>,
    exportOptions: ExportOptions,
    onExportOptionsChange: (ExportOptions) -> Unit,
    onToggleAlbum: (String, String) -> Unit,
    onCreateAlbum: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: (DevelopState) -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val editorBitmap by produceState<Bitmap?>(null, photo.uri) {
        value = BitmapRepository.load(context, Uri.parse(photo.uri), 1600)
    }
    val imageAspect = editorBitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
    var panel by remember { mutableStateOf(ToolPanel.LIGHT) }
    var showHistogram by remember { mutableStateOf(false) }
    var develop by remember(photo.uri) { mutableStateOf(initialState) }
    val undo = remember(photo.uri) { mutableStateListOf<DevelopState>() }
    val redo = remember(photo.uri) { mutableStateListOf<DevelopState>() }
    var compare by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var activeMaskId by remember(photo.uri) { mutableStateOf(initialState.masks.firstOrNull()?.id) }
    var activeSpotId by remember(photo.uri) { mutableStateOf(initialState.healingSpots.firstOrNull()?.id) }
    var historyJob by remember { mutableStateOf<Job?>(null) }
    // A preset is only a preview until the user taps Apply; `previewBase` is the look before the preview so Cancel
    // restores it exactly. Applied presets are recorded on the photo (presetId + presetBase), so tapping the applied
    // look again takes it off, and choosing another look replaces it instead of stacking.
    var previewBase by remember(photo.uri) { mutableStateOf<DevelopState?>(null) }
    var presetPreview by remember(photo.uri) { mutableStateOf<Preset?>(null) }
    var presetAmount by remember(photo.uri) { mutableFloatStateOf(100f) }
    val allPresets = builtInPresets + assetPresets + customPresets + quickLooks
    val isRaw = remember(photo.name) { photo.isRaw() }
    // Small copy of the photo for preset thumbnails, rendered with each preset like Lightroom's preset strip.
    val thumbSource = remember(editorBitmap) { editorBitmap?.scaledToFit(160) }
    val controlsHeight = (configuration.screenHeightDp * .30f).coerceIn(220f, 320f).dp

    fun change(next: DevelopState) {
        if (next == develop) return
        if (historyJob == null) {
            undo.add(develop); if (undo.size > 50) undo.removeAt(0)
        }
        develop = next; redo.clear()
        historyJob?.cancel()
        historyJob = scope.launch { delay(350); historyJob = null }
    }
    /** [state] with its applied preset taken off: only the fields that preset set go back to their earlier values. */
    fun withoutPreset(state: DevelopState): DevelopState {
        val before = state.presetBase ?: return state.copy(presetId = null)
        val fields = state.presetFields ?: allPresets.firstOrNull { it.id == state.presetId }?.lookFor(isRaw)?.second ?: allPresetFields
        return blendPreset(state, before, 1f, fields).copy(presetId = null, presetBase = null, presetFields = null)
    }
    fun presetResult(base: DevelopState, preset: Preset, amount: Float): DevelopState {
        val (look, fields) = preset.lookFor(isRaw)
        return blendPreset(base, look, amount / 100f, fields).copy(presetId = preset.id, presetBase = base, presetFields = fields ?: allPresetFields)
    }
    fun cancelPreset() {
        previewBase?.let { develop = it }
        previewBase = null; presetPreview = null
    }
    fun previewPreset(preset: Preset) {
        when {
            preset.id == presetPreview?.id -> cancelPreset()
            presetPreview == null && preset.id == develop.presetId -> change(withoutPreset(develop))
            else -> {
                val before = previewBase ?: develop
                previewBase = before; presetPreview = preset; presetAmount = 100f
                develop = presetResult(withoutPreset(before), preset, 100f)
            }
        }
    }
    fun setPresetAmount(amount: Float) {
        val before = previewBase ?: return
        val preset = presetPreview ?: return
        presetAmount = amount; develop = presetResult(withoutPreset(before), preset, amount)
    }
    fun applyPreset() {
        val before = previewBase ?: return
        previewBase = null; presetPreview = null
        if (develop != before) {
            historyJob?.cancel(); historyJob = null
            undo.add(before); if (undo.size > 50) undo.removeAt(0); redo.clear()
        }
    }
    // Only write a recipe once the photo has actually been edited, and never persist an unconfirmed preset preview.
    var touched by remember(photo.uri) { mutableStateOf(false) }
    fun save() {
        val committed = previewBase ?: develop
        if (committed != initialState) touched = true
        if (touched) onSave(committed)
    }
    fun back() { cancelPreset(); save(); onBack() }
    BackHandler(onBack = ::back)
    LaunchedEffect(develop, previewBase) { delay(300); save() }

    // Live preview: the export pipeline renders the photo in its own frame (crop/rotation are applied by the layer
    // below). A small draft renders first so sliders stay responsive, then full resolution once input settles.
    var rendered by remember(photo.uri) { mutableStateOf<Bitmap?>(null) }
    val renderKey = develop.renderKey()
    LaunchedEffect(editorBitmap) {
        val full = editorBitmap ?: return@LaunchedEffect
        val draftSource = withContext(Dispatchers.Default) { full.scaledToFit(720) }
        var fullJob: Job? = null
        snapshotFlow { develop.renderKey() }.conflate().collect { key ->
            fullJob?.cancel()
            rendered = ImageExporter.renderPreview(draftSource, key, draft = true)
            fullJob = launch { delay(220); rendered = ImageExporter.renderPreview(full, key) }
        }
    }
    fun selectPanel(option: ToolPanel) {
        if (option != panel) cancelPreset()
        panel = option
        if (option == ToolPanel.MASKS && activeMaskId == null) activeMaskId = develop.masks.firstOrNull()?.id
        if (option == ToolPanel.REMOVE && activeSpotId == null) activeSpotId = develop.healingSpots.firstOrNull()?.id
    }
    Column(Modifier.fillMaxSize().background(CanvasBlack).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp)) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library", tint = Ink) }
            Text(photo.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
            ActionText("↶", undo.isNotEmpty() || previewBase != null) {
                if (previewBase != null) cancelPreset()
                else { historyJob?.cancel(); historyJob = null; redo.add(develop); develop = undo.removeAt(undo.lastIndex) }
            }
            ActionText("↷", redo.isNotEmpty() && previewBase == null) { historyJob?.cancel(); historyJob = null; undo.add(develop); develop = redo.removeAt(redo.lastIndex) }
            // Before/after: tap to toggle (holding the photo also shows the original while held).
            IconButton(onClick = { compare = !compare }) { CompareIcon(if (compare) Accent else Ink) }
            IconButton(onClick = { showHistogram = !showHistogram }) { HistogramIcon(if (showHistogram) Accent else Ink) }
            IconButton(onClick = { showExport = true }, enabled = !exporting) {
                if (exporting) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.Share, "Export", tint = Accent)
            }
        }
        fun runExport(chosen: ExportOptions) {
            exporting = true
            scope.launch {
                val result = ImageExporter.export(context, photo, develop, chosen); exporting = false
                val message = when (result) {
                    is ImageExporter.ExportResult.Saved -> "Saved to ${result.location}"
                    is ImageExporter.ExportResult.Failed -> result.reason
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        // Android 8-9 need the storage permission to save into the public Pictures folder (10+ use MediaStore).
        var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }
        val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingExport?.let(::runExport); pendingExport = null
        }
        if (showExport) ExportDialog(
            options = exportOptions,
            onDismiss = { showExport = false },
            onExport = { chosen ->
                onExportOptionsChange(chosen)
                showExport = false
                val needsPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsPermission) { pendingExport = chosen; storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                else runExport(chosen)
            },
        )
        val cropMode = panel == ToolPanel.CROP && !compare
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF202124)).padding(10.dp).then(
                if (panel == ToolPanel.CROP || panel == ToolPanel.MASKS || panel == ToolPanel.REMOVE || panel == ToolPanel.BLUR) Modifier
                else Modifier.pointerInput(photo.uri) { detectTapGestures(onPress = { compare = true; tryAwaitRelease(); compare = false }) }
            ),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
            val view = remember(boxW, boxH, imageAspect, develop.rotation, develop.straighten, develop.cropAspect, develop.cropLeft, develop.cropTop, develop.cropRight, develop.cropBottom, cropMode) {
                FrameView.of(develop, imageAspect, boxW, boxH, cropMode)
            }
            // Clipped to the visible frame: the crop when editing elsewhere, the whole straightened photo in Crop.
            val geometryActive = develop.geometryVertical != 0f || develop.geometryHorizontal != 0f || develop.geometryAspect != 0f
            Box(
                Modifier.size(with(density) { view.frameWidth.toDp() }, with(density) { view.frameHeight.toDp() }).clipToBounds()
                    .then(if (!geometryActive) Modifier else Modifier.background(Color.Black).drawWithContent {
                        // Geometry is drawn through the exact matrix the exporter applies to the cropped frame.
                        val matrix = ImageExporter.perspectiveMatrix(size.width, size.height, develop)
                        drawContext.canvas.nativeCanvas.save(); drawContext.canvas.nativeCanvas.concat(matrix)
                        drawContent()
                        drawContext.canvas.nativeCanvas.restore()
                    }),
                contentAlignment = Alignment.Center,
            ) {
                // Masks, heal spots and the blur focus are stored relative to the original photo. Drawing them (and
                // taking touches) inside the photo's own transform keeps them on the same pixels after crop/rotate;
                // the exporter maps them through the matching transform.
                Box(
                    Modifier.requiredSize(with(density) { boxW.toDp() }, with(density) { boxH.toDp() }).graphicsLayer(
                        // Export rotates, flips, then straightens: R(a)·F·R(t). The layer scales (flip) before it
                        // rotates, and R(a)·F·R(t) = R(a - t)·F, so a flipped photo turns the other way here.
                        rotationZ = if (develop.flipHorizontal) develop.straighten - develop.rotation else develop.rotation + develop.straighten,
                        scaleX = (if (develop.flipHorizontal) -1f else 1f) * view.scale,
                        scaleY = view.scale,
                        translationX = view.translationX,
                        translationY = view.translationY,
                    ),
                ) {
                    val shownBitmap = if (compare) editorBitmap else rendered ?: editorBitmap
                    if (shownBitmap != null) Image(shownBitmap.asImageBitmap(), photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (!compare && panel == ToolPanel.MASKS) MaskGuide(develop, activeMaskId, imageAspect, ::change)
                    if (!compare && panel == ToolPanel.REMOVE) HealingGuide(develop, activeSpotId, imageAspect, ::change)
                    if (!compare && panel == ToolPanel.BLUR) LensBlurGuide(develop, imageAspect, ::change)
                }
                if (!compare && !cropMode) VignetteOverlay(develop)
                if (cropMode) CropGuide(develop, view.frameAspect, ::change)
            }
            if (!compare && showHistogram) HistogramOverlay(rendered ?: editorBitmap, Modifier.align(Alignment.TopEnd).padding(2.dp))
            val previewHint = when {
                compare -> "Before"
                panel == ToolPanel.CROP -> "Drag the box or its corners"
                panel == ToolPanel.MASKS -> "Paint or position the mask"
                panel == ToolPanel.REMOVE -> develop.healingSpots.firstOrNull { it.id == activeSpotId }?.let { if (it.isPainted) "Paint over what to remove" else "Drag the source or target" } ?: "Add a heal or Generative Remove spot"
                panel == ToolPanel.BLUR -> "Drag to set the focus point"
                else -> null
            }
            if (previewHint != null) Text(previewHint, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).background(Color.Black.copy(.6f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        val group = ToolGroup.entries.first { panel in it.tools }
        Column(Modifier.fillMaxWidth().background(Panel)) {
            // Shown even for single-tool groups so the photo doesn't jump when switching groups.
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(group.tools) { option ->
                    val selected = panel == option
                    Text(
                        option.label, color = if (selected) CanvasBlack else Muted, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ink else Color.White.copy(.06f))
                            .clickable { selectPanel(option) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(controlsHeight).padding(horizontal = 18.dp, vertical = 10.dp)) {
                when (panel) {
                    ToolPanel.ACTIONS -> ActionsPanel(photo, develop, albums, onPhotoUpdate, onCopySettings, onPasteSettings, onToggleAlbum, onCreateAlbum, onDelete, ::change)
                    ToolPanel.QUICK -> QuickActionsPanel(
                        editorBitmap, develop, ::change,
                        looks = quickLooks, previewing = presetPreview, amount = presetAmount,
                        onPreview = ::previewPreset, onAmount = ::setPresetAmount, onApply = ::applyPreset, onCancel = ::cancelPreset,
                    )
                    ToolPanel.BLUR -> LensBlurControls(develop, ::change)
                    ToolPanel.PRESETS -> PresetsPanel(
                        develop, builtInPresets + assetPresets + customPresets, favoritePresets, onFavorite, onCreatePreset, onImportPreset,
                        onRenamePreset = onRenamePreset, onDeletePreset = onDeletePreset,
                        previewing = presetPreview, amount = presetAmount,
                        onPreview = ::previewPreset, onAmount = ::setPresetAmount, onApply = ::applyPreset, onCancel = ::cancelPreset,
                        thumbSource = thumbSource, thumbnailState = { presetResult(withoutPreset(previewBase ?: develop), it, 100f) },
                    )
                    ToolPanel.MASKS -> MaskControls(develop, activeMaskId, { activeMaskId = it }, ::change)
                    ToolPanel.REMOVE -> RemoveControls(develop, activeSpotId, { activeSpotId = it }, ::change)
                    ToolPanel.CROP -> CropControls(develop, orientedAspect(imageAspect, develop.rotation), ::change)
                    ToolPanel.GEOMETRY -> GeometryControls(develop, ::change)
                    ToolPanel.LIGHT -> LightControls(develop, ::change)
                    ToolPanel.CURVE -> CurveControls(develop, ::change)
                    ToolPanel.COLOR -> ColorControls(develop, ::change)
                    ToolPanel.MIX -> ColorMixerControls(develop, ::change)
                    ToolPanel.GRADE -> ColorGradingControls(develop, ::change)
                    ToolPanel.OPTICS -> OpticsControls(develop, ::change)
                    ToolPanel.CALIBRATION -> CalibrationControls(develop, ::change)
                    ToolPanel.EFFECTS -> EffectsControls(develop, ::change)
                    ToolPanel.DETAIL -> DetailControls(develop, ::change)
                }
            }
            ToolGroupBar(group) { selectPanel(it.tools.first()) }
        }
    }
}

@Composable
private fun ExportDialog(options: ExportOptions, onDismiss: () -> Unit, onExport: (ExportOptions) -> Unit) {
    var quality by remember { mutableFloatStateOf(options.quality.toFloat()) }
    var maxSide by remember { mutableIntStateOf(options.maxSide) }
    var format by remember { mutableStateOf(options.format) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).width(300.dp)) {
                Text("EXPORT", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Save to Pictures/LumaRoom", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
                Text("Format", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    listOf("JPEG" to "JPEG", "PNG" to "PNG", "TIFF 16" to "TIFF", "PNG 16" to "PNG16").forEach { (label, value) ->
                        FilterChip(selected = format == value, onClick = { format = value }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                if (format == "TIFF" || format == "PNG16") Text("16-bit: full tonal and colour precision, with every effect applied. Large files.", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
                Text("Max dimension", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 3200, 4096, 6000).forEach { s -> FilterChip(selected = maxSide == s, onClick = { maxSide = s }, label = { Text(if (s == 6000) "Max" else "$s", fontSize = 10.sp) }) }
                }
                if (format == "JPEG") {
                    Text("Quality ${quality.toInt()}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(quality, { quality = it }, valueRange = 50f..100f, colors = sliderColors())
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancel", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
                    Text("Export", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onExport(ExportOptions(quality.toInt(), maxSide, format)) }.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionText(label: String, enabled: Boolean, action: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Ink else Muted.copy(.35f), fontSize = 22.sp)
    }
}

@Composable
private fun CompareIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension / 2f - 1.dp.toPx()
        drawCircle(tint, r, style = Stroke(1.8.dp.toPx()))
        drawArc(tint, 90f, 180f, useCenter = true, topLeft = center - androidx.compose.ui.geometry.Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
    }
}

@Composable
private fun HistogramIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val bars = listOf(.35f, .7f, 1f, .6f, .45f)
        val gap = size.width * .06f
        val barWidth = (size.width - gap * (bars.size - 1)) / bars.size
        bars.forEachIndexed { index, height ->
            val h = size.height * height
            drawRect(tint, androidx.compose.ui.geometry.Offset(index * (barWidth + gap), size.height - h), androidx.compose.ui.geometry.Size(barWidth, h))
        }
    }
}

/** Simple line icons drawn in code so the bar stays consistent without an icon dependency. */
@Composable
private fun ToolGroupIcon(group: ToolGroup, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val line = Stroke(1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * w, y * h)
        when (group) {
            ToolGroup.LOOKS -> {
                drawCircle(tint, w * .26f, at(.38f, .5f), style = line)
                drawCircle(tint, w * .26f, at(.62f, .5f), style = line)
            }
            ToolGroup.ADJUST -> listOf(.25f to .35f, .5f to .7f, .75f to .45f).forEach { (y, knob) ->
                drawLine(tint, at(.1f, y), at(.9f, y), line.width, cap = line.cap)
                drawCircle(CanvasBlack, w * .1f, at(knob, y)); drawCircle(tint, w * .1f, at(knob, y), style = line)
            }
            ToolGroup.LOCAL -> {
                drawCircle(tint, w * .36f, at(.5f, .5f), style = Stroke(line.width, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * .12f, w * .08f))))
                drawCircle(tint, w * .1f, at(.5f, .5f))
            }
            ToolGroup.FRAME -> {
                drawLine(tint, at(.28f, .08f), at(.28f, .72f), line.width, cap = line.cap); drawLine(tint, at(.28f, .72f), at(.92f, .72f), line.width, cap = line.cap)
                drawLine(tint, at(.08f, .28f), at(.72f, .28f), line.width, cap = line.cap); drawLine(tint, at(.72f, .28f), at(.72f, .92f), line.width, cap = line.cap)
            }
            ToolGroup.INFO -> {
                drawCircle(tint, w * .4f, at(.5f, .5f), style = line)
                drawLine(tint, at(.5f, .45f), at(.5f, .72f), line.width, cap = line.cap)
                drawCircle(tint, line.width * .7f, at(.5f, .3f))
            }
        }
    }
}

@Composable
private fun ToolGroupBar(selected: ToolGroup, onSelect: (ToolGroup) -> Unit) {
    Row(Modifier.fillMaxWidth().background(CanvasBlack).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ToolGroup.entries.forEach { group ->
            val active = group == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(group) }.padding(vertical = 6.dp),
            ) {
                ToolGroupIcon(group, if (active) Accent else Muted)
                Text(group.label, color = if (active) Ink else Muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun ActionsPanel(
    photo: PhotoItem,
    current: DevelopState,
    albums: Map<String, Set<String>>,
    updatePhoto: (PhotoItem) -> Unit,
    copySettings: (DevelopState) -> Unit,
    pasteSettings: () -> DevelopState?,
    toggleAlbum: (String, String) -> Unit,
    createAlbum: (String) -> Unit,
    delete: () -> Unit,
    update: (DevelopState) -> Unit,
) {
    val context = LocalContext.current
    var newAlbum by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("ACTIONS", "Review, organize, and reuse edits")
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rating", color = Muted, fontSize = 11.sp, modifier = Modifier.width(72.dp))
            (1..5).forEach { star ->
                Text(
                    if (star <= photo.rating) "★" else "☆",
                    color = if (star <= photo.rating) WarmAccent else Muted,
                    fontSize = 25.sp,
                    modifier = Modifier.clickable { updatePhoto(photo.copy(rating = if (photo.rating == star) 0 else star)) }.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (photo.flagged) "⚑ Pick" else "⚐ Pick",
                color = if (photo.flagged) WarmAccent else Muted,
                fontSize = 11.sp,
                modifier = Modifier.clickable { updatePhoto(photo.copy(flagged = !photo.flagged, rejected = false)) }.padding(8.dp),
            )
            Text(
                "✕ Reject",
                color = if (photo.rejected) Color(0xFFFF7777) else Muted,
                fontSize = 11.sp,
                modifier = Modifier.clickable { updatePhoto(photo.copy(rejected = !photo.rejected, flagged = false)) }.padding(8.dp),
            )
        }
        HorizontalDivider(color = Color.White.copy(.08f), modifier = Modifier.padding(vertical = 10.dp))
        Text("Albums", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (albums.isEmpty()) Text("No albums yet", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
            albums.keys.sorted().forEach { album ->
                val member = albums[album]?.contains(photo.uri) == true
                FilterChip(selected = member, onClick = { toggleAlbum(album, photo.uri) }, label = { Text(album, fontSize = 10.sp) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newAlbum, onValueChange = { newAlbum = it },
                placeholder = { Text("New album", color = Muted, fontSize = 11.sp) }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text("Create", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                val name = newAlbum.trim()
                if (name.isNotBlank()) { createAlbum(name); toggleAlbum(name, photo.uri); newAlbum = "" }
            }.padding(10.dp))
        }
        HorizontalDivider(color = Color.White.copy(.08f), modifier = Modifier.padding(vertical = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("Auto tone") { update(current.copy(exposure = .12f, contrast = 8f, highlights = -28f, shadows = 24f, whites = 7f, blacks = -8f)) }
            ToolButton("Copy settings") { copySettings(current); Toast.makeText(context, "Edit settings copied", Toast.LENGTH_SHORT).show() }
            ToolButton("Paste settings") {
                val pasted = pasteSettings()
                if (pasted != null) update(pasted) else Toast.makeText(context, "Copy settings from a photo first", Toast.LENGTH_SHORT).show()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            ToolButton("Reset adjustments") { update(current.geometryOnly()) }
            ToolButton("Reset all") { update(DevelopState()) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Text("Remove from library", color = Color(0xFFFF7777), fontSize = 10.sp, modifier = Modifier.clickable(onClick = delete).padding(8.dp))
        }
        Text("Removal clears the local catalog entry and edit recipe; the original file is never deleted.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PresetsPanel(
    current: DevelopState, presets: List<Preset>, favorites: List<String>, favorite: (String) -> Unit,
    create: (String, DevelopState, Set<String>) -> Unit, importPreset: () -> Unit,
    onRenamePreset: (String, String) -> Unit, onDeletePreset: (String) -> Unit,
    previewing: Preset?, amount: Float,
    onPreview: (Preset) -> Unit, onAmount: (Float) -> Unit, onApply: () -> Unit, onCancel: () -> Unit,
    thumbSource: Bitmap?, thumbnailState: (Preset) -> DevelopState,
) {
    var category by remember { mutableStateOf("Recommended") }
    var saving by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<Preset?>(null) }
    val applied = presets.firstOrNull { it.id == current.presetId }
    val baseCats = listOf("Recommended", "Premium", "Film", "Vintage", "B&W")
    val packCats = presets.map { it.category }.distinct().filter { it !in baseCats && it != "Yours" }.sorted()
    val categories = listOf("Recommended", "Favorites", "Yours") + baseCats.drop(1) + packCats
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EditorTitle(
                    "PRESETS",
                    when {
                        previewing != null -> "Previewing ${previewing.name} · Apply to keep"
                        applied != null -> "Applied: ${applied.name} · tap it again to remove"
                        else -> "Tap a look to preview it"
                    },
                )
            }
            Text("Import", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable { importPreset(); category = "Yours" }.padding(8.dp))
            Text("+ Save", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable { saving = true }.padding(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            categories.forEach { item -> FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item, fontSize = 10.sp) }) }
        }
        val shown = if (category == "Favorites") presets.filter { it.id in favorites } else presets.filter { it.category == category }
        if (shown.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(if (category == "Yours") "Save your current edit with + Save, or Import an .xmp" else "No presets here yet", color = Muted, fontSize = 12.sp)
        }
        else LazyRow(Modifier.weight(1f).padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { preset ->
                PresetTile(
                    preset, thumbSource, thumbnailState(preset).thumbnailKey(),
                    previewing = previewing?.id == preset.id, applied = previewing == null && current.presetId == preset.id,
                    favorite = preset.id in favorites, onFavorite = { favorite(preset.id) },
                    onClick = { onPreview(preset) }, onLongClick = if (preset.builtIn) null else ({ managing = preset }),
                )
            }
        }
        if (previewing != null) PresetApplyBar(amount, onAmount, onCancel, onApply)
    }
    if (saving) SavePresetDialog(current, onDismiss = { saving = false }) { name, fields -> saving = false; category = "Yours"; create(name, current, fields) }
    managing?.let { preset -> ManagePresetDialog(preset, onDismiss = { managing = null }, onRename = { onRenamePreset(preset.id, it); managing = null }, onDelete = { onDeletePreset(preset.id); managing = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    preset: Preset, thumbSource: Bitmap?, look: DevelopState,
    previewing: Boolean, applied: Boolean, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)?,
) {
    val highlighted = previewing || applied
    // The photo rendered with this preset; re-rendered only when the preset's result would change.
    val thumbnail by produceState<Bitmap?>(null, thumbSource, look) {
        value = thumbSource?.let { runCatching { ImageExporter.renderPreview(it, look, draft = true, thumbnail = true) }.getOrNull() }
    }
    Column(Modifier.fillMaxHeight().width(86.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF24262A))
                .border(if (highlighted) 2.dp else 0.dp, if (highlighted) Accent else Color.Transparent, RoundedCornerShape(8.dp)),
        ) {
            thumbnail?.let { Image(it.asImageBitmap(), preset.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                if (favorite) "★" else "☆", color = if (favorite) WarmAccent else Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopEnd).clickable(onClick = onFavorite).padding(5.dp),
            )
            if (applied) Text("✓", color = CanvasBlack, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(5.dp).background(Accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Text(preset.name, color = if (highlighted) Accent else Ink, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PresetApplyBar(amount: Float, onAmount: (Float) -> Unit, onCancel: () -> Unit, onApply: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Amount", color = Muted, fontSize = 11.sp, modifier = Modifier.width(65.dp))
            LumaSlider(amount, onAmount, 0f..200f, origin = 0f, modifier = Modifier.weight(1f))
            Text(amount.toInt().toString(), color = Ink, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) { Text("✕  Cancel", fontSize = 12.sp) }
            Button(onClick = onApply, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = CanvasBlack), modifier = Modifier.weight(1f)) { Text("✓  Apply", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Lightroom-style "Create Preset": a name and which setting groups the preset carries. */
@Composable
private fun SavePresetDialog(current: DevelopState, onDismiss: () -> Unit, onSave: (String, Set<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val changed = remember(current) { current.changedFields() }
    // Groups with edits start ticked; Masks stay off by default since they're tied to one photo's content.
    val chosen = remember { mutableStateListOf<String>().apply { addAll(presetGroups.filter { (group, fields) -> group != "Masks" && fields.any { it in changed } }.map { it.first }) } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).width(300.dp)) {
                Text("Save preset", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, placeholder = { Text("Preset name", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                Text("Include", color = Muted, fontSize = 11.sp)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    presetGroups.forEach { (group, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { if (group in chosen) chosen.remove(group) else chosen.add(group) }) {
                            Checkbox(checked = group in chosen, onCheckedChange = { if (it) chosen.add(group) else chosen.remove(group) })
                            Text(group, color = Ink, fontSize = 13.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancel", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
                    val canSave = chosen.isNotEmpty()
                    Text("Save", color = if (canSave) Accent else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = canSave) {
                        val fields = presetGroups.filter { it.first in chosen }.flatMap { it.second }.toSet()
                        onSave(name.trim().ifBlank { "My preset" }, fields)
                    }.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ManagePresetDialog(preset: Preset, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(preset.name) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp).width(300.dp)) {
                Text("Edit preset", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete", color = Color(0xFFFF7777), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDelete).padding(10.dp))
                    Spacer(Modifier.weight(1f))
                    Text("Cancel", color = Muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp))
                    Text("Rename", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onRename(name) }.padding(10.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MaskControls(
    state: DevelopState,
    activeId: String?,
    select: (String?) -> Unit,
    update: (DevelopState) -> Unit,
) {
    LaunchedEffect(state.masks, activeId) {
        if (activeId != null && state.masks.none { it.id == activeId }) select(state.masks.lastOrNull()?.id)
    }
    fun add(type: String) {
        if (state.masks.size >= 8) return
        // Sizes are in short-side units. A new Linear gradient starts at the top (like darkening a sky), as in Lightroom.
        val mask = LocalMask(
            id = "mask-${System.currentTimeMillis()}", type = type,
            size = when (type) { "Brush" -> .05f; "Linear" -> .4f; else -> .35f },
            angle = if (type == "Linear") -90f else 0f,
        )
        update(state.copy(masks = state.masks + mask)); select(mask.id)
    }
    fun replace(mask: LocalMask) {
        update(state.copy(masks = state.masks.map { if (it.id == mask.id) mask else it }))
    }
    val active = state.masks.firstOrNull { it.id == activeId } ?: state.masks.lastOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("MASKING", "Local, feathered adjustments") }
            Text("${state.masks.size}/8", color = Muted, fontSize = 10.sp)
        }
        // Wrapping rows: every mask type stays visible on a narrow phone (manual tools first, then AI selections).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            ToolButton("+ Brush") { add("Brush") }
            ToolButton("+ Linear") { add("Linear") }
            ToolButton("+ Radial") { add("Radial") }
            ToolButton("+ Person") { add("Subject") }
            ToolButton("+ Sky") { add("Sky") }
            ToolButton("+ Hair") { add("Hair") }
            ToolButton("+ Skin") { add("Skin") }
            ToolButton("+ Clothes") { add("Clothes") }
        }
        if (state.masks.isEmpty()) {
            Text("Add a brush, linear, or radial mask. Paint or position it directly on the photo.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            state.masks.forEachIndexed { index, mask ->
                FilterChip(
                    selected = mask.id == active?.id,
                    onClick = { select(mask.id) },
                    label = { Text("${index + 1} ${if (mask.type == "Subject") "Person" else mask.type}", fontSize = 9.sp) },
                )
            }
        }
        if (active != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = active.enabled, onClick = { replace(active.copy(enabled = !active.enabled)) }, label = { Text(if (active.enabled) "Enabled" else "Disabled", fontSize = 9.sp) })
                FilterChip(selected = active.invert, onClick = { replace(active.copy(invert = !active.invert)) }, label = { Text("Invert", fontSize = 9.sp) })
                Spacer(Modifier.weight(1f))
                Text("Delete", color = Color(0xFFFF7777), fontSize = 10.sp, modifier = Modifier.clickable {
                    val remaining = state.masks.filterNot { it.id == active.id }
                    update(state.copy(masks = remaining)); select(remaining.lastOrNull()?.id)
                }.padding(8.dp))
            }
            if (active.type == "Brush" && active.brushStrokes.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("Undo stroke", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { replace(active.copy(points = emptyList(), strokes = active.brushStrokes.dropLast(1))) }.padding(8.dp))
                    Text("Clear", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { replace(active.copy(points = emptyList(), strokes = emptyList())) }.padding(8.dp))
                }
            }
            val aiMask = active.type in aiMaskTypes
            EditorSlider("Exposure", active.exposure, -4f..4f, { replace(active.copy(exposure = it)) }, 2)
            EditorSlider("Contrast", active.contrast, -100f..100f, { replace(active.copy(contrast = it)) })
            EditorSlider("Temp", active.temperature, -100f..100f, { replace(active.copy(temperature = it)) }, gradient = TempTrack)
            EditorSlider("Highlights", active.highlights, -100f..100f, { replace(active.copy(highlights = it)) })
            EditorSlider("Shadows", active.shadows, -100f..100f, { replace(active.copy(shadows = it)) })
            EditorSlider("Tint", active.tint, -100f..100f, { replace(active.copy(tint = it)) }, gradient = TintTrack)
            EditorSlider("Saturation", active.saturation, -100f..100f, { replace(active.copy(saturation = it)) }, gradient = SaturationTrack)
            if (!aiMask) {
                EditorSlider("Feather", active.feather * 100f, 2f..100f, { replace(active.copy(feather = it / 100f)) })
                EditorSlider(when (active.type) { "Linear" -> "Width"; "Brush" -> "Brush size"; else -> "Size" }, active.size * 100f, if (active.type == "Brush") 1f..30f else 5f..100f, { replace(active.copy(size = it / 100f)) })
                if (active.type == "Brush") EditorSlider("Flow", active.flow * 100f, 5f..100f, { replace(active.copy(flow = it / 100f)) })
                if (active.type == "Linear") EditorSlider("Angle", active.angle, -180f..180f, { replace(active.copy(angle = it)) })
            }
            Text(
                when {
                    active.type == "Subject" -> "On-device AI selects people. Toggle Invert to adjust the background instead."
                    active.type == "Hair" || active.type == "Skin" || active.type == "Clothes" -> "On-device AI selects people's ${active.type.lowercase()}. Toggle Invert to adjust everything else."
                    active.type == "Sky" -> "AI selects the sky on-device. Toggle Invert for the foreground. Rendered on export."
                    active.type == "Brush" -> "Drag on the photo to paint. Stroke points are simplified and rendered through a cached alpha map at export."
                    else -> "The overlay is a fast preview; full-resolution feathering is rendered during export."
                },
                color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun fittedImageRect(size: androidx.compose.ui.geometry.Size, imageAspect: Float): androidx.compose.ui.geometry.Rect {
    val safeAspect = imageAspect.coerceAtLeast(.05f)
    val canvasAspect = size.width / size.height.coerceAtLeast(1f)
    val width: Float
    val height: Float
    if (canvasAspect > safeAspect) { height = size.height; width = height * safeAspect }
    else { width = size.width; height = width / safeAspect }
    val left = (size.width - width) / 2f; val top = (size.height - height) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
}

@Composable
private fun MaskGuide(state: DevelopState, activeId: String?, imageAspect: Float, update: (DevelopState) -> Unit) {
    val active = state.masks.firstOrNull { it.id == activeId } ?: state.masks.lastOrNull() ?: return
    if (active.type in aiMaskTypes) return
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    /** Edits the active mask from the latest state (never a copy captured when the gesture started). */
    fun updateMask(transform: (LocalMask) -> LocalMask) {
        val current = latestState
        latestUpdate(current.copy(masks = current.masks.map { if (it.id == active.id) transform(it) else it }))
    }
    fun normalized(position: androidx.compose.ui.geometry.Offset, width: Float, height: Float): MaskPoint? {
        val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(width, height), imageAspect)
        if (!imageRect.contains(position)) return null
        return MaskPoint(((position.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f), ((position.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f))
    }
    // Brush: every drag (or tap) starts a new stroke, so separate strokes are never joined. During a drag the
    // strokes are built in a gesture-local copy: reading the editor state per event could miss our own update
    // that hasn't been applied yet, and extend the previous stroke instead.
    var working by remember(active.id) { mutableStateOf<List<List<MaskPoint>>>(emptyList()) }
    fun push(strokes: List<List<MaskPoint>>) { working = strokes; updateMask { it.copy(points = emptyList(), strokes = strokes) } }
    fun startStroke(point: MaskPoint) {
        val existing = (latestState.masks.firstOrNull { it.id == active.id } ?: active).brushStrokes
        if (existing.sumOf { it.size } < MAX_BRUSH_POINTS) push(existing + listOf(listOf(point))) else working = existing
    }
    fun extendStroke(point: MaskPoint) {
        val strokes = working
        val last = strokes.lastOrNull()?.lastOrNull() ?: return
        if ((last.x - point.x) * (last.x - point.x) + (last.y - point.y) * (last.y - point.y) < .000064f || strokes.sumOf { it.size } >= MAX_BRUSH_POINTS) return
        push(strokes.dropLast(1) + listOf(strokes.last() + point))
    }
    Canvas(Modifier.fillMaxSize().pointerInput(active.id, active.type) {
        if (active.type == "Brush") detectTapGestures(onTap = { p -> normalized(p, size.width.toFloat(), size.height.toFloat())?.let(::startStroke) })
    }.pointerInput(active.id, active.type) {
        detectDragGestures(
            onDragStart = { position -> if (active.type == "Brush") normalized(position, size.width.toFloat(), size.height.toFloat())?.let(::startStroke) },
        ) { event, drag ->
            event.consume()
            if (active.type == "Brush") {
                normalized(event.position, size.width.toFloat(), size.height.toFloat())?.let(::extendStroke)
            } else {
                val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
                updateMask { mask ->
                    mask.copy(
                        centerX = (mask.centerX + drag.x / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        centerY = (mask.centerY + drag.y / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }) {
        val imageRect = fittedImageRect(size, imageAspect)
        val center = androidx.compose.ui.geometry.Offset(imageRect.left + active.centerX * imageRect.width, imageRect.top + active.centerY * imageRect.height)
        val guide = if (active.enabled) Accent else Muted
        val angle = Math.toRadians(active.angle.toDouble())
        val normal = androidx.compose.ui.geometry.Offset(cos(angle).toFloat(), sin(angle).toFloat())
        // Sizes are in units of the photo's short side, matching the renderer.
        val unit = minOf(imageRect.width, imageRect.height)
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            if (active.type == "Brush") {
                val radius = active.size * unit
                val outer = Stroke(radius * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val inner = Stroke(radius * 2f * (1f - active.feather).coerceAtLeast(.08f), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                active.brushStrokes.forEach { stroke ->
                    val points = stroke.map { androidx.compose.ui.geometry.Offset(imageRect.left + it.x * imageRect.width, imageRect.top + it.y * imageRect.height) }
                    if (points.size == 1) {
                        drawCircle(guide.copy(.22f), radius, points[0]); drawCircle(guide.copy(.7f), radius * (1f - active.feather).coerceAtLeast(.08f), points[0])
                    } else {
                        val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, guide.copy(.22f), style = outer); drawPath(path, guide.copy(.7f), style = inner)
                    }
                }
            } else if (active.type == "Linear") {
                val direction = androidx.compose.ui.geometry.Offset(-normal.y, normal.x)
                val extent = maxOf(imageRect.width, imageRect.height) * 1.5f
                val halfTransition = active.size * active.feather * unit / 2f
                drawLine(guide, center - direction * extent, center + direction * extent, 2.dp.toPx())
                drawLine(guide.copy(.55f), center - direction * extent - normal * halfTransition, center + direction * extent - normal * halfTransition, 1.dp.toPx())
                drawLine(guide.copy(.55f), center - direction * extent + normal * halfTransition, center + direction * extent + normal * halfTransition, 1.dp.toPx())
            } else {
                val radius = active.size * unit
                drawCircle(guide.copy(.12f), radius, center)
                drawCircle(guide, radius, center, style = Stroke(2.dp.toPx()))
                drawCircle(guide.copy(.5f), radius * (1f - active.feather), center, style = Stroke(1.dp.toPx()))
            }
        }
        if (active.type != "Brush") {
            drawCircle(Color.White, 5.dp.toPx(), center)
            drawCircle(guide, 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun RemoveControls(
    state: DevelopState,
    activeId: String?,
    select: (String?) -> Unit,
    update: (DevelopState) -> Unit,
) {
    LaunchedEffect(state.healingSpots, activeId) {
        if (activeId != null && state.healingSpots.none { it.id == activeId }) select(state.healingSpots.lastOrNull()?.id)
    }
    fun addSpot(contentAware: Boolean = false) {
        if (state.healingSpots.size >= 24) return
        // Generative Remove starts empty with a mid-size brush; nothing is removed until it's painted.
        val spot = if (contentAware) HealingSpot(id = "spot-${System.currentTimeMillis()}", contentAware = true, brush = true, radius = .035f, feather = .4f)
        else HealingSpot(id = "spot-${System.currentTimeMillis()}")
        update(state.copy(healingSpots = state.healingSpots + spot)); select(spot.id)
    }
    fun replace(spot: HealingSpot) {
        update(state.copy(healingSpots = state.healingSpots.map { if (it.id == spot.id) spot else it }))
    }
    val active = state.healingSpots.firstOrNull { it.id == activeId } ?: state.healingSpots.lastOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("REMOVE", "Local heal and clone") }
            Text("${state.healingSpots.size}/24", color = Muted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 5.dp)) {
            ToolButton("+ Heal/Clone") { addSpot(false) }
            ToolButton("+ Generative Remove") { addSpot(true) }
            if (active != null && !active.contentAware) ToolButton("Auto source") {
                replace(active.copy(sourceX = (active.targetX - .2f).coerceIn(0f, 1f), sourceY = active.targetY))
            }
        }
        if (state.healingSpots.isEmpty()) {
            Text("Heal/Clone copies from a source you drag; Generative Remove regenerates the area (no source needed).", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            GenerativeRemoveModelCard()
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            state.healingSpots.forEachIndexed { index, spot ->
                FilterChip(selected = spot.id == active?.id, onClick = { select(spot.id) }, label = { Text("${if (spot.contentAware) "Remove" else "Heal"} ${index + 1}", fontSize = 9.sp) })
            }
        }
        if (active != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active.isPainted) {
                    Text("Undo stroke", color = if (active.strokes.isNotEmpty()) Accent else Muted, fontSize = 10.sp, modifier = Modifier.clickable(enabled = active.strokes.isNotEmpty()) { replace(active.copy(strokes = active.strokes.dropLast(1))) }.padding(8.dp))
                    Text("Clear", color = if (active.strokes.isNotEmpty()) Accent else Muted, fontSize = 10.sp, modifier = Modifier.clickable(enabled = active.strokes.isNotEmpty()) { replace(active.copy(strokes = emptyList())) }.padding(8.dp))
                } else {
                    FilterChip(selected = active.contentAware, onClick = { replace(active.copy(contentAware = !active.contentAware)) }, label = { Text(if (active.contentAware) "Generative Remove" else "Heal / Clone", fontSize = 9.sp) })
                }
                Spacer(Modifier.weight(1f))
                Text("Delete spot", color = Color(0xFFFF7777), fontSize = 10.sp, modifier = Modifier.clickable {
                    val remaining = state.healingSpots.filterNot { it.id == active.id }
                    update(state.copy(healingSpots = remaining)); select(remaining.lastOrNull()?.id)
                }.padding(8.dp))
            }
            if (active.isPainted) EditorSlider("Brush size", active.radius * 100f, .5f..15f, { replace(active.copy(radius = it / 100f)) }, 1)
            else EditorSlider("Size", active.radius * 100f, 1f..30f, { replace(active.copy(radius = it / 100f)) })
            EditorSlider("Feather", active.feather * 100f, 2f..100f, { replace(active.copy(feather = it / 100f)) })
            EditorSlider("Opacity", active.opacity * 100f, 5f..100f, { replace(active.copy(opacity = it / 100f)) })
            Text(
                when {
                    active.isPainted && active.strokes.isEmpty() -> "Paint over the object on the photo. Cover it completely, including its shadow."
                    active.isPainted -> "Paint more strokes to extend this removal. The red area is regenerated from its surroundings."
                    active.contentAware -> "Drag the blue circle over the object; the area is regenerated from its surroundings."
                    else -> "Clone/heal copies from the amber source to the blue target."
                },
                color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp),
            )
            if (active.contentAware) GenerativeRemoveModelCard()
        }
    }
}

/** Paints Generative Remove strokes: each drag adds a stroke; the covered area shows as a red overlay. */
@Composable
private fun RemoveBrushGuide(state: DevelopState, active: HealingSpot, imageAspect: Float, update: (DevelopState) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    var cursor by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var painting by remember { mutableStateOf(false) }
    var working by remember(active.id) { mutableStateOf<List<List<MaskPoint>>>(emptyList()) }
    fun currentStrokes() = (latestState.healingSpots.firstOrNull { it.id == active.id } ?: active).strokes
    /** Writes [strokes] to the spot; during a drag they're built in [working] so no event reads a stale state. */
    fun push(strokes: List<List<MaskPoint>>) {
        working = strokes
        val current = latestState
        latestUpdate(current.copy(healingSpots = current.healingSpots.map { if (it.id == active.id) it.copy(strokes = strokes) else it }))
    }
    fun normalized(position: androidx.compose.ui.geometry.Offset, width: Float, height: Float): MaskPoint? {
        val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(width, height), imageAspect)
        if (!imageRect.contains(position)) return null
        return MaskPoint(((position.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f), ((position.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f))
    }
    Canvas(Modifier.fillMaxSize().pointerInput(active.id) {
        // A tap dabs a single brush-sized dot (small blemishes), a drag paints a stroke.
        detectTapGestures(onTap = { position ->
            val point = normalized(position, size.width.toFloat(), size.height.toFloat()) ?: return@detectTapGestures
            val strokes = currentStrokes()
            if (strokes.sumOf { it.size } < MAX_REMOVE_POINTS) push(strokes + listOf(listOf(point)))
        })
    }.pointerInput(active.id) {
        fun normalized(position: androidx.compose.ui.geometry.Offset) = normalized(position, size.width.toFloat(), size.height.toFloat())
        detectDragGestures(
            onDragStart = { position ->
                cursor = position; painting = true
                val point = normalized(position) ?: return@detectDragGestures
                val strokes = currentStrokes()
                if (strokes.sumOf { it.size } < MAX_REMOVE_POINTS) push(strokes + listOf(listOf(point))) else working = strokes
            },
            onDragEnd = { cursor = null; painting = false }, onDragCancel = { cursor = null; painting = false },
        ) { event, _ ->
            event.consume()
            cursor = event.position
            val point = normalized(event.position) ?: return@detectDragGestures
            val strokes = working
            val last = strokes.lastOrNull() ?: return@detectDragGestures
            val tail = last.last()
            // Skip points closer than ~0.4% of the frame: smooth strokes without bloating the recipe.
            if ((tail.x - point.x) * (tail.x - point.x) + (tail.y - point.y) * (tail.y - point.y) >= .000016f && strokes.sumOf { it.size } < MAX_REMOVE_POINTS)
                push(strokes.dropLast(1) + listOf(last + point))
        }
    }) {
        val imageRect = fittedImageRect(size, imageAspect)
        val radius = active.radius * minOf(imageRect.width, imageRect.height)
        // Strong while painting; faint afterwards so the regenerated result shows through.
        val paint = Color(0xFFFF4D4D).copy(alpha = if (painting) .45f else .14f)
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            active.strokes.forEach { stroke ->
                val points = stroke.map { androidx.compose.ui.geometry.Offset(imageRect.left + it.x * imageRect.width, imageRect.top + it.y * imageRect.height) }
                if (points.size == 1) drawCircle(paint, radius, points[0])
                else drawPath(
                    Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }, paint,
                    style = Stroke(radius * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                )
            }
            cursor?.let { drawCircle(Color.White, radius, it, style = Stroke(1.5.dp.toPx())) }
        }
    }
}

/** Download/status card for the LaMa model behind Generative Remove. */
@Composable
private fun GenerativeRemoveModelCard() {
    val status by Inpainter.status.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(.05f)).padding(12.dp)) {
        when (val s = status) {
            Inpainter.Status.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ AI model installed · LaMa, runs on-device", color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Remove", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable { Inpainter.delete() }.padding(6.dp))
            }
            is Inpainter.Status.Downloading -> {
                Text("Downloading AI model… ${(s.progress * 100).toInt()}%", color = Ink, fontSize = 11.sp)
                LinearProgressIndicator(progress = { s.progress }, color = Accent, trackColor = Color.White.copy(.12f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> {
                Text("Use AI for Generative Remove", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    (if (s is Inpainter.Status.Failed) "Download failed: ${s.message}. " else "") +
                        "Downloads the LaMa model once (${Inpainter.DOWNLOAD_MB} MB, Wi-Fi recommended). It runs fully on this device; until then a simpler fill is used.",
                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Text("Download model", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).clickable { Inpainter.download() }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun HealingGuide(state: DevelopState, activeId: String?, imageAspect: Float, update: (DevelopState) -> Unit) {
    val active = state.healingSpots.firstOrNull { it.id == activeId } ?: state.healingSpots.lastOrNull() ?: return
    if (active.isPainted) { RemoveBrushGuide(state, active, imageAspect, update); return }
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    Canvas(Modifier.fillMaxSize().pointerInput(active.id) {
        var movingSource = false
        detectDragGestures(
            onDragStart = { position ->
                val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
                // Current handle positions (not the ones captured when this gesture handler was created).
                val spot = latestState.healingSpots.firstOrNull { it.id == active.id } ?: active
                val source = androidx.compose.ui.geometry.Offset(imageRect.left + spot.sourceX * imageRect.width, imageRect.top + spot.sourceY * imageRect.height)
                val target = androidx.compose.ui.geometry.Offset(imageRect.left + spot.targetX * imageRect.width, imageRect.top + spot.targetY * imageRect.height)
                movingSource = (position - source).getDistance() < (position - target).getDistance()
            },
        ) { event, _ ->
            event.consume()
            val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
            val x = ((event.position.x - imageRect.left) / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val y = ((event.position.y - imageRect.top) / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val current = latestState
            latestUpdate(current.copy(healingSpots = current.healingSpots.map { spot ->
                if (spot.id != active.id) spot else if (movingSource) spot.copy(sourceX = x, sourceY = y) else spot.copy(targetX = x, targetY = y)
            }))
        }
    }) {
        val imageRect = fittedImageRect(size, imageAspect)
        val source = androidx.compose.ui.geometry.Offset(imageRect.left + active.sourceX * imageRect.width, imageRect.top + active.sourceY * imageRect.height)
        val target = androidx.compose.ui.geometry.Offset(imageRect.left + active.targetX * imageRect.width, imageRect.top + active.targetY * imageRect.height)
        val radius = active.radius * minOf(imageRect.width, imageRect.height)
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            drawLine(Color.White.copy(.45f), source, target, 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 7f)))
            drawCircle(WarmAccent.copy(.12f), radius, source)
            drawCircle(WarmAccent, radius, source, style = Stroke(2.dp.toPx()))
            drawCircle(Accent.copy(.12f), radius, target)
            drawCircle(Accent, radius, target, style = Stroke(2.dp.toPx()))
            drawCircle(WarmAccent, 5.dp.toPx(), source)
            drawCircle(Accent, 5.dp.toPx(), target)
        }
    }
}

/** Aspect of the photo after its 90° turns (straighten keeps the frame size). */
private fun orientedAspect(imageAspect: Float, rotation: Int): Float =
    if (((rotation % 180) + 180) % 180 == 90) 1f / imageAspect else imageAspect

/** Sets [aspect] and fits the largest crop of that ratio, centred on the current crop. */
private fun DevelopState.withCropAspect(aspect: String, frameAspect: Float): DevelopState {
    if (aspect == "Free") return copy(cropAspect = aspect)
    val fitted = copy(cropAspect = aspect, cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f).effectiveCrop(frameAspect)
    return copy(cropAspect = aspect, cropLeft = fitted[0], cropTop = fitted[1], cropRight = fitted[2], cropBottom = fitted[3])
}

@Composable
private fun CropControls(state: DevelopState, frameAspect: Float, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("CROP & ROTATE", "Drag the box or its corners on the photo") }
            Text("Reset", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable {
                update(state.copy(straighten = 0f).withCropAspect("Original", frameAspect))
            }.padding(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Free", "Original", "1:1", "4:5", "3:2", "16:9", "9:16").forEach { ratio ->
                FilterChip(selected = state.cropAspect == ratio, onClick = { update(state.withCropAspect(ratio, frameAspect)) }, label = { Text(ratio, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            // A quarter turn swaps the frame's aspect, so the crop is refitted to the turned frame.
            ToolButton("↺ Rotate") { update(state.copy(rotation = (state.rotation - 90).floorRotation()).withCropAspect(state.cropAspect.takeUnless { it == "Free" } ?: "Original", 1f / frameAspect)) }
            ToolButton("↻ Rotate") { update(state.copy(rotation = (state.rotation + 90).floorRotation()).withCropAspect(state.cropAspect.takeUnless { it == "Free" } ?: "Original", 1f / frameAspect)) }
            ToolButton(if (state.flipHorizontal) "Unflip" else "⇋ Flip") { update(state.copy(flipHorizontal = !state.flipHorizontal)) }
        }
        EditorSlider("Straighten", state.straighten, -45f..45f, { update(state.copy(straighten = it)) }, 1)
        Text("Straightening zooms in just enough that no empty corners show, like Lightroom's Constrain.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Crop box over the straightened frame: drag inside to move it, drag a corner to resize (ratio-locked unless Free). */
@Composable
private fun CropGuide(state: DevelopState, frameAspect: Float, update: (DevelopState) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    val minSide = .08f
    Canvas(
        Modifier.fillMaxSize().systemGestureExclusion().pointerInput(frameAspect) {
            var mode = -1 // -1 none, 4 move, 0..3 corners TL, TR, BR, BL
            var rect = floatArrayOf(0f, 0f, 1f, 1f)
            detectDragGestures(
                onDragStart = { p ->
                    rect = latestState.effectiveCrop(frameAspect)
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val corners = listOf(rect[0] to rect[1], rect[2] to rect[1], rect[2] to rect[3], rect[0] to rect[3])
                    val reach = 40.dp.toPx()
                    val hit = corners.indexOfFirst { (cx, cy) -> kotlin.math.hypot(cx * w - p.x, cy * h - p.y) < reach }
                    mode = when {
                        hit >= 0 -> hit
                        p.x in rect[0] * w..rect[2] * w && p.y in rect[1] * h..rect[3] * h -> 4
                        else -> -1
                    }
                },
            ) { change, drag ->
                if (mode < 0) return@detectDragGestures
                change.consume()
                val dx = drag.x / size.width; val dy = drag.y / size.height
                rect = if (mode == 4) {
                    val w = rect[2] - rect[0]; val h = rect[3] - rect[1]
                    val l = (rect[0] + dx).coerceIn(0f, 1f - w); val t = (rect[1] + dy).coerceIn(0f, 1f - h)
                    floatArrayOf(l, t, l + w, t + h)
                } else {
                    val right = mode == 1 || mode == 2; val bottom = mode == 2 || mode == 3
                    val ax = if (right) rect[0] else rect[2]; val ay = if (bottom) rect[1] else rect[3]
                    val cx = (if (right) rect[2] else rect[0]) + dx; val cy = (if (bottom) rect[3] else rect[1]) + dy
                    val maxW = if (right) 1f - ax else ax; val maxH = if (bottom) 1f - ay else ay
                    var w = (if (right) cx - ax else ax - cx).coerceIn(minOf(minSide, maxW), maxW)
                    var h = (if (bottom) cy - ay else ay - cy).coerceIn(minOf(minSide, maxH), maxH)
                    cropRatio(latestState.cropAspect, frameAspect)?.let { ratio ->
                        val target = ratio / frameAspect // normalized width / height
                        if (w / h > target) h = w / target else w = h * target
                        if (w > maxW) { w = maxW; h = w / target }
                        if (h > maxH) { h = maxH; w = h * target }
                    }
                    val l = if (right) ax else ax - w; val t = if (bottom) ay else ay - h
                    floatArrayOf(l, t, l + w, t + h)
                }
                latestUpdate(latestState.copy(cropLeft = rect[0], cropTop = rect[1], cropRight = rect[2], cropBottom = rect[3]))
            }
        },
    ) {
        val r = state.effectiveCrop(frameAspect)
        val left = r[0] * size.width; val top = r[1] * size.height; val right = r[2] * size.width; val bottom = r[3] * size.height
        val shade = Color.Black.copy(.55f)
        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, top))
        drawRect(shade, androidx.compose.ui.geometry.Offset(0f, bottom), androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
        drawRect(shade, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(left, bottom - top))
        drawRect(shade, androidx.compose.ui.geometry.Offset(right, top), androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
        val line = Color.White
        drawRect(line, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(1.2.dp.toPx()))
        for (step in 1..2) {
            val x = left + (right - left) * step / 3f; val y = top + (bottom - top) * step / 3f
            drawLine(line.copy(.4f), androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Offset(x, bottom), 1.dp.toPx())
            drawLine(line.copy(.4f), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1.dp.toPx())
        }
        val arm = 18.dp.toPx(); val thick = 3.dp.toPx()
        listOf(Triple(left, top, 1f to 1f), Triple(right, top, -1f to 1f), Triple(right, bottom, -1f to -1f), Triple(left, bottom, 1f to -1f)).forEach { (x, y, dir) ->
            drawLine(line, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + arm * dir.first, y), thick)
            drawLine(line, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + arm * dir.second), thick)
        }
    }
}

/** Where the photo sits on screen: the layer transform and the visible frame (crop, or the whole photo in Crop). */
private class FrameView(val scale: Float, val translationX: Float, val translationY: Float, val frameWidth: Float, val frameHeight: Float, val frameAspect: Float) {
    companion object {
        fun of(state: DevelopState, imageAspect: Float, boxW: Float, boxH: Float, cropMode: Boolean): FrameView {
            // The image is drawn Fit into the box, then turned; oriented size is that fitted size after 90° turns.
            val dispW = minOf(boxW, boxH * imageAspect); val dispH = dispW / imageAspect
            val quarter = ((state.rotation % 180) + 180) % 180 == 90
            val orientedW = if (quarter) dispH else dispW; val orientedH = if (quarter) dispW else dispH
            val frameAspect = orientedW / orientedH
            val zoom = straightenScale(state.straighten, frameAspect)
            if (cropMode) {
                // Margin so corner handles stay clear of the screen edges (and the system back gesture).
                val s = minOf(boxW / orientedW, boxH / orientedH) * .84f
                return FrameView(s * zoom, 0f, 0f, orientedW * s, orientedH * s, frameAspect)
            }
            val r = state.effectiveCrop(frameAspect)
            val cropW = (r[2] - r[0]) * orientedW; val cropH = (r[3] - r[1]) * orientedH
            val s = minOf(boxW / cropW, boxH / cropH)
            val offsetX = ((r[0] + r[2]) / 2f - .5f) * orientedW; val offsetY = ((r[1] + r[3]) / 2f - .5f) * orientedH
            return FrameView(s * zoom, -s * offsetX, -s * offsetY, cropW * s, cropH * s, frameAspect)
        }
    }
}

/** Post-crop vignette drawn over the visible frame (the preview renders the photo uncropped, so it can't bake it in). */
@Composable
private fun VignetteOverlay(state: DevelopState) {
    val amount = (state.vignette + state.opticsVignette * .5f).coerceIn(-100f, 100f) / 100f
    if (abs(amount) < .01f) return
    Canvas(Modifier.fillMaxSize()) {
        val start = (.2f + state.vignetteMidpoint / 100f * .75f - (.05f + state.vignetteFeather / 100f * .7f) / 2f).coerceIn(0f, .95f) * .8f
        val tone = if (amount < 0f) Color.Black else Color.White
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(start to Color.Transparent, 1f to tone.copy(alpha = abs(amount) * .9f)),
                center = center, radius = size.maxDimension * .72f,
            ),
        )
    }
}

/** Geometry fields don't change the rendered pixels (the layer applies them), so they don't trigger a re-render. */
private fun DevelopState.renderKey(): DevelopState = copy(
    rotation = 0, straighten = 0f, flipHorizontal = false, cropAspect = "Original",
    cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f, cropZoom = 1f, cropOffsetX = 0f, cropOffsetY = 0f,
    geometryVertical = 0f, geometryHorizontal = 0f, geometryAspect = 0f, lensDistortion = 0f,
    vignette = 0f, opticsVignette = 0f, presetId = null, presetBase = null,
)

/** Like [renderKey], but thumbnails show the whole look, so the preset's vignette is kept. */
private fun DevelopState.thumbnailKey(): DevelopState = renderKey().copy(vignette = vignette, opticsVignette = opticsVignette)

private fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return this
    val scale = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}

@Composable
private fun GeometryControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("GEOMETRY", "Perspective and aspect correction")
        Canvas(Modifier.fillMaxWidth().height(82.dp).background(Color(0xFF181A1D), RoundedCornerShape(7.dp)).padding(9.dp)) {
            val vertical = state.geometryVertical / 100f * size.width * .16f
            val horizontal = state.geometryHorizontal / 100f * size.height * .16f
            val points = listOf(
                androidx.compose.ui.geometry.Offset(if (vertical > 0) vertical else 0f, if (horizontal > 0) horizontal else 0f),
                androidx.compose.ui.geometry.Offset(if (vertical > 0) size.width - vertical else size.width, if (horizontal < 0) -horizontal else 0f),
                androidx.compose.ui.geometry.Offset(if (vertical < 0) size.width + vertical else size.width, if (horizontal < 0) size.height + horizontal else size.height),
                androidx.compose.ui.geometry.Offset(if (vertical < 0) -vertical else 0f, if (horizontal > 0) size.height - horizontal else size.height),
            )
            val path = Path().apply { moveTo(points[0].x, points[0].y); lineTo(points[1].x, points[1].y); lineTo(points[2].x, points[2].y); lineTo(points[3].x, points[3].y); close() }
            drawPath(path, Accent.copy(alpha = .18f)); drawPath(path, Accent, style = Stroke(2.dp.toPx()))
            drawLine(Color.White.copy(.25f), androidx.compose.ui.geometry.Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), 1.dp.toPx())
            drawLine(Color.White.copy(.25f), androidx.compose.ui.geometry.Offset(0f, size.height / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), 1.dp.toPx())
        }
        EditorSlider("Vertical", state.geometryVertical, -100f..100f, { update(state.copy(geometryVertical = it)) })
        EditorSlider("Horizontal", state.geometryHorizontal, -100f..100f, { update(state.copy(geometryHorizontal = it)) })
        EditorSlider("Aspect", state.geometryAspect, -100f..100f, { update(state.copy(geometryAspect = it)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset geometry", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { update(state.copy(geometryVertical = 0f, geometryHorizontal = 0f, geometryAspect = 0f)) }.padding(8.dp))
        }
    }
}

private fun Int.floorRotation(): Int = ((this % 360) + 360) % 360

@Composable
private fun ToolButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    Text(
        label, color = if (enabled) Ink else Muted.copy(.5f), fontSize = 12.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.07f)).clickable(enabled = enabled, onClick = action).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun LightControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("LIGHT", "Tone and dynamic range") }
            Text("AUTO", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { update(state.copy(exposure = .12f, contrast = 8f, highlights = -28f, shadows = 24f, whites = 7f, blacks = -8f)) }.padding(8.dp))
        }
        EditorSlider("Exposure", state.exposure, -4f..4f, { update(state.copy(exposure = it)) }, 2)
        EditorSlider("Contrast", state.contrast, -100f..100f, { update(state.copy(contrast = it)) })
        EditorSlider("Highlights", state.highlights, -100f..100f, { update(state.copy(highlights = it)) })
        EditorSlider("Shadows", state.shadows, -100f..100f, { update(state.copy(shadows = it)) })
        EditorSlider("Whites", state.whites, -100f..100f, { update(state.copy(whites = it)) })
        EditorSlider("Blacks", state.blacks, -100f..100f, { update(state.copy(blacks = it)) })
    }
}

@Composable
private fun CurveControls(state: DevelopState, update: (DevelopState) -> Unit) {
    var mode by remember { mutableStateOf("Point") }
    var channel by remember { mutableStateOf("RGB") }
    val channelColor = mapOf(
        "RGB" to Ink, "Red" to Color(0xFFE85A5A), "Green" to Color(0xFF65C66A), "Blue" to Color(0xFF5897E8),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EditorTitle("TONE CURVE", if (mode == "Point") "$channel · drag to shape, long-press node to remove" else "Parametric regions")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Point", "Parametric").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m, fontSize = 9.sp) }) }
            }
        }
        if (mode == "Parametric") {
            Canvas(Modifier.fillMaxWidth().height(100.dp).background(Color(0xFF181A1D), RoundedCornerShape(7.dp)).padding(8.dp)) {
                val grid = Color.White.copy(alpha = .10f)
                for (step in 1..3) {
                    val x = size.width * step / 4f; val y = size.height * step / 4f
                    drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                }
                val points = listOf(
                    androidx.compose.ui.geometry.Offset(0f, size.height),
                    androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * (.75f - state.curveShadows / 240f).coerceIn(0f, 1f)),
                    androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * (.5f - state.curveMidtones / 240f).coerceIn(0f, 1f)),
                    androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * (.25f - state.curveHighlights / 240f).coerceIn(0f, 1f)),
                    androidx.compose.ui.geometry.Offset(size.width, 0f),
                )
                val path = Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, Accent, style = Stroke(2.dp.toPx()))
                points.forEach { drawCircle(Ink, 4.dp.toPx(), it) }
            }
            EditorSlider("Shadows", state.curveShadows, -100f..100f, { update(state.copy(curveShadows = it)) })
            EditorSlider("Midtones", state.curveMidtones, -100f..100f, { update(state.copy(curveMidtones = it)) })
            EditorSlider("Highlights", state.curveHighlights, -100f..100f, { update(state.copy(curveHighlights = it)) })
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                toneCurveChannels.forEach { ch ->
                    FilterChip(selected = channel == ch, onClick = { channel = ch }, label = { Text(ch, fontSize = 9.sp, color = channelColor.getValue(ch)) })
                }
            }
            PointCurveEditor(state, channel, channelColor.getValue(channel), update)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Reset $channel", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable {
                    val next = state.curves.toMutableMap(); next.remove(channel); update(state.copy(curves = next))
                }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun PointCurveEditor(state: DevelopState, channel: String, lineColor: Color, update: (DevelopState) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    fun pointsOf(s: DevelopState): List<CurvePoint> {
        val p = s.curves[channel] ?: emptyList()
        return if (p.isEmpty()) listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)) else p.sortedBy { it.x }
    }
    fun commit(points: List<CurvePoint>) {
        val cleaned = points.sortedBy { it.x }
        val next = latestState.curves.toMutableMap()
        if (cleaned.isIdentityCurve()) next.remove(channel) else next[channel] = cleaned
        latestUpdate(latestState.copy(curves = next))
    }
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF181A1D), RoundedCornerShape(7.dp)).padding(8.dp)
            .pointerInput(channel) {
                var working = listOf<CurvePoint>()
                var dragIndex = -1
                val threshold = 34.dp.toPx() * 34.dp.toPx()
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        working = pointsOf(latestState)
                        var best = -1; var bestDist = Float.MAX_VALUE
                        working.forEachIndexed { i, p ->
                            val sx = p.x * w; val sy = (1f - p.y) * h
                            val d = (pos.x - sx) * (pos.x - sx) + (pos.y - sy) * (pos.y - sy)
                            if (d < bestDist) { bestDist = d; best = i }
                        }
                        if (best >= 0 && bestDist <= threshold) {
                            dragIndex = best
                        } else {
                            val nx = (pos.x / w).coerceIn(0f, 1f); val ny = (1f - pos.y / h).coerceIn(0f, 1f)
                            working = (working + CurvePoint(nx, ny)).sortedBy { it.x }
                            dragIndex = working.indexOfFirst { it.x == nx && it.y == ny }
                            commit(working)
                        }
                    },
                ) { event, _ ->
                    event.consume()
                    if (dragIndex < 0 || dragIndex >= working.size) return@detectDragGestures
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val ny = (1f - event.position.y / h).coerceIn(0f, 1f)
                    var nx = (event.position.x / w).coerceIn(0f, 1f)
                    when (dragIndex) {
                        0 -> nx = 0f
                        working.size - 1 -> nx = 1f
                        else -> {
                            val lo = working[dragIndex - 1].x + .02f
                            val hi = working[dragIndex + 1].x - .02f
                            nx = if (lo >= hi) (working[dragIndex - 1].x + working[dragIndex + 1].x) / 2f else nx.coerceIn(lo, hi)
                        }
                    }
                    working = working.toMutableList().also { it[dragIndex] = CurvePoint(nx, ny) }
                    commit(working)
                }
            }
            .pointerInput(channel) {
                val threshold = 40.dp.toPx() * 40.dp.toPx()
                detectTapGestures(onLongPress = { pos ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val pts = pointsOf(latestState)
                    var best = -1; var bestDist = Float.MAX_VALUE
                    pts.forEachIndexed { i, p ->
                        val sx = p.x * w; val sy = (1f - p.y) * h
                        val d = (pos.x - sx) * (pos.x - sx) + (pos.y - sy) * (pos.y - sy)
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    if (best > 0 && best < pts.size - 1 && bestDist <= threshold) commit(pts.filterIndexed { i, _ -> i != best })
                })
            },
    ) {
        val w = size.width; val h = size.height
        val grid = Color.White.copy(.10f)
        for (s in 1..3) {
            val x = w * s / 4f; val y = h * s / 4f
            drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), 1f)
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), 1f)
        }
        drawLine(Color.White.copy(.14f), androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, 0f), 1f)
        val pts = pointsOf(state)
        val path = Path()
        for (i in 0..64) {
            val t = i / 64f
            val v = evaluateCurve(pts, t)
            val sx = t * w; val sy = (1f - v) * h
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
        pts.forEach { p ->
            val c = androidx.compose.ui.geometry.Offset(p.x * w, (1f - p.y) * h)
            drawCircle(Color(0xFF181A1D), 5.dp.toPx(), c)
            drawCircle(lineColor, 5.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ColorControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("COLOR", state.profile)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Adobe Color", "Adobe Vivid", "Adobe Portrait", "Adobe Neutral", "Monochrome").forEach { profile -> FilterChip(selected = state.profile == profile, onClick = { update(state.copy(profile = profile)) }, label = { Text(profile, fontSize = 9.sp) }) }
        }
        Text("White balance", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        val wbPresets = listOf(
            "As Shot" to Pair(0f, 0f), "Auto" to Pair(6f, -3f), "Daylight" to Pair(10f, 2f), "Cloudy" to Pair(22f, 6f),
            "Shade" to Pair(32f, 10f), "Tungsten" to Pair(-38f, 8f), "Fluorescent" to Pair(-18f, 18f), "Flash" to Pair(16f, 4f),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            wbPresets.forEach { (name, tempTint) ->
                val match = kotlin.math.abs(state.temperature - tempTint.first) < .5f && kotlin.math.abs(state.tint - tempTint.second) < .5f
                FilterChip(selected = match, onClick = { update(state.copy(temperature = tempTint.first, tint = tempTint.second)) }, label = { Text(name, fontSize = 9.sp) })
            }
        }
        EditorSlider("Temperature", state.temperature, -100f..100f, { update(state.copy(temperature = it)) }, gradient = TempTrack)
        EditorSlider("Tint", state.tint, -100f..100f, { update(state.copy(tint = it)) }, gradient = TintTrack)
        EditorSlider("Vibrance", state.vibrance, -100f..100f, { update(state.copy(vibrance = it)) }, gradient = SaturationTrack)
        EditorSlider("Saturation", state.saturation, -100f..100f, { update(state.copy(saturation = it)) }, gradient = SaturationTrack)
    }
}

private val mixerColors = mapOf(
    "Red" to Color(0xFFE85A5A), "Orange" to Color(0xFFF39B49), "Yellow" to Color(0xFFE7D451),
    "Green" to Color(0xFF65C66A), "Aqua" to Color(0xFF54C9C6), "Blue" to Color(0xFF5897E8),
    "Purple" to Color(0xFF9367D8), "Magenta" to Color(0xFFD85BA7),
)

@Composable
private fun ColorMixerControls(state: DevelopState, update: (DevelopState) -> Unit) {
    if (state.profile == "Monochrome") { BwMixerControls(state, update); return }
    var selected by remember { mutableStateOf("Red") }
    val colors = mixerColors
    val adjustment = state.colorMix[selected] ?: ColorMix()
    fun apply(value: ColorMix) {
        val next = state.colorMix.toMutableMap()
        if (value == ColorMix()) next.remove(selected) else next[selected] = value
        update(state.copy(colorMix = next))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("COLOR MIXER", "$selected · targeted HSL")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            colorMixChannels.forEach { channel ->
                Box(
                    Modifier.size(if (selected == channel) 42.dp else 36.dp).clip(CircleShape).background(colors.getValue(channel)).border(if (selected == channel) 3.dp else 1.dp, if (selected == channel) Ink else Color.White.copy(.28f), CircleShape).clickable { selected = channel },
                    contentAlignment = Alignment.Center,
                ) { if (selected == channel) Text("✓", color = Color.Black.copy(.72f), fontWeight = FontWeight.Bold) }
            }
        }
        val index = colorMixChannels.indexOf(selected)
        val swatch = colors.getValue(selected)
        val previous = colors.getValue(colorMixChannels[(index + 7) % 8]); val next = colors.getValue(colorMixChannels[(index + 1) % 8])
        EditorSlider("Hue", adjustment.hue, -100f..100f, { apply(adjustment.copy(hue = it)) }, gradient = listOf(previous, swatch, next))
        EditorSlider("Saturation", adjustment.saturation, -100f..100f, { apply(adjustment.copy(saturation = it)) }, gradient = listOf(Color(0xFF8A8A8A), swatch))
        EditorSlider("Luminance", adjustment.luminance, -100f..100f, { apply(adjustment.copy(luminance = it)) }, gradient = listOf(Color.Black, swatch, Color.White))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset $selected", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { apply(ColorMix()) }.padding(8.dp))
        }
    }
}

@Composable
private fun BwMixerControls(state: DevelopState, update: (DevelopState) -> Unit) {
    var selected by remember { mutableStateOf("Red") }
    val value = state.bwMix[selected] ?: 0f
    fun apply(v: Float) {
        val next = state.bwMix.toMutableMap()
        if (v == 0f) next.remove(selected) else next[selected] = v
        update(state.copy(bwMix = next))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("B&W MIX", "$selected · gray response per color")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            colorMixChannels.forEach { channel ->
                Box(
                    Modifier.size(if (selected == channel) 42.dp else 36.dp).clip(CircleShape).background(mixerColors.getValue(channel)).border(if (selected == channel) 3.dp else 1.dp, if (selected == channel) Ink else Color.White.copy(.28f), CircleShape).clickable { selected = channel },
                    contentAlignment = Alignment.Center,
                ) { if (selected == channel) Text("✓", color = Color.Black.copy(.72f), fontWeight = FontWeight.Bold) }
            }
        }
        EditorSlider("Gray", value, -100f..100f, { apply(it) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset mix", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { update(state.copy(bwMix = emptyMap())) }.padding(8.dp))
        }
        Text("Negative darkens, positive lightens how each original color maps to gray.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ColorGradingControls(state: DevelopState, update: (DevelopState) -> Unit) {
    var range by remember { mutableStateOf("Shadows") }
    // One wheel at a time with a range selector, as in Lightroom mobile: angle is hue, distance from centre saturation.
    val (hue, amount) = when (range) {
        "Midtones" -> state.midtoneHue to state.midtoneGrade
        "Highlights" -> state.highlightHue to state.highlightGrade
        else -> state.shadowHue to state.shadowGrade
    }
    fun set(newHue: Float, newAmount: Float) = update(when (range) {
        "Midtones" -> state.copy(midtoneHue = newHue, midtoneGrade = newAmount)
        "Highlights" -> state.copy(highlightHue = newHue, highlightGrade = newAmount)
        else -> state.copy(shadowHue = newHue, shadowGrade = newAmount)
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("COLOR GRADING", "Tint shadows, midtones and highlights")
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Shadows", "Midtones", "Highlights").forEach { item ->
                val itemAmount = when (item) { "Midtones" -> state.midtoneGrade; "Highlights" -> state.highlightGrade; else -> state.shadowGrade }
                FilterChip(selected = range == item, onClick = { range = item }, label = { Text(if (itemAmount > 0f) "$item •" else item, fontSize = 10.sp) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            GradeWheel(hue, amount, Modifier.size(132.dp)) { h, a -> set(h, a) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                EditorSlider("Hue", hue, 0f..360f, { set(it, amount) }, default = when (range) { "Midtones" -> 30f; "Highlights" -> 45f; else -> 220f }, gradient = HueTrack)
                EditorSlider("Saturation", amount, 0f..100f, { set(hue, it) }, gradient = listOf(Color(0xFF8A8A8A), hueColor(hue)))
            }
        }
        EditorSlider("Balance", state.gradingBalance, -100f..100f, { update(state.copy(gradingBalance = it)) }, gradient = listOf(Color(0xFF2A2A2A), Color(0xFFDADADA)))
    }
}

/** A colour wheel: drag the puck; angle sets hue (0 red at 3 o'clock, counter-clockwise), radius sets saturation. */
@Composable
private fun GradeWheel(hue: Float, amount: Float, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val latestHue by rememberUpdatedState(hue)
    val wheel = remember { colorWheelImage(256) }
    Canvas(modifier.systemGestureExclusion().pointerInput(Unit) {
        fun pick(p: androidx.compose.ui.geometry.Offset) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val d = p - c
            val radius = minOf(size.width, size.height) / 2f
            val h = ((Math.toDegrees(kotlin.math.atan2(-d.y.toDouble(), d.x.toDouble())).toFloat()) + 360f) % 360f
            latest(h, (d.getDistance() / radius * 100f).coerceIn(0f, 100f))
        }
        detectDragGestures(onDragStart = { pick(it) }) { change, _ -> change.consume(); pick(change.position) }
    }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { latest(latestHue, 0f) }) }) {
        val radius = size.minDimension / 2f
        drawImage(wheel, dstOffset = androidx.compose.ui.unit.IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()), dstSize = androidx.compose.ui.unit.IntSize((radius * 2).toInt(), (radius * 2).toInt()))
        drawCircle(Color.Black.copy(.25f), radius, style = Stroke(1.dp.toPx()))
        val angle = Math.toRadians(hue.toDouble())
        val puck = center + androidx.compose.ui.geometry.Offset((kotlin.math.cos(angle) * radius * amount / 100f).toFloat(), (-kotlin.math.sin(angle) * radius * amount / 100f).toFloat())
        drawCircle(Color.White, 8.dp.toPx(), puck, style = Stroke(2.5.dp.toPx()))
        drawCircle(Color.Black.copy(.35f), 9.5.dp.toPx(), puck, style = Stroke(1.dp.toPx()))
    }
}

/** Pre-rendered wheel (no gradient seams): hue from the angle (counter-clockwise from 3 o'clock), saturation from the radius. */
private fun colorWheelImage(size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val half = size / 2f
    val pixels = IntArray(size * size) { i ->
        val dx = i % size - half + .5f; val dy = i / size - half + .5f
        val r = kotlin.math.sqrt(dx * dx + dy * dy) / half
        if (r > 1f) 0 else {
            val h = ((Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
            val alpha = ((1f - r) * half * 2f).coerceIn(0f, 1f) // anti-aliased edge
            android.graphics.Color.HSVToColor((alpha * 255).toInt(), floatArrayOf(h, r, .96f))
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun hueColor(hue: Float): Color = Color.hsv(((hue % 360f) + 360f) % 360f, 1f, 1f)
private val HueTrack = (0..6).map { hueColor(it * 60f) }
private val TempTrack = listOf(Color(0xFF3F7FD9), Color(0xFFDADADA), Color(0xFFE8C24A))
private val TintTrack = listOf(Color(0xFF4CB050), Color(0xFFDADADA), Color(0xFFD653C8))
private val SaturationTrack = listOf(Color(0xFF8A8A8A), Color(0xFFE0457B), Color(0xFFF2B33D), Color(0xFF3EB8E0))

/** Lightroom's Calibration panel: how the red, green and blue primaries render, plus a shadow tint. */
@Composable
private fun CalibrationControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("CALIBRATION", "Primaries: the base colour rendering") }
            Text("Reset", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable {
                update(state.copy(redHue = 0f, redSaturation = 0f, greenHue = 0f, greenSaturation = 0f, blueHue = 0f, blueSaturation = 0f, shadowTint = 0f))
            }.padding(8.dp))
        }
        EditorSlider("Shadows tint", state.shadowTint, -100f..100f, { update(state.copy(shadowTint = it)) }, gradient = TintTrack)
        SubHeader("Red primary")
        EditorSlider("Hue", state.redHue, -100f..100f, { update(state.copy(redHue = it)) }, gradient = listOf(Color(0xFFD9406E), Color(0xFFE5483C), Color(0xFFEB9333)))
        EditorSlider("Saturation", state.redSaturation, -100f..100f, { update(state.copy(redSaturation = it)) }, gradient = listOf(Color(0xFF8A8A8A), Color(0xFFE5483C)))
        SubHeader("Green primary")
        EditorSlider("Hue", state.greenHue, -100f..100f, { update(state.copy(greenHue = it)) }, gradient = listOf(Color(0xFFC9C23A), Color(0xFF4CB050), Color(0xFF3BB5A3)))
        EditorSlider("Saturation", state.greenSaturation, -100f..100f, { update(state.copy(greenSaturation = it)) }, gradient = listOf(Color(0xFF8A8A8A), Color(0xFF4CB050)))
        SubHeader("Blue primary")
        EditorSlider("Hue", state.blueHue, -100f..100f, { update(state.copy(blueHue = it)) }, gradient = listOf(Color(0xFF3BB5C9), Color(0xFF3F64D9), Color(0xFF8A4BD6)))
        EditorSlider("Saturation", state.blueSaturation, -100f..100f, { update(state.copy(blueSaturation = it)) }, gradient = listOf(Color(0xFF8A8A8A), Color(0xFF3F64D9)))
    }
}

@Composable
private fun OpticsControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("OPTICS", "Lens correction and color-fringe") }
            Text("AUTO", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { update(state.copy(lensDistortion = 18f, opticsVignette = 22f, defringe = 20f)) }.padding(8.dp))
        }
        EditorSlider("Distortion", state.lensDistortion, -100f..100f, { update(state.copy(lensDistortion = it)) })
        EditorSlider("Lens shading", state.opticsVignette, -100f..100f, { update(state.copy(opticsVignette = it)) })
        EditorSlider("Defringe", state.defringe, 0f..100f, { update(state.copy(defringe = it)) })
        HorizontalDivider(color = Color.White.copy(.08f), modifier = Modifier.padding(vertical = 10.dp))
        Text("Auto applies a typical profile: straighten barrel distortion, lift corners, and defringe. Distortion renders on export.", color = Muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset optics", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { update(state.copy(lensDistortion = 0f, opticsVignette = 0f, defringe = 0f)) }.padding(8.dp))
        }
    }
}

@Composable
private fun EffectsControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("EFFECTS", "Presence and atmosphere")
        EditorSlider("Texture", state.texture, -100f..100f, { update(state.copy(texture = it)) })
        EditorSlider("Clarity", state.clarity, -100f..100f, { update(state.copy(clarity = it)) })
        EditorSlider("Dehaze", state.dehaze, -100f..100f, { update(state.copy(dehaze = it)) })
        SubHeader("Vignette")
        EditorSlider("Amount", state.vignette, -100f..100f, { update(state.copy(vignette = it)) }, gradient = listOf(Color.Black, Color(0xFF8A8A8A), Color.White))
        if (state.vignette != 0f) {
            EditorSlider("Midpoint", state.vignetteMidpoint, 0f..100f, { update(state.copy(vignetteMidpoint = it)) }, default = 50f)
            EditorSlider("Feather", state.vignetteFeather, 0f..100f, { update(state.copy(vignetteFeather = it)) }, default = 50f)
            EditorSlider("Roundness", state.vignetteRoundness, -100f..100f, { update(state.copy(vignetteRoundness = it)) })
        }
        SubHeader("Grain")
        EditorSlider("Amount", state.grain, 0f..100f, { update(state.copy(grain = it)) })
        if (state.grain > 0f) {
            EditorSlider("Size", state.grainSize, 0f..100f, { update(state.copy(grainSize = it)) }, default = 25f)
            EditorSlider("Roughness", state.grainRoughness, 0f..100f, { update(state.copy(grainRoughness = it)) }, default = 50f)
        }
    }
}

@Composable
private fun DetailControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("DETAIL", "Sharpening and noise reduction")
        SubHeader("Sharpening")
        EditorSlider("Amount", state.sharpen, 0f..100f, { update(state.copy(sharpen = it)) })
        EditorSlider("Radius", state.sharpenRadius, 0.5f..3f, { update(state.copy(sharpenRadius = it)) }, 1, default = 1f)
        EditorSlider("Detail", state.sharpenDetail, 0f..100f, { update(state.copy(sharpenDetail = it)) }, default = 25f)
        EditorSlider("Masking", state.sharpenMasking, 0f..100f, { update(state.copy(sharpenMasking = it)) })
        SubHeader("Noise Reduction")
        EditorSlider("Luminance", state.noiseReduction, 0f..100f, { update(state.copy(noiseReduction = it)) })
        EditorSlider("Detail", state.nrDetail, 0f..100f, { update(state.copy(nrDetail = it)) }, default = 50f)
        EditorSlider("Contrast", state.nrContrast, 0f..100f, { update(state.copy(nrContrast = it)) })
        EditorSlider("Color noise", state.colorNoise, 0f..100f, { update(state.copy(colorNoise = it)) })
        Spacer(Modifier.height(10.dp)); Text("Masking limits sharpening to edges; NR Detail protects fine texture. Export renders the full stack.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SubHeader(label: String) {
    Text(label.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun QuickActionsPanel(
    bitmap: Bitmap?, state: DevelopState, update: (DevelopState) -> Unit,
    looks: List<Preset>, previewing: Preset?, amount: Float,
    onPreview: (Preset) -> Unit, onAmount: (Float) -> Unit, onApply: () -> Unit, onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            EditorTitle("QUICK ACTIONS", if (previewing != null) "Previewing ${previewing.name} · Apply to keep" else "One-tap enhancements")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                // While a look is previewed, other edits would be lost on Cancel/Amount, so they wait for Apply/Cancel.
                val free = previewing == null
                ToolButton("Auto enhance", enabled = free) {
                    if (bitmap != null) update(ImageAnalysis.autoEnhance(bitmap, state))
                    else Toast.makeText(context, "Image still loading", Toast.LENGTH_SHORT).show()
                }
                ToolButton("Auto straighten", enabled = free) {
                    if (bitmap != null) update(state.copy(straighten = ImageAnalysis.autoStraighten(bitmap)))
                    else Toast.makeText(context, "Image still loading", Toast.LENGTH_SHORT).show()
                }
                ToolButton("Auto WB", enabled = free) {
                    if (bitmap != null) { val a = ImageAnalysis.autoEnhance(bitmap, state); update(state.copy(temperature = a.temperature, tint = a.tint)) }
                }
            }
            SubHeader("One-tap looks")
            // Same behaviour as presets: tap to preview, tap again to cancel, Apply to keep, tap an applied look to remove it.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                looks.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { look ->
                            val isPreviewing = previewing?.id == look.id
                            val isApplied = previewing == null && state.presetId == look.id
                            Text(
                                (if (isApplied) "✓ " else "") + look.name,
                                color = if (isPreviewing || isApplied) CanvasBlack else Ink, fontSize = 12.sp, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (isApplied) Accent else if (isPreviewing) Ink else Color.White.copy(.07f))
                                    .clickable { onPreview(look) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
            SubHeader("Background (subject AI)")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("None", "Blur", "B&W", "Darken", "Pop").forEach { option ->
                    FilterChip(selected = state.background == option, enabled = previewing == null, onClick = { update(state.copy(background = option)) }, label = { Text(option, fontSize = 10.sp) })
                }
            }
            Text(if (previewing != null) "Apply or cancel the look to use auto tools and background effects." else "Separates people from the background on-device.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (previewing != null) PresetApplyBar(amount, onAmount, onCancel, onApply)
    }
}

@Composable
private fun LensBlurControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("LENS BLUR", "Bokeh with a focus area")
        EditorSlider("Amount", state.lensBlur, 0f..100f, { update(state.copy(lensBlur = it)) })
        EditorSlider("Focus size", state.lensBlurFocusRadius * 100f, 5f..100f, { update(state.copy(lensBlurFocusRadius = it / 100f)) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { update(state.copy(lensBlur = 0f, lensBlurFocusX = .5f, lensBlurFocusY = .5f, lensBlurFocusRadius = .3f)) }.padding(8.dp))
        }
        Text("Drag on the photo to set the in-focus point. Everything outside the focus area blurs; it renders at full resolution on export.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LensBlurGuide(state: DevelopState, imageAspect: Float, update: (DevelopState) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        detectDragGestures { event, _ ->
            event.consume()
            val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
            val x = ((event.position.x - imageRect.left) / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val y = ((event.position.y - imageRect.top) / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
            latestUpdate(latestState.copy(lensBlurFocusX = x, lensBlurFocusY = y))
        }
    }) {
        val imageRect = fittedImageRect(size, imageAspect)
        val center = androidx.compose.ui.geometry.Offset(imageRect.left + state.lensBlurFocusX * imageRect.width, imageRect.top + state.lensBlurFocusY * imageRect.height)
        val radius = (state.lensBlurFocusRadius * imageRect.height).coerceAtLeast(4f) // renderer measures focus in units of height
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            drawCircle(Accent.copy(.10f), radius, center)
            drawCircle(Accent, radius, center, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 5.dp.toPx(), center)
            drawCircle(Accent, 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun EditorTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(title.lowercase().replaceFirstChar { it.uppercase() }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

/** Label and value sit above the track; double-tap the label row to reset to [default] (when one exists). */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit, decimals: Int = 0,
    default: Float? = 0f.takeIf { it in range }, gradient: List<Color>? = null,
) {
    val changed = default != null && abs(value - default) > .001f
    // The gesture handler outlives recompositions; call the newest callback, or a reset would write back stale state.
    val latestChange by rememberUpdatedState(change)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().pointerInput(default) { detectTapGestures(onDoubleTap = { default?.let(latestChange) }) },
        ) {
            Text(label, color = if (changed) Ink else Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val text = when (decimals) { 2 -> String.format("%.2f", value); 1 -> String.format("%.1f", value); else -> value.toInt().toString() }
            Text(if (changed && value > 0f && 0f in range && range.start < 0f) "+$text" else text, color = if (changed) Accent else Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        LumaSlider(value, change, range, origin = default ?: range.start, gradient = gradient)
    }
}

/** Thin track that fills from [origin] (the neutral value), so +/- adjustments read at a glance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumaSlider(
    value: Float, change: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, origin: Float,
    modifier: Modifier = Modifier.fillMaxWidth(), gradient: List<Color>? = null,
) {
    Slider(
        value, change, valueRange = range, modifier = modifier.height(30.dp),
        thumb = { Box(Modifier.size(16.dp).background(Ink, CircleShape).border(1.5.dp, CanvasBlack.copy(.6f), CircleShape)) },
        track = { sliderState ->
            // Colour sliders (Temp, Tint, HSL...) show what they do, like Lightroom; others fill from the neutral point.
            if (gradient != null) Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                drawRoundRect(Brush.horizontalGradient(gradient), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
            } else Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val span = (range.endInclusive - range.start).coerceAtLeast(.0001f)
                fun x(v: Float) = (v - range.start) / span * size.width
                val start = x(origin.coerceIn(range.start, range.endInclusive))
                val current = x(sliderState.value)
                drawRoundRect(Color.White.copy(.14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
                drawRect(Accent, androidx.compose.ui.geometry.Offset(minOf(start, current), 0f), androidx.compose.ui.geometry.Size(abs(current - start), size.height))
            }
        },
    )
}

@Composable
private fun sliderColors() = SliderDefaults.colors(activeTrackColor = Accent, thumbColor = Ink, inactiveTrackColor = Color.White.copy(.14f))
