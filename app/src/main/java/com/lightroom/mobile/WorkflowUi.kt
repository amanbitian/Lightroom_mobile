package com.lightroom.mobile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object WorkflowCatalog {
    private var instance: LibraryStore? = null
    @Synchronized fun get(context: android.content.Context): LibraryStore = instance ?: LibraryStore(context.applicationContext).also { instance = it }
}

@Composable
fun ThirdPartyLicenses() {
    val context=LocalContext.current;var show by remember{mutableStateOf(false)}
    TextButton(onClick={show=true}){Text("RAW engine & lens data licenses")}
    if(show) {
        val licenses by produceState(""){value=withContext(Dispatchers.IO){context.assets.list("licenses").orEmpty().sorted().joinToString("\n\n"){name->"$name\n"+context.assets.open("licenses/$name").bufferedReader().use{it.readText()}}}}
        AlertDialog(onDismissRequest={show=false},title={Text("Third-party notices")},text={Text(licenses,modifier=Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={show=false}){Text("Close")}})
    }
}

@Composable
fun WorkflowTools(photo: PhotoItem, state: DevelopState, restore: (DevelopState) -> Unit) {
    var metadata by remember { mutableStateOf(false) }; var versions by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { metadata = true }) { Text("Metadata & profiles") }
        OutlinedButton(onClick = { versions = true }) { Text("History & versions") }
    }
    if (metadata) MetadataDialog(photo) { metadata = false }
    if (versions) VersionsDialog(photo, state, { restore(it); versions = false }) { versions = false }
}

