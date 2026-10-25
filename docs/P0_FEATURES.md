# RAW, profiles, batch workflow, history and metadata

This implementation adds the first five requested Lightroom-comparison areas. It is not a claim of Lightroom rendering parity or support for every camera and profile format.

## How to use

### Develop a RAW image

Import a RAW file through **Files**, then open it. Recognized RAW extensions use LibRaw sensor unpacking and demosaicing rather than an embedded JPEG. The native build includes Bayer/AHD and X-Trans processing, as-shot white balance, camera matrices, highlight reconstruction, zlib and JPEG-compressed RAW support. Unrecognized or failed RAW files show an error rather than silently switching to a JPEG preview.

The editor displays an 8-bit preview. Export retains LibRaw's 16-bit samples through calibrated lens correction, crop/rotation/perspective mapping and the color engine. TIFF/PNG16 retain that precision; retouch/detail/blur remain bitmap-based passes whose deltas are added to the 16-bit result. This is not a floating-point, scene-linear, HDR RAW editing engine.

Native decoding is serialized to bound concurrent memory use. Inputs are limited to 512 MB, 100 MP and LibRaw's 384 MB raw allocation budget. These limits do not guarantee every large image fits a low-memory phone. Very large exports can require a smaller output dimension.

### Camera and lens profiles

Open a photo, choose **Info → Metadata & profiles**, and scroll down.

- RAW camera conversion defaults to LibRaw's embedded camera-matrix database and as-shot white balance.
- **Import camera ICC** accepts RGB input/scanner profiles up to 8 MB. Little CMS validates that they can produce RGB output. Display/output ICC profiles and DCP files are rejected explicitly.
- **Match lens from EXIF** selects only an unambiguous exact normalized lens-name match. Otherwise search for and select the exact lens manually.
- Enter focal length when EXIF lacks it. Aperture enables aperture-dependent shading when calibration exists.
- Save reloads the photo and invalidates thumbnails. Profile settings are catalog overrides; originals are not changed.

Lens data is bundled from Lensfun. Implemented rectilinear models are poly3/poly5/PTLens distortion, linear/poly3 lateral chromatic aberration and PA vignetting. Focal coefficients are interpolated linearly; vignetting uses the nearest available aperture calibration. Missing camera crop factors use the selected lens's calibration crop factor. Fisheye projection conversion, ACM models, distance-dependent vignetting interpolation and DCP import are not implemented. A profile must be suitable for the camera and lens; an already corrected JPEG can be overcorrected.

### Batch edits and background export

In **Library → Select photos**, tap photos or **Select visible**, then **Edit N selected**.

- Copy a source photo's settings in **Info → Copy settings**, then paste them onto the selected photos.
- Alternatively search and select a preset. Only that preset's declared groups are applied.
- Crop and perspective remain individual. Local masks, retouch and blur are preserved by default when pasting; explicitly enable **Include local tools** to copy them.
- Each target receives a named **Before batch edit** version. The operation uses one SQLite transaction, so an error rolls back the entire edit batch.
- **Export selected in background** snapshots each photo's recipe, metadata, profile choices and export options into a SQLite queue. A foreground service processes one image at a time, with progress and cancel notification actions.
- **Resume exports**, **Retry failed exports**, and **Cancel remaining exports** are available in selection mode. One bad file does not stop the remaining queue. Cancellation lets the current image finish and cancels queued images.

Android can stop foreground work after its platform time limit. Queue records remain available for Resume. An interruption after a file is published but before its job is marked saved can produce a duplicate on retry (at-least-once processing). A force-stopped application does not restart itself; reopen and resume it. Background export does not upload photos.

### History and named versions

Open **Info → History & versions**.

- Settled edits are recorded persistently, with the changed control names and time.
- Enter a name and choose **Save current look** to create a version.
- Restore history entries or named versions; rename/delete named versions.
- Versions include edit recipes and metadata/profile selections. Restoring an entry reloads its profile configuration too.
- Existing catalogs migrate without deleting photos or recipes. History starts with the state present at this update; earlier edit steps cannot be reconstructed.

The existing 300 ms autosave debounce coalesces rapid slider movement. History records saved states, not every individual pointer movement. Unsaved changes can still be lost if Android kills the process before autosave; the previous saved recipe and versions remain.

### Metadata and export privacy

Use **Info → Metadata & profiles** to edit caption, copyright, keywords, capture time, GPS coordinates, focal length and aperture. Camera/lens, ISO and shutter metadata are read from EXIF. Keywords are trimmed and deduplicated; coordinates and dates are validated. Library search covers catalog metadata as well as filenames.

Export has separate **Include metadata** and **Include location** controls. Location is off by default. JPEG receives EXIF plus XMP; PNG/PNG16 receive standard XMP iTXt; TIFF16 receives TIFF tag 700 XMP. These contain the supported catalog fields, not a byte-for-byte copy of every manufacturer EXIF/MakerNote tag. Turning metadata off omits it from newly encoded files. Imported originals are never modified.

## Build

Install Android SDK 36, NDK **28.2.13676358**, CMake **3.22.1**, and JDK 17+. Android Gradle can install the native packages when SDK licenses have been accepted. Debug builds support ARM64 and x86_64; release builds are ARM64-only. Source dependencies are vendored so native compilation does not fetch source code.

```powershell
.\gradlew.bat assembleDebug assembleRelease
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest lintDebug
```

Third-party versions and notices are in [THIRD_PARTY.md](THIRD_PARTY.md), the vendored source trees and **Settings → RAW engine & lens data licenses**.

## Verification limits

Automated fixtures cover sensor-only Bayer and X-Trans DNGs, native ICC validation/application, actual 16-bit RAW export precision, supported lens-model math, catalog migration, versions, batch recovery, metadata/GPS export controls and foreground export with a failed file while the activity is backgrounded. Physical ARM64 devices, a broad set of real camera RAWs, low-memory devices, long-running service timeouts and visual color accuracy against measured camera charts still need acceptance testing.
