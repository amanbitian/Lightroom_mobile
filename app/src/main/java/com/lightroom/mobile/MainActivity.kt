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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Button
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumaRoomApp() }
    }
}

private enum class AppTab { LIBRARY, EDIT, SETTINGS }
private enum class ToolPanel(val label: String) {
    ACTIONS("Actions"), PRESETS("Presets"), MASKS("Masks"), REMOVE("Remove"), CROP("Crop"), GEOMETRY("Geometry"), LIGHT("Light"), CURVE("Curve"), COLOR("Color"), MIX("Mix"), GRADE("Grading"), OPTICS("Optics"), EFFECTS("Effects"), DETAIL("Detail")
}

private val Ink = Color(0xFFF5F2ED)
private val Muted = Color(0xFF9C9A97)
private val Panel = Color(0xFF17181B)
private val CanvasBlack = Color(0xFF0C0D0F)
private val Accent = Color(0xFF52B7FF)
private val WarmAccent = Color(0xFFEAB46C)

@Composable
private fun LumaRoomApp() {
    val context = LocalContext.current
    val store = remember { LibraryStore(context) }
    val photos = remember { mutableStateListOf<PhotoItem>().apply { addAll(store.loadPhotos()) } }
    val customPresets = remember { mutableStateListOf<Preset>().apply { addAll(store.loadCustomPresets()) } }
    val favoritePresets = remember { mutableStateListOf<String>().apply { addAll(store.loadFavoritePresets()) } }
    var albums by remember { mutableStateOf(store.loadAlbums()) }
    var exportOptions by remember { mutableStateOf(store.loadExportOptions()) }
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var editing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AppTab.LIBRARY) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (photos.none { it.uri == uri.toString() }) {
                photos.add(0, PhotoItem(uri.toString(), displayName(context, uri, "Photo ${photos.size + 1}")))
            }
        }
        store.savePhotos(photos)
    }

    MaterialTheme {
        Surface(color = CanvasBlack, modifier = Modifier.fillMaxSize()) {
            if (editing && selectedPhoto != null) {
                EditorScreen(
                    photo = selectedPhoto!!,
                    initialState = store.loadEdit(selectedPhoto!!.uri),
                    favoritePresets = favoritePresets,
                    customPresets = customPresets,
                    onFavorite = { id ->
                        if (id in favoritePresets) favoritePresets.remove(id) else favoritePresets.add(id)
                        store.saveFavoritePresets(favoritePresets.toSet())
                    },
                    onCreatePreset = { state ->
                        val number = customPresets.size + 1
                        customPresets.add(Preset("custom-${System.currentTimeMillis()}", "My Preset $number", "Yours", state, false))
                        store.saveCustomPresets(customPresets)
                    },
                    onPhotoUpdate = { updated ->
                        val index = photos.indexOfFirst { it.uri == updated.uri }
                        if (index >= 0) photos[index] = updated
                        selectedPhoto = updated
                        store.savePhotos(photos)
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
                        store.removePhoto(uri)
                        photos.removeAll { it.uri == uri }
                        selectedPhoto = null; editing = false; tab = AppTab.LIBRARY
                    },
                    onSave = { state -> selectedPhoto?.uri?.let { uri -> store.saveEdit(uri, state) } },
                    onBack = { editing = false; tab = AppTab.LIBRARY },
                )
            } else {
                Scaffold(containerColor = CanvasBlack, bottomBar = { BottomNavigation(tab) { tab = it } }) { padding ->
                    when (tab) {
                        AppTab.LIBRARY -> LibraryScreen(
                            photos = photos,
                            albums = albums,
                            isEdited = store::hasEdit,
                            modifier = Modifier.padding(padding),
                            onImport = { picker.launch(arrayOf("image/*")) },
                            onPhotoClick = { selectedPhoto = it; editing = true; tab = AppTab.EDIT },
                        )
                        AppTab.EDIT -> EmptyEditState(Modifier.padding(padding)) { picker.launch(arrayOf("image/*")) }
                        AppTab.SETTINGS -> SettingsScreen(photos.size, modifier = Modifier.padding(padding))
                    }
                }
            }
        }
    }
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
    photos: List<PhotoItem>, albums: Map<String, Set<String>>, isEdited: (String) -> Boolean, modifier: Modifier = Modifier,
    onImport: () -> Unit, onPhotoClick: (PhotoItem) -> Unit,
) {
    var filter by remember { mutableStateOf("All Photos") }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Newest") }
    var density by remember { mutableIntStateOf(145) }
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
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("LUMAROOM", color = Accent, fontSize = 11.sp, letterSpacing = 2.6.sp, fontWeight = FontWeight.Bold)
                Text(filter, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            IconButton(onClick = onImport, modifier = Modifier.size(46.dp).background(Accent, CircleShape)) { Text("+", color = CanvasBlack, fontSize = 27.sp) }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            smartFilters.forEach { label ->
                FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, fontSize = 12.sp) })
            }
            albums.keys.sorted().forEach { album ->
                FilterChip(selected = filter == album, onClick = { filter = album }, label = { Text("▤ $album", fontSize = 12.sp) })
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search by filename", color = Muted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text("Sort", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 7.dp))
            listOf("Newest", "Oldest", "Name", "Rating").forEach { option ->
                FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option, fontSize = 10.sp) }, modifier = Modifier.padding(end = 6.dp))
            }
            Spacer(Modifier.width(6.dp))
            listOf("S" to 185, "M" to 145, "L" to 110).forEach { (label, cell) ->
                FilterChip(selected = density == cell, onClick = { density = cell }, label = { Text(label, fontSize = 10.sp) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (photos.isEmpty()) EmptyLibrary(onImport, Modifier.weight(1f)) else {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (visible.size == 1) "1 photo" else "${visible.size} photos", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(sort, color = Muted, fontSize = 12.sp)
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(density.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(visible, key = { it.uri }) { photo -> PhotoTile(photo, isEdited(photo.uri)) { onPhotoClick(photo) } }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxWidth()) {
        DemoPreview(Modifier.size(190.dp, 245.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(24.dp)); Text("Your darkroom starts here", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text("Import photos. Originals always stay untouched.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = CanvasBlack), modifier = Modifier.padding(top = 20.dp)) { Text("Import photos") }
    }
}

@Composable
private fun PhotoTile(photo: PhotoItem, edited: Boolean, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick), contentAlignment = Alignment.BottomStart) {
        PhotoBitmap(photo, Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().height(42.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.78f)))))
        Text(photo.name, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(8.dp))
        if (edited) Text("ADJ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(.65f), RoundedCornerShape(bottomStart = 5.dp)).padding(6.dp))
        if (photo.flagged || photo.rejected || photo.rating > 0) Row(Modifier.align(Alignment.TopStart).background(Color.Black.copy(.6f), RoundedCornerShape(bottomEnd = 5.dp)).padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo.flagged) Text("⚑", color = WarmAccent, fontSize = 9.sp)
            if (photo.rejected) Text("✕", color = Color(0xFFFF7777), fontSize = 9.sp)
            if (photo.rating > 0) Text("★${photo.rating}", color = WarmAccent, fontSize = 9.sp, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun PhotoBitmap(photo: PhotoItem, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null, contentScale: ContentScale = ContentScale.Crop, requestedSide: Int = 512) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.uri, requestedSide) {
        value = BitmapRepository.load(context, Uri.parse(photo.uri), requestedSide)
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), photo.name, modifier, contentScale = contentScale, colorFilter = colorFilter) else DemoPreview(modifier)
}

@Composable
private fun DemoPreview(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3B5360), Color(0xFFE6B579), Color(0xFF3B313D))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFFFFD28B).copy(.85f), size.minDimension * .16f, androidx.compose.ui.geometry.Offset(size.width * .68f, size.height * .25f))
            drawRect(Color(0xFF173338).copy(.58f), androidx.compose.ui.geometry.Offset(0f, size.height * .58f), androidx.compose.ui.geometry.Size(size.width, size.height * .42f))
            drawCircle(Color(0xFF6B7B70).copy(.8f), size.minDimension * .22f, androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .73f))
        }
    }
}

