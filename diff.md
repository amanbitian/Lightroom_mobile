# LumaRoom — Change log (diff.md)

| | |
|---|---|
| **Date of changes** | 2026-10-24 |
| **Baseline** | `96f621d` "feature added" (2026-10-23), on top of `e15280a` "first commit" (2026-10-23) |
| **Branch** | `dev` (not committed yet; all changes are in the working tree) |
| **Version** | 0.20.0 (versionCode 20) |
| **Size of the change** | 12 tracked files changed (+2,572 / −918 lines), plus about 40 new files and folders (sources, tests, assets, icons, docs, Gradle wrapper) |

This file lists everything changed in the repository during the 2026-10-24 work session, in the order it was done. For how the app works now, see [README.md](README.md), [architecture.md](architecture.md) and [sdd.md](sdd.md).

---

## 1. Build setup: get the app running on the emulator
- Added the **Gradle 9.3 wrapper** (`gradlew`, `gradlew.bat`, `gradle/`). The repo had none, and Gradle 8.9 fails on JDK 25.
- **AGP 8.5.2 → 8.13.0** (`build.gradle.kts`), so release lint runs on Android Studio's JDK 25. Build-Tools 35 is installed automatically.
- Set `sdk.dir` in `local.properties` to the macOS SDK path (it was a Windows path).
- **Signing secrets moved out of `app/build.gradle.kts`** into the git-ignored `local.properties` (`lumaroom.storeFile/storePassword/keyAlias/keyPassword`) or `LUMAROOM_*` environment variables. Without them the release APK is built unsigned. ⚠️ The old passwords are still in the history of `e15280a` and `96f621d`.
- Release builds: R8 minify plus `shrinkResources`, arm64 only, `app/proguard-rules.pro` (keeps LiteRT).
- `.gitignore`: added build outputs, `local.properties`, APKs and the `.kotlin/` cache.

## 2. First fixes and UI cleanup
- **Presets/filters could not be deselected and carried over automatically:**
  - Presets are now previewed first and only kept on **Apply**.
  - Tapping an applied preset removes it.
  - Choosing another preset replaces the current one instead of stacking on top.
  - Unconfirmed previews are never autosaved.
- **Full UI cleanup of the Library and Editor:**
  - Dark Material 3 scheme and a consistent top bar.
  - Grouped tool bar (Looks · Adjust · Local · Crop · Info) with sub-tool pills.
  - Library filters, sort, grid sizes and search.
  - Tidy panels and sliders with reset on double-tap.

## 3. High-priority gaps
- **Presets no longer wipe settings they don't define.** The importer records each preset's `fields`, and `blendPreset` touches only those.
- **EXIF rotation** is applied on decode, in previews and in exports (`RawDecoder`).
- **Mask and heal placement on export:** masks and spots are stored relative to the original photo. `FrameMap.remap` moves them through rotation, flip, straighten, crop and lens correction, so the export matches the preview.
- **Memory on large photos:**
  - Exact-size decode and sampled caches (`BitmapRepository`).
  - Each render stage consumes its input.
  - Blurs run downscaled.
  - The 16-bit buffer is off-heap.
  - Export failures (such as out of memory) are reported instead of crashing.
- **Database:** photo rows are updated in place. `INSERT OR REPLACE` had been cascade-deleting edits.

## 4. Crop, presets, custom presets, gallery, optimisation
- **The crop box moves with touch:**
  - Draggable box with corner handles and aspect ratios.
  - `systemGestureExclusion`, so edge drags no longer trigger Back.
- **Lightroom preset compatibility** (`PresetImporter`):
  - tone and the parametric curve, white balance (Kelvin only for RAW), presence, HSL, B&W mix;
  - grading / split toning, point curves, camera calibration, detail;
  - post-crop and lens vignette, grain, Look profiles;
  - graduated and radial filters as masks;
  - all 423 bundled presets parse.