@Composable
private fun VersionsDialog(photo: PhotoItem, state: DevelopState, restore: (DevelopState) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val store = remember { WorkflowCatalog.get(context) }
    var versions by remember { mutableStateOf<List<EditVersion>>(emptyList()) }; var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    suspend fun reload() { versions = withContext(Dispatchers.IO) { store.history(photo.uri) } }
    LaunchedEffect(photo.uri) { kotlinx.coroutines.delay(400); reload() }
    AlertDialog(onDismissRequest = dismiss, title = { Text("History & named versions") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(label, { label = it }, label = { Text("Version name") }, singleLine = true)
            TextButton(enabled = label.isNotBlank() && !busy, onClick = {
                busy = true; scope.launch { runCatching { withContext(Dispatchers.IO) { store.snapshot(photo.uri, label, state) }; label = ""; reload() }.onFailure { error = it.message }; busy = false }
            }) { Text("Save current look") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { restore(DevelopState()) }) { Text("Restore original") }
            if (versions.isEmpty()) Text("History appears as edits are saved. Existing edits begin recording from this update.")
            versions.forEach { v ->
                HorizontalDivider()
                Text((if (v.named) "★ " else "") + v.label)
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.createdAt)), style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(enabled=!busy,onClick = { busy=true;scope.launch {
                        runCatching { withContext(Dispatchers.IO) { v.metadata?.let{store.saveMetadata(photo.uri,it)} };BitmapRepository.clear();restore(v.state) }.onFailure{error=it.message};busy=false
                    } }) { Text("Restore") }
                    if (v.named) {
                        TextButton(enabled = label.isNotBlank(), onClick = { scope.launch { withContext(Dispatchers.IO) { store.renameVersion(v.id, label) }; reload() } }) { Text("Rename") }
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { store.deleteVersion(v.id) }; reload() } }) { Text("Delete") }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable
private fun MetadataDialog(photo: PhotoItem, dismiss: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val store = remember { WorkflowCatalog.get(context) }
    var value by remember { mutableStateOf<PhotoMetadata?>(null) }; var latitude by remember { mutableStateOf("") }; var longitude by remember { mutableStateOf("") }
    var focalText by remember { mutableStateOf("") }; var apertureText by remember { mutableStateOf("") }; var keywordText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var saving by remember { mutableStateOf(false) }
    LaunchedEffect(photo.uri) {
        val loaded = withContext(Dispatchers.IO) { store.loadMetadata(photo.uri) ?: PhotoMetadataIO.read(context, Uri.parse(photo.uri)) }
        value = loaded; latitude = loaded.latitude?.toString().orEmpty(); longitude = loaded.longitude?.toString().orEmpty()
        focalText=loaded.focalLength?.toString().orEmpty();apertureText=loaded.aperture?.toString().orEmpty();keywordText=loaded.keywords.joinToString(", ")
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Photo metadata") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val m = value
            if (m == null) CircularProgressIndicator() else {
                Text("${m.cameraMake} ${m.cameraModel}\n${m.lens} · ${m.focalLength ?: "?"} mm · f/${m.aperture ?: "?"}")
                OutlinedTextField(m.caption, { value = m.copy(caption = it) }, label = { Text("Caption") })
                OutlinedTextField(m.copyright, { value = m.copy(copyright = it) }, label = { Text("Copyright") })
                OutlinedTextField(keywordText, { keywordText = it }, label = { Text("Keywords, separated by commas") })
                OutlinedTextField(m.capturedAt, { value = m.copy(capturedAt = it) }, label = { Text("Capture time: YYYY:MM:DD HH:MM:SS") })
                OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude (optional)") })
                OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude (optional)") })
                OutlinedTextField(focalText, { focalText = it }, label = { Text("Focal length in mm") })
                OutlinedTextField(apertureText, { apertureText = it }, label = { Text("Aperture (f-number)") })
                Text("Metadata is stored in the catalog. Export includes it only when enabled; location sharing is off by default.")
                ProfileOptions(photo, m.copy(focalLength=focalText.toDoubleOrNull(),aperture=apertureText.toDoubleOrNull())) { value = it }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = value != null && !saving, onClick = {
        try {
            val lat = if (latitude.isBlank()) null else latitude.toDoubleOrNull() ?: error("Invalid latitude")
            val lon = if (longitude.isBlank()) null else longitude.toDoubleOrNull() ?: error("Invalid longitude")
            val focal=if(focalText.isBlank())null else focalText.toDoubleOrNull() ?: kotlin.error("Invalid focal length")
            val aperture=if(apertureText.isBlank())null else apertureText.toDoubleOrNull() ?: kotlin.error("Invalid aperture")
            val updated = requireNotNull(value).copy(latitude = lat, longitude = lon,focalLength=focal,aperture=aperture,keywords=keywordText.split(',')).validated()
            require(updated.lensProfile.isBlank() || focal!=null) { "Focal length is required for lens correction" }
            saving = true
            scope.launch { runCatching { withContext(Dispatchers.IO) { store.saveMetadata(photo.uri, updated) }; BitmapRepository.clear() }.onSuccess { dismiss() }.onFailure { error = it.message }; saving = false }
        } catch (e: Exception) { error = e.message ?: "Check the entered values" }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun BatchTools(photos: List<PhotoItem>, presets: List<Preset>, done: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val store = remember { WorkflowCatalog.get(context) }
    var show by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var includeLocal by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf<Preset?>(null) }; var query by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf(false) }
    val progress by BatchExportService.progress.collectAsState()
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(progress) { failures = withContext(Dispatchers.IO) { store.exportFailures() } }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = photos.isNotEmpty(), onClick = { show = true }) { Text("Edit ${photos.size} selected") }
            TextButton(onClick = { context.startForegroundService(Intent(context, BatchExportService::class.java)) }) { Text("Resume exports") }
        }
        Text(progress, style = MaterialTheme.typography.labelSmall)
        if (failures.isNotEmpty()) {
            Text(failures.first(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick={scope.launch{withContext(Dispatchers.IO){store.retryFailedExports()};context.startForegroundService(Intent(context,BatchExportService::class.java))}}){Text("Retry failed exports")}
        }
        TextButton(onClick={context.startForegroundService(Intent(context,BatchExportService::class.java).setAction("cancel"))}){Text("Cancel remaining exports")}
    }
    if (show) AlertDialog(onDismissRequest = { if (!busy) show = false }, title = { Text("Batch · ${photos.size} photos") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("Uses copied settings or one preset. Each photo gets a 'Before batch edit' version. Crop and geometry are preserved.")
            Row { Checkbox(includeLocal, { includeLocal = it }); Text("Include local tools when pasting") }
            OutlinedTextField(query, { query = it }, label = { Text("Search presets") })
            TextButton(onClick = { preset = null }) { Text(if (preset == null) "✓ Copied settings" else "Use copied settings") }
            presets.filter { query.isNotBlank() && it.name.contains(query, true) }.take(25).forEach { p -> TextButton(onClick = { preset = p }) { Text((if (preset?.id == p.id) "✓ " else "") + p.name) } }
            preset?.let { Text("Selected preset: ${it.name}") }
            message?.let { Text(it) }
            TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
                runCatching { withContext(Dispatchers.IO) { val source = if (preset == null) store.pastedSettings() ?: error("Copy settings from an edited photo first") else DevelopState(); store.applyBatch(photos.map { it.uri }, source, includeLocal, preset) } }
                    .onSuccess { done(); show = false; Toast.makeText(context, "Updated ${photos.size} photos", Toast.LENGTH_LONG).show() }.onFailure { message = it.message }
                busy = false
            } }) { Text("Apply edits") }
            TextButton(enabled = !busy, onClick = { export = true }) { Text("Export selected in background") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { show = false }) { Text("Close") } })
    if (export) ExportDialog(store.loadExportOptions(), { export = false }) { options ->
        export = false; busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.enqueueExports(photos, options) }; context.startForegroundService(Intent(context, BatchExportService::class.java)) }
                .onSuccess { show = false }.onFailure { message = it.message }
            busy = false
        }
    }
}