@Composable
private fun BottomNavigation(tab: AppTab, select: (AppTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        BottomItem("▦", "Library", tab == AppTab.LIBRARY) { select(AppTab.LIBRARY) }
        BottomItem("✦", "Edit", tab == AppTab.EDIT) { select(AppTab.EDIT) }
        BottomItem("⚙", "Settings", tab == AppTab.SETTINGS) { select(AppTab.SETTINGS) }
    }
}

@Composable
private fun BottomItem(icon: String, label: String, selected: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(90.dp).clickable(onClick = click).padding(5.dp)) {
        Text(icon, color = if (selected) Accent else Muted, fontSize = 19.sp); Text(label, color = if (selected) Ink else Muted, fontSize = 10.sp)
    }
}

@Composable
private fun EmptyEditState(modifier: Modifier = Modifier, import: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("Choose a photo to edit", color = Ink, fontSize = 22.sp); Text("Your edit history stays on this device.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 9.dp))
        OutlinedButton(onClick = import) { Text("Import photos") }
    }
}

@Composable
private fun SettingsScreen(photoCount: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(18.dp)); Text("SETTINGS", color = Accent, fontSize = 11.sp, letterSpacing = 2.6.sp, fontWeight = FontWeight.Bold)
        Text("LumaRoom Mobile", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(26.dp))
        SettingsRow("Local catalog", "$photoCount photos · SQLite indexed · edits saved automatically")
        SettingsRow("Export", "JPEG/PNG · adjustable quality & size · Pictures/LumaRoom")
        SettingsRow("Privacy", "No account · no telemetry · no upload")
        SettingsRow("About", "Version 0.14.0 · Kotlin + Jetpack Compose")
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
    photo: PhotoItem, initialState: DevelopState, favoritePresets: List<String>, customPresets: List<Preset>,
    onFavorite: (String) -> Unit, onCreatePreset: (DevelopState) -> Unit,
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
    var panel by remember { mutableStateOf(ToolPanel.ACTIONS) }
    var develop by remember(photo.uri) { mutableStateOf(initialState) }
    val undo = remember(photo.uri) { mutableStateListOf<DevelopState>() }
    val redo = remember(photo.uri) { mutableStateListOf<DevelopState>() }
    var compare by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var activeMaskId by remember(photo.uri) { mutableStateOf(initialState.masks.firstOrNull()?.id) }
    var activeSpotId by remember(photo.uri) { mutableStateOf(initialState.healingSpots.firstOrNull()?.id) }
    var historyJob by remember { mutableStateOf<Job?>(null) }
    val controlsHeight = (configuration.screenHeightDp * .34f).coerceIn(250f, 360f).dp

    fun change(next: DevelopState) {
        if (next == develop) return
        if (historyJob == null) {
            undo.add(develop); if (undo.size > 50) undo.removeAt(0)
        }
        develop = next; redo.clear()
        historyJob?.cancel()
        historyJob = scope.launch { delay(350); historyJob = null }
    }
    fun back() { onSave(develop); onBack() }
    BackHandler(onBack = ::back)
    LaunchedEffect(develop) { delay(300); onSave(develop) }

    val shown = if (compare) DevelopState(
        rotation = develop.rotation,
        straighten = develop.straighten,
        flipHorizontal = develop.flipHorizontal,
        cropAspect = develop.cropAspect,
        cropZoom = develop.cropZoom,
        cropOffsetX = develop.cropOffsetX,
        cropOffsetY = develop.cropOffsetY,
        geometryVertical = develop.geometryVertical,
        geometryHorizontal = develop.geometryHorizontal,
        geometryAspect = develop.geometryAspect,
    ) else develop
    Column(Modifier.fillMaxSize().background(CanvasBlack).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("‹", color = Ink, fontSize = 35.sp, modifier = Modifier.clickable(onClick = ::back).padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) { Text("EDIT", color = Accent, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold); Text(photo.name, color = Ink, fontSize = 14.sp, maxLines = 1) }
            ActionText("↶", undo.isNotEmpty()) { historyJob?.cancel(); historyJob = null; redo.add(develop); develop = undo.removeAt(undo.lastIndex) }
            ActionText("↷", redo.isNotEmpty()) { historyJob?.cancel(); historyJob = null; undo.add(develop); develop = redo.removeAt(redo.lastIndex) }
            Text(if (exporting) "…" else "Share", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = !exporting) { showExport = true }.padding(10.dp))
        }
        if (showExport) ExportDialog(
            options = exportOptions,
            onDismiss = { showExport = false },
            onExport = { chosen ->
                onExportOptionsChange(chosen)
                showExport = false
                exporting = true
                scope.launch {
                    val result = ImageExporter.export(context, photo, develop, chosen); exporting = false
                    Toast.makeText(context, if (result != null) "Saved to Pictures/LumaRoom" else "Export failed", Toast.LENGTH_LONG).show()
                }
            },
        )
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF202124)).then(
                if (panel == ToolPanel.CROP || panel == ToolPanel.MASKS || panel == ToolPanel.REMOVE) Modifier
                else Modifier.pointerInput(photo.uri) { detectTapGestures(onPress = { compare = true; tryAwaitRelease(); compare = false }) }
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                PhotoBitmap(
                    photo,
                    Modifier.fillMaxSize().graphicsLayer(
                        rotationZ = shown.rotation + shown.straighten,
                        rotationX = -shown.geometryVertical * .12f,
                        rotationY = shown.geometryHorizontal * .12f,
                        cameraDistance = 24f,
                        scaleX = (if (shown.flipHorizontal) -1f else 1f) * shown.cropZoom * (1f + shown.geometryAspect / 200f),
                        scaleY = shown.cropZoom,
                        translationX = -shown.cropOffsetX * 160f * shown.cropZoom,
                        translationY = -shown.cropOffsetY * 160f * shown.cropZoom,
                    ),
                    previewFilter(shown), ContentScale.Fit, requestedSide = 1600,
                )
                if (!compare && editorBitmap != null && develop.healingSpots.isNotEmpty()) HealingPreview(editorBitmap!!, shown, imageAspect)
                val combinedVignette = (develop.vignette + develop.opticsVignette).coerceIn(-100f, 100f)
                if (!compare && combinedVignette < 0f) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy((-combinedVignette / 135f).coerceIn(0f, .72f))))))
                if (!compare && develop.masks.any { it.enabled }) MaskAdjustmentPreview(develop, imageAspect)
            }
            if (!compare && panel == ToolPanel.CROP) CropGuide(develop, ::change)
            if (!compare && panel == ToolPanel.MASKS) MaskGuide(develop, activeMaskId, imageAspect, ::change)
            if (!compare && panel == ToolPanel.REMOVE) HealingGuide(develop, activeSpotId, imageAspect, ::change)
            if (!compare) HistogramOverlay(photo, Modifier.align(Alignment.TopEnd).padding(14.dp))
            val previewHint = when {
                compare -> "BEFORE"
                panel == ToolPanel.CROP -> "Drag to reframe"
                panel == ToolPanel.MASKS -> "Paint or position mask"
                panel == ToolPanel.REMOVE -> "Drag source or target"
                else -> "Hold for before"
            }
            Text(previewHint, color = Color.White, fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(.55f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        LazyRow(Modifier.fillMaxWidth().background(Panel).padding(vertical = 9.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items(ToolPanel.entries) { option -> Text(option.label, color = if (panel == option) Accent else Muted, fontSize = 12.sp, fontWeight = if (panel == option) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable {
                panel = option
                if (option == ToolPanel.MASKS && activeMaskId == null) activeMaskId = develop.masks.firstOrNull()?.id
                if (option == ToolPanel.REMOVE && activeSpotId == null) activeSpotId = develop.healingSpots.firstOrNull()?.id
            }.padding(vertical = 4.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(controlsHeight).background(Color(0xFF111214)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            when (panel) {
                ToolPanel.ACTIONS -> ActionsPanel(photo, develop, albums, onPhotoUpdate, onCopySettings, onPasteSettings, onToggleAlbum, onCreateAlbum, onDelete, ::change)
                ToolPanel.PRESETS -> PresetsPanel(develop, builtInPresets + customPresets, favoritePresets, onFavorite, onCreatePreset, ::change)
                ToolPanel.MASKS -> MaskControls(develop, activeMaskId, { activeMaskId = it }, ::change)
                ToolPanel.REMOVE -> RemoveControls(develop, activeSpotId, { activeSpotId = it }, ::change)
                ToolPanel.CROP -> CropControls(develop, ::change)
                ToolPanel.GEOMETRY -> GeometryControls(develop, ::change)
                ToolPanel.LIGHT -> LightControls(develop, ::change)
                ToolPanel.CURVE -> CurveControls(develop, ::change)
                ToolPanel.COLOR -> ColorControls(develop, ::change)
                ToolPanel.MIX -> ColorMixerControls(develop, ::change)
                ToolPanel.GRADE -> ColorGradingControls(develop, ::change)
                ToolPanel.OPTICS -> OpticsControls(develop, ::change)
                ToolPanel.EFFECTS -> EffectsControls(develop, ::change)
                ToolPanel.DETAIL -> DetailControls(develop, ::change)
            }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf("JPEG", "PNG").forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f, fontSize = 11.sp) }) }
                }
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
    Text(label, color = if (enabled) Ink else Muted.copy(.35f), fontSize = 23.sp, modifier = Modifier.clickable(enabled = enabled, onClick = action).padding(horizontal = 8.dp))
}

private fun previewFilter(state: DevelopState): ColorFilter {
    val activeMix = state.colorMix.values.filter { it != ColorMix() }
    val mixSaturation = activeMix.map { it.saturation }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
    val mixLuminance = activeMix.map { it.luminance }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
    val saturation = (1f + state.saturation / 100f + state.vibrance / 190f + mixSaturation / 800f).coerceIn(0f, 2.5f)
    val contrast = 1f + state.contrast / 100f + state.dehaze / 400f
    val brightness = state.exposure * .19f + state.shadows / 650f + state.whites / 900f + state.blacks / 1000f + state.curveMidtones / 520f + mixLuminance / 1200f
    val curveContrast = (state.curveHighlights - state.curveShadows) / 700f
    // Approximate the point tone curve in the live preview; export applies the exact LUT.
    val curveRgb = state.curves["RGB"] ?: emptyList()
    var pointBrightness = 0f
    var pointContrast = 0f
    if (!curveRgb.isIdentityCurve()) {
        pointBrightness = evaluateCurve(curveRgb, .5f) - .5f
        pointContrast = ((evaluateCurve(curveRgb, .75f) - evaluateCurve(curveRgb, .25f)) / .5f - 1f) * .8f
    }
    fun channelOffset(name: String): Float {
        val curve = state.curves[name] ?: emptyList()
        return if (curve.isIdentityCurve()) 0f else (evaluateCurve(curve, .5f) - .5f) * 255f
    }
    val adjustedContrast = contrast + curveContrast + pointContrast
    val translate = (1f - adjustedContrast) * .5f + brightness + pointBrightness
    val warmth = state.temperature / 100f * 25f
    val tint = state.tint / 100f * 15f
    val grade = previewGrade(state)
    val redOffset = channelOffset("Red"); val greenOffset = channelOffset("Green"); val blueOffset = channelOffset("Blue")
    return ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        adjustedContrast * saturation, 0f, 0f, 0f, translate * 255f + warmth + tint + grade[0] + redOffset,
        0f, adjustedContrast * saturation, 0f, 0f, translate * 255f - tint + grade[1] + greenOffset,
        0f, 0f, adjustedContrast * saturation, 0f, translate * 255f - warmth + tint + grade[2] + blueOffset,
        0f, 0f, 0f, 1f, 0f,
    )))
}