- **Custom presets:** a Save Preset dialog with a choice of setting groups, rename and delete on long-press, and `.xmp` / `.lrtemplate` import.
- **Preset and Quick looks select/deselect** behave the same way (preview, Apply, tap again to remove).
- **Gallery access beyond Recent:**
  - Android photo picker (all photos and albums) plus a Files picker for RAW, TIFF and Drive.
  - Persisted grants, with a copy into app storage when a grant can't be kept.
  - Names taken from the EXIF date.
- **Optimisation:**
  - Draft previews at 720 px and full previews at 1600 px.
  - Render caches and debounced history.
  - Single-threaded database writes.
- Wrote a list of missing Lightroom features and a plan for local AI (delivered in chat, then implemented in phases 5–6).

## 5. On-device AI with LiteRT
- Added **LiteRT 1.0.1** (`LiteRtModels.kt`). Models are memory-mapped and stored uncompressed in the APK.
- **People segmentation** (`SubjectSegmenter.kt`): the bundled MediaPipe `selfie_multiclass_256x256.tflite` (16 MB) gives Person, Hair, Skin and Clothes masks plus background effects (blur, B&W, darken, pop).
- **Generative Remove** (`Inpainter.kt`):
  - LaMa-Dilated from Qualcomm AI Hub, a 170 MB optional download, SHA-256 verified.
  - Seam colour matching.
  - The interpreter is released when idle (20 s).
  - Push-pull fallback when the model is missing (`RemovalFill.kt`).

## 6. Generative Remove brush
- Generative Remove now uses a **painted brush mask** (separate strokes, tap to dab) instead of a circle, so it covers any object shape (`RemovalRegion`).

## 7. HSL fix, colour science, Lightroom-like UI, tests, docs
- **HSL fixed:**
  - Hue and saturation now act on their colours.
  - Luminance no longer changes the whole image.
  - The new **`ColorEngine.kt`** (pure Kotlin) uses OKLCh bands at Lightroom's hues with smooth blending and grey protection.
- **Colour science close to Lightroom (Camera Raw order):**
  - linear-light calibration, white balance and exposure in stops;
  - local Shadows/Highlights/Clarity from a blurred base layer;
  - whites/blacks/contrast on luminance, with a highlight shoulder only above white;
  - monotone cubic curves;
  - vibrance with skin protection;
  - grading wheels, B&W mix, gamut mapping;
  - dehaze, vignette, grain.
- **Preview = export:** both use one pipeline (`ImageExporter`). The histogram is computed from the rendered preview.
- **Lightroom-style UI:**
  - colour-gradient sliders;
  - pre-rendered grading wheels;
  - a calibration panel and Optics with Auto;
  - Lens Blur with a draggable focus point;
  - export as JPEG, PNG, 16-bit TIFF or 16-bit PNG.
- **Tests added:**
  - JVM: `ColorEngineTest` (14), `PresetImportTest` (6), `PresetBlendTest` (6), `PushPullFillTest` (2).
  - On device: `FeatureTest`.
- **Docs:** rewrote `README.md` and added `architecture.md`, `sdd.md` and `app/src/main/assets/models/README.md`.

## 8. Bug-fix round (code review plus hands-on testing)
- **Geometry:**
  - Flip after rotation now mirrors what you see, and older edits are migrated (recipe JSON v3).
  - Geometry is previewed through the export's `perspectiveMatrix`.
  - Reset adjustments keeps the crop.
  - Added a crop bounds guard.
- **Masks:**
  - AI masks are segmented on the source photo and looked up through `SourceGrid`, so preview and export select the same pixels.
  - The segmentation cache holds 2 sources.
  - Masks are capped at 8.
  - Linear masks start as a top-down gradient.
  - Brush strokes no longer join together, thanks to gesture-local stroke state.
- **Retouch:**
  - Heal drag no longer uses stale positions.
  - Brush and Generative Remove sizes are clamped.
  - Lens-blur guide radius fixed.
- **Presets:**
  - A preset curve now removes the previous curve correctly.
  - Applied preset fields are stored on the photo.
  - The curve point cap was raised from 32 to 128.
