package com.lightroom.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable
fun ProfileOptions(photo: PhotoItem, metadata: PhotoMetadata, update: (PhotoMetadata) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<LensProfile>>(emptyList()) }; var query by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { profiles = withContext(Dispatchers.IO) { LensProfiles.load(context) } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val name = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size()+n <= 8*1024*1024) { "Profile exceeds 8 MB" }; output.write(buffer,0,n) }
                        output.toByteArray()
                    } ?: kotlin.error("Couldn't read profile")
                    require(bytes.size in 128..8*1024*1024) { "ICC must be between 128 bytes and 8 MB" }
                    fun tag(offset: Int) = String(bytes,offset,4,Charsets.US_ASCII)
                    require(tag(36)=="acsp" && tag(16)=="RGB " && tag(12)=="scnr") { "Choose an RGB input/camera ICC profile. DCP and display profiles are not supported." }
                    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    val directory=File(context.filesDir,"camera-profiles").apply { mkdirs() }
                    val target=File(directory,"$hash.icc")
                    target.outputStream().use { it.write(bytes) }
                    require(NativeRaw.validateProfile(target.path)) { "ICC profile cannot produce RGB output" }
                    "$hash.icc"
                }
                update(metadata.copy(cameraProfile=name)); error=null
            }.onFailure { error=it.message }
        }
    }
    HorizontalDivider()
    Text("Camera profile", style = MaterialTheme.typography.titleSmall)
    Text(if(!photo.isRaw()) "Source image color (camera conversion applies to RAW only)" else if(metadata.cameraProfile.isBlank()) "LibRaw camera matrix + as-shot white balance" else "Imported ICC input profile")
    if(photo.isRaw()) {
        TextButton(onClick={ importer.launch(arrayOf("*/*")) }) { Text("Import camera ICC") }
        if(metadata.cameraProfile.isNotBlank()) TextButton(onClick={ update(metadata.copy(cameraProfile="")) }) { Text("Use camera matrix") }
    }
    Text("Lens profiles · ${profiles.size} calibrated rectilinear lenses", style = MaterialTheme.typography.titleSmall)
    Text(if(metadata.lensProfile.isEmpty()) "Correction off" else profiles.firstOrNull { it.id==metadata.lensProfile }?.name ?: "Selected profile unavailable")
    TextButton(onClick={
        val match=LensProfiles.match(profiles,metadata)
        if(match==null) error="No unambiguous lens match. Search and select the exact lens below."
        else if(metadata.focalLength==null) error="Focal length is missing. Enter it below."
        else { update(metadata.copy(lensProfile=match.id)); error=null }
    }) { Text("Match lens from EXIF") }
    if(metadata.lensProfile.isNotEmpty()) TextButton(onClick={ update(metadata.copy(lensProfile="")) }) { Text("Disable correction") }
    OutlinedTextField(query,{query=it},label={Text("Search exact lens model")})
    if(query.length>=3) profiles.filter{it.name.contains(query,true)}.take(12).forEach { lens -> TextButton(onClick={update(metadata.copy(lensProfile=lens.id))}) {Text(lens.name)} }
    Text("Calibrated distortion/TCA and aperture-dependent shading. Fisheye and ACM models are excluded. Aperture and focal interpolation use the available calibration points. Saving reloads the photo.")
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
}