private fun previewGrade(state: DevelopState): FloatArray {
    fun hueColor(hue: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        return when (floor(h).toInt()) {
            0 -> floatArrayOf(1f, x, 0f); 1 -> floatArrayOf(x, 1f, 0f); 2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f); 4 -> floatArrayOf(x, 0f, 1f); else -> floatArrayOf(1f, 0f, x)
        }
    }
    val shadow = hueColor(state.shadowHue); val middle = hueColor(state.midtoneHue); val highlight = hueColor(state.highlightHue)
    val shadowStrength = state.shadowGrade / 100f * 18f
    val middleStrength = state.midtoneGrade / 100f * 16f
    val highlightStrength = state.highlightGrade / 100f * 18f
    return FloatArray(3) { channel ->
        (shadow[channel] - .5f) * shadowStrength + (middle[channel] - .5f) * middleStrength + (highlight[channel] - .5f) * highlightStrength
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
            ToolButton("Reset adjustments") { update(DevelopState(rotation = current.rotation, straighten = current.straighten, flipHorizontal = current.flipHorizontal, cropAspect = current.cropAspect, cropZoom = current.cropZoom, cropOffsetX = current.cropOffsetX, cropOffsetY = current.cropOffsetY, geometryVertical = current.geometryVertical, geometryHorizontal = current.geometryHorizontal, geometryAspect = current.geometryAspect)) }
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
    create: (DevelopState) -> Unit, update: (DevelopState) -> Unit,
) {
    var category by remember { mutableStateOf("Recommended") }
    var selected by remember { mutableStateOf<Preset?>(null) }
    var base by remember { mutableStateOf(current) }
    var strength by remember { mutableFloatStateOf(100f) }
    val categories = listOf("Recommended", "Favorites", "Premium", "Film", "Vintage", "B&W", "Yours")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { EditorTitle("PRESETS", selected?.name ?: "Discover a look") }
            Text("+ Save yours", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable { create(current); category = "Yours" }.padding(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            categories.forEach { item -> FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item, fontSize = 10.sp) }) }
        }
        val shown = if (category == "Favorites") presets.filter { it.id in favorites } else presets.filter { it.category == category }
        if (shown.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("No presets here yet", color = Muted, fontSize = 12.sp) }
        else LazyRow(Modifier.weight(1f).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { preset ->
                Column(Modifier.width(110.dp).height(76.dp).clip(RoundedCornerShape(8.dp)).background(if (selected?.id == preset.id) Color(0xFF233747) else Color(0xFF24262A)).border(1.dp, if (selected?.id == preset.id) Accent else Color.White.copy(.08f), RoundedCornerShape(8.dp)).clickable {
                    base = current; selected = preset; strength = 100f; update(blendPreset(base, preset.state, 1f))
                }.padding(9.dp)) {
                    Row(Modifier.fillMaxWidth()) { Text(preset.name, color = Ink, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f)); Text(if (preset.id in favorites) "★" else "☆", color = if (preset.id in favorites) WarmAccent else Muted, modifier = Modifier.clickable { favorite(preset.id) }) }
                    Spacer(Modifier.weight(1f)); Text(if (preset.builtIn) preset.category else "CUSTOM", color = Muted, fontSize = 8.sp)
                }
            }
        }
        if (selected != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Amount", color = Muted, fontSize = 11.sp, modifier = Modifier.width(65.dp))
            Slider(strength, { strength = it; update(blendPreset(base, selected!!.state, it / 100f)) }, valueRange = 0f..200f, modifier = Modifier.weight(1f), colors = sliderColors())
            Text(strength.toInt().toString(), color = Ink, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
        }
    }
}

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
        val mask = LocalMask(id = "mask-${System.currentTimeMillis()}", type = type, size = if (type == "Brush") .08f else .42f)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            ToolButton("+ Brush") { add("Brush") }
            ToolButton("+ Linear") { add("Linear") }
            ToolButton("+ Radial") { add("Radial") }
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
                    label = { Text("${index + 1} ${mask.type}", fontSize = 9.sp) },
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
            if (active.type == "Brush" && active.points.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("Clear stroke", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { replace(active.copy(points = emptyList())) }.padding(8.dp))
                }
            }
            EditorSlider("Exposure", active.exposure, -4f..4f, { replace(active.copy(exposure = it)) }, 2)
            EditorSlider("Contrast", active.contrast, -100f..100f, { replace(active.copy(contrast = it)) })
            EditorSlider("Temp", active.temperature, -100f..100f, { replace(active.copy(temperature = it)) })
            EditorSlider("Saturation", active.saturation, -100f..100f, { replace(active.copy(saturation = it)) })
            EditorSlider("Feather", active.feather * 100f, 2f..100f, { replace(active.copy(feather = it / 100f)) })
            EditorSlider(when (active.type) { "Linear" -> "Width"; "Brush" -> "Brush size"; else -> "Size" }, active.size * 100f, if (active.type == "Brush") 1f..30f else 5f..100f, { replace(active.copy(size = it / 100f)) })
            if (active.type == "Brush") EditorSlider("Flow", active.flow * 100f, 5f..100f, { replace(active.copy(flow = it / 100f)) })
            if (active.type == "Linear") EditorSlider("Angle", active.angle, -180f..180f, { replace(active.copy(angle = it)) })
            Text(if (active.type == "Brush") "Drag on the photo to paint. Stroke points are simplified and rendered through a cached alpha map at export." else "The overlay is a fast preview; full-resolution feathering is rendered during export.", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
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
private fun MaskAdjustmentPreview(state: DevelopState, imageAspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val imageRect = fittedImageRect(size, imageAspect)
        state.masks.filter { it.enabled }.forEach { mask ->
            val exposureAlpha = (abs(mask.exposure) / 4f * .34f).coerceIn(0f, .34f)
            val temperatureAlpha = (abs(mask.temperature) / 100f * .12f).coerceIn(0f, .12f)
            if (exposureAlpha + temperatureAlpha <= .002f) return@forEach
            val base = when {
                abs(mask.temperature) > abs(mask.exposure) * 18f -> if (mask.temperature > 0f) Color(0xFFFFB36B) else Color(0xFF70A8FF)
                mask.exposure >= 0f -> Color.White
                else -> Color.Black
            }.copy(alpha = (exposureAlpha + temperatureAlpha).coerceAtMost(.38f))
            if (mask.type == "Brush") {
                if (mask.points.isEmpty()) return@forEach
                val stroke = Path()
                mask.points.forEachIndexed { index, point ->
                    val x = imageRect.left + point.x * imageRect.width
                    val y = imageRect.top + point.y * imageRect.height
                    if (index == 0) stroke.moveTo(x, y) else stroke.lineTo(x, y)
                }
                val radius = mask.size * minOf(imageRect.width, imageRect.height)
                clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
                    if (mask.points.size == 1) drawCircle(base.copy(alpha = base.alpha * mask.flow), radius, androidx.compose.ui.geometry.Offset(imageRect.left + mask.points[0].x * imageRect.width, imageRect.top + mask.points[0].y * imageRect.height))
                    else drawPath(stroke, base.copy(alpha = base.alpha * mask.flow), style = Stroke(radius * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                return@forEach
            }
            val center = androidx.compose.ui.geometry.Offset(imageRect.left + mask.centerX * imageRect.width, imageRect.top + mask.centerY * imageRect.height)
            val radiusX = mask.size * imageRect.width
            val radiusY = mask.size * imageRect.height
            val angle = Math.toRadians(mask.angle.toDouble())
            val normal = androidx.compose.ui.geometry.Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val brush = if (mask.type == "Linear") {
                val transition = (mask.size * mask.feather * maxOf(imageRect.width, imageRect.height)).coerceAtLeast(2f)
                Brush.linearGradient(
                    colors = if (mask.invert) listOf(base, Color.Transparent) else listOf(Color.Transparent, base),
                    start = center - normal * transition / 2f,
                    end = center + normal * transition / 2f,
                )
            } else {
                Brush.radialGradient(
                    colors = if (mask.invert) listOf(Color.Transparent, base) else listOf(base, Color.Transparent),
                    center = center,
                    radius = maxOf(radiusX, radiusY).coerceAtLeast(2f),
                )
            }
            if (mask.type == "Radial" && !mask.invert) {
                drawOval(brush, topLeft = center - androidx.compose.ui.geometry.Offset(radiusX, radiusY), size = androidx.compose.ui.geometry.Size(radiusX * 2f, radiusY * 2f))
            } else {
                drawRect(brush, topLeft = imageRect.topLeft, size = imageRect.size)
            }
        }
    }
}

@Composable
private fun MaskGuide(state: DevelopState, activeId: String?, imageAspect: Float, update: (DevelopState) -> Unit) {
    val active = state.masks.firstOrNull { it.id == activeId } ?: state.masks.lastOrNull() ?: return
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    Canvas(Modifier.fillMaxSize().pointerInput(active.id, active.type) {
        var strokePoints = active.points
        fun appendPoint(position: androidx.compose.ui.geometry.Offset) {
            val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
            if (!imageRect.contains(position) || strokePoints.size >= 128) return
            val point = MaskPoint(
                ((position.x - imageRect.left) / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                ((position.y - imageRect.top) / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
            )
            val last = strokePoints.lastOrNull()
            if (last != null && (last.x - point.x) * (last.x - point.x) + (last.y - point.y) * (last.y - point.y) < .000064f) return
            strokePoints = strokePoints + point
            val current = latestState
            latestUpdate(current.copy(masks = current.masks.map { mask -> if (mask.id == active.id) mask.copy(points = strokePoints) else mask }))
        }
        detectDragGestures(
            onDragStart = { position -> if (active.type == "Brush") appendPoint(position) },
        ) { event, drag ->
            event.consume()
            if (active.type == "Brush") {
                appendPoint(event.position)
            } else {
                val current = latestState
                val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
                latestUpdate(current.copy(masks = current.masks.map { mask ->
                    if (mask.id != active.id) mask else mask.copy(
                        centerX = (mask.centerX + drag.x / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        centerY = (mask.centerY + drag.y / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    )
                }))
            }
        }
    }) {
        val imageRect = fittedImageRect(size, imageAspect)
        val center = androidx.compose.ui.geometry.Offset(imageRect.left + active.centerX * imageRect.width, imageRect.top + active.centerY * imageRect.height)
        val guide = if (active.enabled) Accent else Muted
        val angle = Math.toRadians(active.angle.toDouble())
        val normal = androidx.compose.ui.geometry.Offset(cos(angle).toFloat(), sin(angle).toFloat())
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            if (active.type == "Brush") {
                if (active.points.isNotEmpty()) {
                    val stroke = Path()
                    active.points.forEachIndexed { index, point ->
                        val x = imageRect.left + point.x * imageRect.width
                        val y = imageRect.top + point.y * imageRect.height
                        if (index == 0) stroke.moveTo(x, y) else stroke.lineTo(x, y)
                    }
                    val radius = active.size * minOf(imageRect.width, imageRect.height)
                    val outer = Stroke(radius * 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                    val inner = Stroke(radius * 2f * (1f - active.feather).coerceAtLeast(.08f), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                    if (active.points.size == 1) {
                        val point = androidx.compose.ui.geometry.Offset(imageRect.left + active.points[0].x * imageRect.width, imageRect.top + active.points[0].y * imageRect.height)
                        drawCircle(guide.copy(.22f), radius, point); drawCircle(guide.copy(.7f), radius * (1f - active.feather).coerceAtLeast(.08f), point)
                    } else {
                        drawPath(stroke, guide.copy(.22f), style = outer); drawPath(stroke, guide.copy(.7f), style = inner)
                    }
                }
            } else if (active.type == "Linear") {
                val direction = androidx.compose.ui.geometry.Offset(-normal.y, normal.x)
                val extent = maxOf(imageRect.width, imageRect.height)
                val halfTransition = active.size * active.feather * extent / 2f
                drawLine(guide, center - direction * extent, center + direction * extent, 2.dp.toPx())
                drawLine(guide.copy(.55f), center - direction * extent - normal * halfTransition, center + direction * extent - normal * halfTransition, 1.dp.toPx())
                drawLine(guide.copy(.55f), center - direction * extent + normal * halfTransition, center + direction * extent + normal * halfTransition, 1.dp.toPx())
            } else {
                val radiusX = active.size * imageRect.width
                val radiusY = active.size * imageRect.height
                drawOval(guide.copy(.12f), topLeft = center - androidx.compose.ui.geometry.Offset(radiusX, radiusY), size = androidx.compose.ui.geometry.Size(radiusX * 2f, radiusY * 2f))
                drawOval(guide, topLeft = center - androidx.compose.ui.geometry.Offset(radiusX, radiusY), size = androidx.compose.ui.geometry.Size(radiusX * 2f, radiusY * 2f), style = Stroke(2.dp.toPx()))
                val innerX = radiusX * (1f - active.feather); val innerY = radiusY * (1f - active.feather)
                drawOval(guide.copy(.5f), topLeft = center - androidx.compose.ui.geometry.Offset(innerX, innerY), size = androidx.compose.ui.geometry.Size(innerX * 2f, innerY * 2f), style = Stroke(1.dp.toPx()))
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
    fun addSpot() {
        if (state.healingSpots.size >= 24) return
        val spot = HealingSpot(id = "spot-${System.currentTimeMillis()}")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            ToolButton("+ Healing spot") { addSpot() }
            if (active != null) ToolButton("Auto source") {
                replace(active.copy(sourceX = (active.targetX - .2f).coerceIn(0f, 1f), sourceY = active.targetY))
            }
        }
        if (state.healingSpots.isEmpty()) {
            Text("Add a spot, then drag the blue target and amber source handles on the photo.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            state.healingSpots.forEachIndexed { index, spot ->
                FilterChip(selected = spot.id == active?.id, onClick = { select(spot.id) }, label = { Text("Spot ${index + 1}", fontSize = 9.sp) })
            }
        }
        if (active != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("Delete spot", color = Color(0xFFFF7777), fontSize = 10.sp, modifier = Modifier.clickable {
                    val remaining = state.healingSpots.filterNot { it.id == active.id }
                    update(state.copy(healingSpots = remaining)); select(remaining.lastOrNull()?.id)
                }.padding(8.dp))
            }
            EditorSlider("Size", active.radius * 100f, 1f..30f, { replace(active.copy(radius = it / 100f)) })
            EditorSlider("Feather", active.feather * 100f, 2f..100f, { replace(active.copy(feather = it / 100f)) })
            EditorSlider("Opacity", active.opacity * 100f, 5f..100f, { replace(active.copy(opacity = it / 100f)) })
            Text("This is a deterministic local clone/heal tool. Generative and object-aware removal require a separately licensed on-device model or service.", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun HealingPreview(bitmap: Bitmap, state: DevelopState, imageAspect: Float) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val filter = previewFilter(state)
    Canvas(Modifier.fillMaxSize()) {
        val imageRect = fittedImageRect(size, imageAspect)
        val screenShortSide = minOf(imageRect.width, imageRect.height)
        val bitmapShortSide = minOf(bitmap.width, bitmap.height).toFloat()
        state.healingSpots.forEach { spot ->
            val screenRadius = (spot.radius * screenShortSide).coerceAtLeast(1f)
            val sourceRadius = (spot.radius * bitmapShortSide).toInt().coerceAtLeast(1)
            val sourceCenterX = (spot.sourceX * (bitmap.width - 1)).toInt()
            val sourceCenterY = (spot.sourceY * (bitmap.height - 1)).toInt()
            val sourceLeft = (sourceCenterX - sourceRadius).coerceIn(0, bitmap.width - 1)
            val sourceTop = (sourceCenterY - sourceRadius).coerceIn(0, bitmap.height - 1)
            val sourceWidth = (sourceRadius * 2).coerceAtMost(bitmap.width - sourceLeft).coerceAtLeast(1)
            val sourceHeight = (sourceRadius * 2).coerceAtMost(bitmap.height - sourceTop).coerceAtLeast(1)
            val target = androidx.compose.ui.geometry.Offset(imageRect.left + spot.targetX * imageRect.width, imageRect.top + spot.targetY * imageRect.height)
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(target.x - screenRadius, target.y - screenRadius, target.x + screenRadius, target.y + screenRadius)) }
            clipPath(clip) {
                drawImage(
                    image = image,
                    srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, sourceTop),
                    srcSize = androidx.compose.ui.unit.IntSize(sourceWidth, sourceHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset((target.x - screenRadius).toInt(), (target.y - screenRadius).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((screenRadius * 2f).toInt().coerceAtLeast(1), (screenRadius * 2f).toInt().coerceAtLeast(1)),
                    alpha = spot.opacity,
                    colorFilter = filter,
                )
            }
        }
    }
}

@Composable
private fun HealingGuide(state: DevelopState, activeId: String?, imageAspect: Float, update: (DevelopState) -> Unit) {
    val active = state.healingSpots.firstOrNull { it.id == activeId } ?: state.healingSpots.lastOrNull() ?: return
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    Canvas(Modifier.fillMaxSize().pointerInput(active.id) {
        var movingSource = false
        detectDragGestures(
            onDragStart = { position ->
                val imageRect = fittedImageRect(androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()), imageAspect)
                val source = androidx.compose.ui.geometry.Offset(imageRect.left + active.sourceX * imageRect.width, imageRect.top + active.sourceY * imageRect.height)
                val target = androidx.compose.ui.geometry.Offset(imageRect.left + active.targetX * imageRect.width, imageRect.top + active.targetY * imageRect.height)
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

@Composable
private fun CropControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("CROP & ROTATE", "Drag the preview to reframe")
        Text("Aspect ratio", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 7.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Original", "1:1", "4:5", "3:2", "16:9").forEach { ratio -> FilterChip(selected = state.cropAspect == ratio, onClick = { update(state.copy(cropAspect = ratio)) }, label = { Text(ratio, fontSize = 11.sp) }) }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolButton("Rotate left") { update(state.copy(rotation = (state.rotation - 90).floorRotation())) }
            ToolButton("Rotate right") { update(state.copy(rotation = (state.rotation + 90).floorRotation())) }
            ToolButton(if (state.flipHorizontal) "Unflip" else "Flip") { update(state.copy(flipHorizontal = !state.flipHorizontal)) }
            ToolButton("Center") { update(state.copy(cropOffsetX = 0f, cropOffsetY = 0f)) }
        }
        EditorSlider("Straighten", state.straighten, -45f..45f, { update(state.copy(straighten = it)) }, 1)
        EditorSlider("Zoom", state.cropZoom, 1f..3f, { update(state.copy(cropZoom = it)) }, 2)
        Text("Rotation ${state.rotation}° · Pan ${(state.cropOffsetX * 100).toInt()}, ${(state.cropOffsetY * 100).toInt()}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CropGuide(state: DevelopState, update: (DevelopState) -> Unit) {
    val latestState by rememberUpdatedState(state)
    val latestUpdate by rememberUpdatedState(update)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { event, drag ->
                event.consume()
                val current = latestState
                latestUpdate(current.copy(
                    cropOffsetX = (current.cropOffsetX - drag.x / size.width * 2f / current.cropZoom).coerceIn(-1f, 1f),
                    cropOffsetY = (current.cropOffsetY - drag.y / size.height * 2f / current.cropZoom).coerceIn(-1f, 1f),
                ))
            }
        },
    ) {
        val ratio = when (state.cropAspect) { "1:1" -> 1f; "4:5" -> .8f; "3:2" -> 1.5f; "16:9" -> 16f / 9f; else -> size.width / size.height }
        val maxWidth = size.width * .84f; val maxHeight = size.height * .84f
        val frameWidth: Float; val frameHeight: Float
        if (maxWidth / maxHeight > ratio) { frameHeight = maxHeight; frameWidth = frameHeight * ratio }
        else { frameWidth = maxWidth; frameHeight = frameWidth / ratio }
        val left = (size.width - frameWidth) / 2f; val top = (size.height - frameHeight) / 2f
        val guide = Color.White.copy(alpha = .64f)
        drawRect(guide, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(frameWidth, frameHeight), style = Stroke(1.4.dp.toPx()))
        for (step in 1..2) {
            val x = left + frameWidth * step / 3f; val y = top + frameHeight * step / 3f
            drawLine(guide.copy(alpha = .42f), androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Offset(x, top + frameHeight), 1.dp.toPx())
            drawLine(guide.copy(alpha = .42f), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(left + frameWidth, y), 1.dp.toPx())
        }
    }
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
private fun ToolButton(label: String, action: () -> Unit) { OutlinedButton(onClick = action, shape = RoundedCornerShape(7.dp)) { Text(label, fontSize = 10.sp) } }

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
            listOf("Adobe Color", "Adobe Vivid", "Adobe Portrait", "Adobe Neutral", "Monochrome").forEach { profile -> FilterChip(selected = state.profile == profile, onClick = { update(state.copy(profile = profile, saturation = if (profile == "Monochrome") -100f else state.saturation)) }, label = { Text(profile, fontSize = 9.sp) }) }
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
        EditorSlider("Temperature", state.temperature, -100f..100f, { update(state.copy(temperature = it)) })
        EditorSlider("Tint", state.tint, -100f..100f, { update(state.copy(tint = it)) })
        EditorSlider("Vibrance", state.vibrance, -100f..100f, { update(state.copy(vibrance = it)) })
        EditorSlider("Saturation", state.saturation, -100f..100f, { update(state.copy(saturation = it)) })
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
        EditorSlider("Hue", adjustment.hue, -100f..100f, { apply(adjustment.copy(hue = it)) })
        EditorSlider("Saturation", adjustment.saturation, -100f..100f, { apply(adjustment.copy(saturation = it)) })
        EditorSlider("Luminance", adjustment.luminance, -100f..100f, { apply(adjustment.copy(luminance = it)) })
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("COLOR GRADING", "Independent shadow, midtone, and highlight tint")
        GradeSection("Shadows", state.shadowHue, state.shadowGrade, { update(state.copy(shadowHue = it)) }, { update(state.copy(shadowGrade = it)) })
        GradeSection("Midtones", state.midtoneHue, state.midtoneGrade, { update(state.copy(midtoneHue = it)) }, { update(state.copy(midtoneGrade = it)) })
        GradeSection("Highlights", state.highlightHue, state.highlightGrade, { update(state.copy(highlightHue = it)) }, { update(state.copy(highlightGrade = it)) })
        EditorSlider("Balance", state.gradingBalance, -100f..100f, { update(state.copy(gradingBalance = it)) })
    }
}

@Composable
private fun OpticsControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("OPTICS", "Manual lens shading and color-fringe correction")
        EditorSlider("Lens shading", state.opticsVignette, -100f..100f, { update(state.copy(opticsVignette = it)) })
        EditorSlider("Defringe", state.defringe, 0f..100f, { update(state.copy(defringe = it)) })
        HorizontalDivider(color = Color.White.copy(.08f), modifier = Modifier.padding(vertical = 12.dp))
        Text("Lens shading compensates dark or bright corners. Defringe suppresses purple and green edge contamination without desaturating the full image.", color = Muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Reset optics", color = Accent, fontSize = 10.sp, modifier = Modifier.clickable { update(state.copy(opticsVignette = 0f, defringe = 0f)) }.padding(8.dp))
        }
    }
}

@Composable
private fun GradeSection(label: String, hue: Float, amount: Float, updateHue: (Float) -> Unit, updateAmount: (Float) -> Unit) {
    Text(label, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
    EditorSlider("Hue", hue, 0f..360f, updateHue)
    EditorSlider("Saturation", amount, 0f..100f, updateAmount)
}

@Composable
private fun EffectsControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("EFFECTS", "Presence and atmosphere")
        EditorSlider("Texture", state.texture, -100f..100f, { update(state.copy(texture = it)) })
        EditorSlider("Clarity", state.clarity, -100f..100f, { update(state.copy(clarity = it)) })
        EditorSlider("Dehaze", state.dehaze, -100f..100f, { update(state.copy(dehaze = it)) })
        EditorSlider("Vignette", state.vignette, -100f..100f, { update(state.copy(vignette = it)) })
        EditorSlider("Grain", state.grain, 0f..100f, { update(state.copy(grain = it)) })
    }
}

@Composable
private fun DetailControls(state: DevelopState, update: (DevelopState) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditorTitle("DETAIL", "Sharpening and noise reduction")
        SubHeader("Sharpening")
        EditorSlider("Amount", state.sharpen, 0f..100f, { update(state.copy(sharpen = it)) })
        EditorSlider("Radius", state.sharpenRadius, 0.5f..3f, { update(state.copy(sharpenRadius = it)) }, 1)
        EditorSlider("Detail", state.sharpenDetail, 0f..100f, { update(state.copy(sharpenDetail = it)) })
        EditorSlider("Masking", state.sharpenMasking, 0f..100f, { update(state.copy(sharpenMasking = it)) })
        SubHeader("Noise Reduction")
        EditorSlider("Luminance", state.noiseReduction, 0f..100f, { update(state.copy(noiseReduction = it)) })
        EditorSlider("Detail", state.nrDetail, 0f..100f, { update(state.copy(nrDetail = it)) })
        EditorSlider("Contrast", state.nrContrast, 0f..100f, { update(state.copy(nrContrast = it)) })
        EditorSlider("Color noise", state.colorNoise, 0f..100f, { update(state.copy(colorNoise = it)) })
        Spacer(Modifier.height(10.dp)); Text("Masking limits sharpening to edges; NR Detail protects fine texture. Export renders the full stack.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SubHeader(label: String) {
    Text(label, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun EditorTitle(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Text(title, color = Ink, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun EditorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit, decimals: Int = 0) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(45.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(82.dp))
        Slider(value, change, valueRange = range, modifier = Modifier.weight(1f), colors = sliderColors())
        Text(if (decimals == 2) String.format("%.2f", value) else value.toInt().toString(), color = Ink, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(activeTrackColor = Accent, thumbColor = Ink, inactiveTrackColor = Color.White.copy(.16f))