- **Colour and detail:**
  - Fixed a stale hue on the grading wheel.
  - Fixed NaN in auto white balance.
  - Implemented colour noise reduction.
  - New edits start with sharpening and colour NR at 0, as in Lightroom for JPEGs.
  - Grain now shows in the preview.
- **Export and library:**
  - 16-bit PNG is streamed (no more OOM).
  - Removing a photo goes through the write queue.
  - Library thumbnails show each photo's edit, with a neutral tile while loading.
  - Fixed stale double-tap reset.
  - Edits are disabled while a Quick preview is showing.
- `FeatureTest` grew to **28** on-device tests.

## 9. Logo, launcher icon and v0.20.0
- **New LumaRoom logo** (house with aperture and plant, cream background) used as the **adaptive launcher icon**:
  - `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`;
  - `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` (108–432 px);
  - `values/ic_launcher_background.xml` (#F8F7F1);
  - `android:icon` and `android:roundIcon` in the manifest.
- **In-app logo:** `drawable-nodpi/lumaroom_logo.webp`, shown by the new `BrandLogo` composable on the empty Library (replacing the old demo preview) and at the top of Settings (replacing the text header).
- `docs/icon-512.png` for stores and the README.
- App name changed from "LumaRoom Mobile" to **"LumaRoom"**.
- **Version 0.20.0** (versionCode 20).
- Updated every md file for the logo and version, and added this `diff.md`.

---

## Files

**New to the repository:**
- `app/src/main/java/com/lightroom/mobile/`:
  - Created: `ColorEngine.kt`, `Inpainter.kt`, `RemovalFill.kt`, `LiteRtModels.kt`, `SubjectSegmenter.kt`.
  - Existed locally but were untracked, and were reworked: `AssetPresets.kt`, `ImageAnalysis.kt`, `PresetImporter.kt`, `RawDecoder.kt`, `SkySegmenter.kt`, `TiffPngWriter.kt`.
- `app/src/test/`: `ColorEngineTest`, `PresetImportTest`, `PresetBlendTest`, `PushPullFillTest`.
- `app/src/androidTest/`: `FeatureTest`.
- `app/src/main/assets/`: 423 presets, `models/selfie_multiclass_256x256.tflite` and its README.
- `app/src/main/res/`: launcher icons, `drawable-nodpi/lumaroom_logo.webp`, `values/ic_launcher_background.xml`.
- Build and docs: `app/proguard-rules.pro`, the Gradle wrapper, `architecture.md`, `sdd.md`, `docs/icon-512.png`, `diff.md`.

**Modified:**
- Code: `MainActivity.kt`, `ImageExporter.kt`, `EditorModels.kt`, `LibraryStore.kt`, `BitmapRepository.kt`, `Histogram.kt`.
- Config and docs: `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts`, `build.gradle.kts`, `.gitignore`, `README.md`.

## Verification
Run on 2026-10-24 on the Android emulator (API 35), after the logo change:
- `./gradlew testDebugUnitTest`: **28/28 passed** (JVM).
- `./gradlew connectedDebugAndroidTest`: **28/28 passed** (on device).
- `./gradlew assembleRelease`: built with release lint and signed with v2 signing. The output is `LumaRoom-0.20.0.apk` (22.1 MB; it is git-ignored and was shared separately).
- Checked by hand: the new icon on the launcher, and the logo on the empty Library and in Settings.

## Known limitations
- Colour is close to Lightroom's, not identical: Adobe's process version and camera profiles are proprietary. RAW files use the embedded JPEG preview (no demosaic).
- LaMa needs about 600–800 MB while running and works at 512 px. The Sky mask is heuristic.
- With strong Geometry, mask and heal guides are drawn warped, while touches use the unwarped frame.
- Tested on the emulator only, not yet on a real phone, real RAW files or real portraits.
- The old signing passwords are in the git history. Rotate the key before publishing.
