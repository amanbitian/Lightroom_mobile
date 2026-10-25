# LumaRoom — Architecture

LumaRoom is a single-module Android app (Kotlin + Jetpack Compose + Material 3) implementing a local photo library and a non-destructive editor modelled on Lightroom Mobile. There is no backend or account. The only network use is the optional download of the LaMa model. See [sdd.md](sdd.md) for detailed component design and [diff.md](diff.md) for the change log. Current version: 0.20.0.

## 1. Technology

- **Language:** Kotlin (JVM target 17).
- **UI:** Jetpack Compose, Material 3 (custom dark scheme), single Activity (`MainActivity`).
- **Async:** coroutines. Decode, render, AI and database writes run off the main thread; writes go through a single-threaded dispatcher so they land in order.
- **Persistence:** SQLite (catalog and edit recipes) plus SharedPreferences (custom presets, favorites, albums, export options, clipboard).
- **Imaging:** CPU pixel processing on `android.graphics.Bitmap`. The colour maths lives in a pure-Kotlin `ColorEngine` that is unit-tested on the JVM. Geometry uses Android `Matrix`/`Canvas`.
- **AI:** LiteRT (`com.google.ai.edge.litert`, formerly TensorFlow Lite) on CPU with XNNPACK.
- **Build:** Gradle 9.3 wrapper, AGP 8.13, `minSdk 26`, `targetSdk 35`. Release builds use R8 code and resource shrinking and ship arm64 only.

## 2. Module map

```
app/src/main/java/com/lightroom/mobile/
├── MainActivity.kt      UI: library, editor (canvas, tool groups, all panels), dialogs, navigation
├── EditorModels.kt      DevelopState & friends, JSON, preset blending, quick looks, smooth tone curves,
│                        crop helpers (effectiveCrop, straightenScale)
├── ColorEngine.kt       Per-pixel colour pipeline (pure Kotlin): linear WB/exposure/calibration, local tone,
│                        curves, OKLab colour (HSL, grading, B&W), masks, dehaze, vignette, grain
├── ImageExporter.kt     Render orchestration: transform (orient/straighten/crop/warp) + FrameMap,
│                        healing, adjustPixels (ColorEngine + detail pass), background, lens blur,
│                        preview rendering and caches, export and MediaStore writing
├── RemovalFill.kt       Generative Remove regions (painted strokes -> hole/alpha) and push-pull fill
├── Inpainter.kt         LaMa: download/verify, inference, seam colour matching, idle release
├── SubjectSegmenter.kt  People segmentation (person/hair/skin/clothes) via LiteRT
├── SkySegmenter.kt      Heuristic sky mask
├── LiteRtModels.kt      Model loading (memory-mapped) and interpreter options
├── RawDecoder.kt        Decoding: BitmapFactory -> ImageDecoder -> embedded RAW JPEG; EXIF orientation; exact max size
├── TiffPngWriter.kt     16-bit TIFF / PNG encoders
├── PresetImporter.kt    Lightroom .xmp / .lrtemplate -> DevelopState + defined fields
├── AssetPresets.kt      Loads the bundled preset packs
├── LibraryStore.kt      SQLite catalog + preferences
├── BitmapRepository.kt  Sampled bitmap LRU cache
├── ImageAnalysis.kt     Auto enhance, auto straighten
└── Histogram.kt         Histogram overlay (computed from the rendered preview)
app/src/main/res/
├── mipmap-anydpi-v26/    Adaptive launcher icon (ic_launcher, ic_launcher_round)
├── mipmap-*/            Icon foreground per density (mdpi–xxxhdpi)
├── drawable-nodpi/      lumaroom_logo.webp (full logo, shown by BrandLogo)
└── values/              strings (app_name "LumaRoom"), ic_launcher_background #F8F7F1
app/src/main/assets/
├── presets/<Pack>/*.xmp 423 bundled Lightroom-format presets
└── models/              selfie_multiclass_256x256.tflite (+ README)
app/src/test/            JVM tests (colour engine, presets, importer, fills)
app/src/androidTest/     On-device feature tests (FeatureTest)
```

## 3. Screens

`LumaRoomApp` has three tabs (Library, Edit, Settings). The `BrandLogo` composable shows the LumaRoom logo on the empty Library and at the top of Settings. Opening a photo shows the full-screen `EditorScreen`:

- **Top bar:** back, name, undo/redo, before/after toggle, histogram toggle, export.
- **Canvas:** the rendered preview inside a transform layer (orientation, straighten, crop), clipped to the visible frame. Mask, heal and blur guides live inside the same layer so touches map to photo pixels. In Crop the whole straightened photo is shown with a draggable crop box.
- **Tools:** groups in a bottom bar (Looks: Presets, Quick · Adjust: Light, Color, Effects, Detail, Curve, Mix, Grading, Optics, Calibration · Local: Masks, Retouch, Lens Blur · Crop: Crop, Geometry · Info), with sub-tool pills above the panel.

## 4. Editing model

An edit is an immutable `DevelopState`, never applied to the original. Panels receive it and raise changes through `update(DevelopState)`. The editor keeps a debounced undo/redo history and autosaves to SQLite, but only once the photo is actually edited, and never an unconfirmed preset preview.

Presets are previewed until Apply. The photo records the applied preset (`presetId`) and the look it was applied to (`presetBase`), so tapping it again removes exactly the fields it set, and choosing another preset replaces it.

## 5. Render pipeline

The **preview and the export share the same code**:

```
preview: editor bitmap (1600 px) -> healing (cached; LaMa on full renders) -> adjustPixels -> background -> lens blur
         (draft: 720 px, no detail pass, classic fill; full render ~220 ms after input settles)
export:  decode (exact max size, EXIF-oriented) -> transform -> FrameMap.remap(state) -> healing
         -> adjustPixels -> background -> lens blur -> JPEG/PNG | 16-bit TIFF/PNG -> MediaStore
```

- **transform:** 90° turns then flip (so Flip mirrors what you see), then straighten (auto-zoomed, no empty corners), then the crop rectangle, lens distortion (native mesh) and perspective.
  - It returns a `FrameMap` (matrix → distortion → matrix). `FrameMap.remap` moves masks, brush and heal strokes and the blur focus into the output frame, because they are stored relative to the original photo.
  - Its inverse (`sourceGrid`) lets AI masks segmented on the original photo be looked up from the output frame, so preview and export select the same pixels.
- **adjustPixels:** builds the brush/AI mask rasters and a blurred luminance base layer, runs `ColorEngine.process` on 64-row tiles, then the detail pass (sharpening, noise reduction, texture).
- In the preview the orientation/crop transform is a Compose `graphicsLayer`. Geometry is drawn through the exporter's `perspectiveMatrix`, and the post-crop vignette is an overlay. Everything else is the same code.
- Library thumbnails of edited photos use `renderEdited`, the whole pipeline at thumbnail size, cached until the next save.
- **Memory:** each stage consumes its input (about one full-size copy alive at a time). Blurs run on a downscaled copy and blend row by row. The 16-bit buffer is off-heap. Segmentation input is capped at 1024 px. The LaMa interpreter is closed 20 s after use.

## 6. Colour science

`ColorEngine` follows Adobe Camera Raw's order. All steps are tested in `ColorEngineTest`.

1. **Linear light:** calibration primaries (white-preserving 3×3), white balance as channel gains, exposure as 2^EV.
2. **Tone:** shadows/highlights from a local base layer, then whites, blacks and an S-curve contrast on luminance (applied as a ratio, so hue is kept), clarity from the base-layer detail, and a highlight shoulder only when values exceed white.
3. **Curves:** parametric, then smooth point curves per channel.
4. **Colour in OKLab/OKLCh:** saturation; vibrance (favours muted colours, protects skin); the HSL mixer with bands at the OKLCh hues of Lightroom's colours, smooth blending, and grey protection; or the B&W mix; then colour grading wheels and shadow tint; then gamut mapping by chroma reduction.
5. Local masks, dehaze, then the frame effects (vignette, lens shading, grain).

## 7. On-device AI

- **People:** `SubjectSegmenter` runs one inference for all parts: Person, Hair, Skin and Clothes masks, and background effects. The accessories class only counts next to a person.
- **Generative Remove:** strokes become a `RemovalRegion`. LaMa regenerates a 512 px window around it, seams are matched to the surroundings, and the result is blended with a feather. If LaMa isn't installed, a push-pull fill is used instead.
- **Fallback contract:** every AI path returns null/false on failure. The caller then skips the effect or uses its classic method, and export always completes.

## 8. Persistence

- **SQLite** (`lumaroom_catalog.db`, WAL, foreign keys):
  - `photos(uri, name, rating, flagged, rejected, imported_at)`
  - `edits(uri -> photos ON DELETE CASCADE, recipe JSON, updated_at)`
  - Photo rows are updated in place: REPLACE would cascade-delete edits.
- **SharedPreferences:** custom presets (with defined fields and RAW-only white balance), favorites, albums, export options, clipboard.
- **Files:** photo-picker copies in `files/imports` when a grant can't be persisted; the LaMa model in `files/models`.

## 9. Key decisions

- **One pipeline for preview and export:** the screen matches the saved file. Speed comes from draft renders and caches, not approximations.
- **Pure-Kotlin colour engine:** every control's behaviour is pinned by JVM tests and checked again on the device.
- **OKLab for colour work:** perceptual uniformity keeps hue and luminance edits local and predictable, close to how Lightroom's HSL behaves.
- **CPU processing:** deterministic and portable; draft renders take ~10–25 ms and full previews ~90 ms on the emulator (release build).
- **Local-first AI with graceful fallbacks:** the large model is opt-in and verified; nothing breaks without it.

## 10. Known limitations

- Rendering is close to Lightroom's, not identical: Adobe's process version, camera profiles and Look tables are proprietary. RAW files use the embedded JPEG preview (no demosaic).
- LaMa needs ~600–800 MB while running and works at 512 px, so very large removals are soft.
- The Sky mask is heuristic.
- The old signing passwords remain in the git history of the first two commits. Rotate the key (or treat it as public) before publishing.
- With strong Geometry, mask and heal guides are drawn warped but touches use the unwarped frame.
