# LumaRoom — Software Design Document (SDD)

Companion to [architecture.md](architecture.md); changes are logged in [diff.md](diff.md). Describes version 0.20.0 (versionCode 20). Specifies data models, algorithms, component contracts, persistence and tests.

## 1. Purpose & scope

An offline Android photo library and non-destructive editor modelled on Lightroom Mobile: import and organisation, a full develop pipeline, presets (built-in, 423 bundled Lightroom-format presets, import, user-created), and on-device AI (people masks, background effects, Generative Remove).

Out of scope: cloud sync, accounts, RAW demosaicing, Adobe camera profiles / Look tables, and generative expand.

## 2. Domain model (`EditorModels.kt`)

### 2.1 `PhotoItem`
`uri` (content/file URI), `name`, `rating 0..5`, `flagged`, `rejected`, `importedAt`. `isRaw()` checks the extension against `RawDecoder.rawExtensions`.

### 2.2 `DevelopState`
An immutable recipe. Every field defaults to a no-op, and JSON loading clamps values and fills in missing keys.

- **Light:** exposure (−4..4 EV), contrast, highlights, shadows, whites, blacks (±100).
- **Curves:** curveShadows/Midtones/Highlights (±100); `curves` RGB/Red/Green/Blue → points (0..1).
- **Color:** temperature, tint, vibrance, saturation (±100); `profile` (Adobe Color/Vivid/Portrait/Neutral/Monochrome).
- **Calibration:** red/green/blue Hue and Saturation, shadowTint (±100).
- **HSL:** `colorMix` channel → `ColorMix(hue, saturation, luminance)`. `bwMix` channel → gray response (Monochrome only).
- **Color grading:** shadow/midtone/highlight hue (0..360, wheel degrees: 0 red, 60 yellow, 240 blue) and grade (0..100), gradingBalance.
- **Effects:**
  - texture, clarity, dehaze;
  - vignette + vignetteMidpoint, vignetteFeather (0..100), vignetteRoundness (±100);
  - grain + grainSize, grainRoughness.
- **Detail:** sharpen, sharpenRadius (0.5..3), sharpenDetail, sharpenMasking, noiseReduction, nrDetail, nrContrast, colorNoise.
  - Recipes carry `v` = 3. In older recipes, sharpen defaulted to 40, and colorNoise 25 (which did nothing then) reads as 0.
  - New edits start at 0 for both.
- **Geometry:**
  - rotation (90° steps), straighten (±45), flipHorizontal;
  - `cropAspect` (Free/Original/1:1/4:5/3:2/16:9/9:16) and `cropLeft/Top/Right/Bottom`, normalised to the rotated and straightened frame. Legacy cropZoom/offset values are migrated on load.
  - geometryVertical/Horizontal/Aspect.
- **Optics:** opticsVignette, defringe, lensDistortion.
- **Lens blur:** lensBlur, focus x/y, radius.
- **AI background:** `background` (None/Blur/B&W/Darken/Pop).
- **Local:** `masks` (≤8 `LocalMask`), `healingSpots` (≤24 `HealingSpot`).
- **Preset:** `presetId`, `presetBase` (the look the applied preset was applied on).

### 2.3 `LocalMask`
- `type`: Brush, Linear, Radial, or one of `aiMaskTypes` (Subject = Person, Hair, Skin, Clothes, Sky).
- Brush masks keep `strokes` (separate polylines; legacy `points` is one stroke) with up to `MAX_BRUSH_POINTS` = 600 points.
- Radial and Linear sizes are in units of the frame's short side, measured in real pixels, so a radial mask is round and a gradient's angle is true at any aspect.
- Settings: enabled, invert, centre/size/feather/angle, brush `points` and `flow`.
- Adjustments: exposure, contrast, highlights, shadows, temperature, tint, saturation.
- Masks a preset brings with it use ids prefixed `preset-`, so swapping presets replaces them.

### 2.4 `HealingSpot`
target, source, radius, feather, opacity, `contentAware`.

Generative Remove spots set `brush = true` and hold `strokes: List<List<MaskPoint>>` (normalised; at most `MAX_REMOVE_POINTS` = 600 points). Each stroke is `radius` (a fraction of the short side) wide on each side. Older removal spots without `brush` are circles at the target.

### 2.5 `Preset`
- `id`, `name`, `category`, `state`, `builtIn`.
- `fields`: the field names it defines; null means a complete look.
- `rawWhiteBalance`: absolute temp/tint, applied only to RAW files.
- `lookFor(isRaw)` returns the look plus its fields.
- `presetGroups` maps Lightroom-style setting groups to fields, for "Save preset".
- `quickLooks` are presets whose fields are the settings they change.

### 2.6 Helpers
- `evaluateCurve`: monotone cubic (Fritsch–Carlson) through the points; 2 points means linear, empty means identity.
- `effectiveCrop(frameAspect)`: fits a locked ratio around the rectangle's centre.
- `straightenScale(deg, aspect) = cos θ + sin θ · max(a, 1/a)`: the zoom that hides empty corners.

## 3. Preset blending (`blendPreset(base, target, t, fields)`)

- Scalar fields: `base + (target − base)·t` for defined fields only. `t` is 0..2.
- Hues blend along the shorter arc.
- `colorMix` blends per channel.
- Curves and `bwMix` are carried over when defined and t > 0.
- Profile:
  - with explicit fields, it changes only if "profile" is defined;
  - complete looks change it only to a non-default profile;
  - never at t = 0.
- Masks: base masks minus `preset-*`, plus the target's `preset-*` masks.
- A defined curve/B&W mix replaces the current one, even if empty, so removal really restores "none".
- Total masks are capped at 8.
- Removing an applied preset is `blendPreset(current, presetBase, 1, presetFields)`. `presetFields` is stored on the photo, so removal works even if the preset is deleted.

## 4. Lightroom import (`PresetImporter`)

Detects XMP vs `.lrtemplate` and records every field it sets. It maps:

- **Tone:** Exposure2012, Contrast2012, Highlights2012, Shadows2012, Whites2012, Blacks2012.
- **White balance:** Incremental temp/tint (all photos). `WhiteBalance=Custom` with Kelvin `Temperature`/`Tint` becomes `rawWhiteBalance`, using (K − 5500)/50.
- **Presence:** Vibrance, Saturation, Clarity2012, Dehaze, Texture.
- **Detail:** Sharpness / Radius / Detail / EdgeMasking; luminance and colour NR.
- **Effects:** PostCropVignette Amount/Midpoint/Feather/Roundness; VignetteAmount → lens shading; Grain Amount/Size/Frequency.
- **Calibration:** Red/Green/Blue Hue & Saturation, ShadowTint.
- **Profile:** ConvertToGrayscale or a Look name → profile.
- **Mixers:** GrayMixer* → `bwMix`; HSL (any key means the preset owns the mixer).
- **Color grading:** ColorGrade* with a SplitToning fallback.
- **Curves:** ParametricShadows/Darks/Lights/Highlights with splits are folded into a 65-point RGB curve as `point(parametric(x))`, with per-channel PV2012 curves alongside.
- **Local filters:**
  - GradientBasedCorrections → Linear masks (Zero/Full points);
  - CircularGradientBasedCorrections → Radial masks (bounds, feather, Flipped = inside);
  - Local exposure/contrast(+clarity)/saturation/temperature/tint/highlights/shadows, scaled by CorrectionAmount.

`parametricCurve` is a smooth bump per region (amplitude 0.16 at ±100), pinned at 0 and 1.

## 5. Colour engine (`ColorEngine`)

Construction precomputes:
- calibration matrix;
- WB gains `(1 + .3t + .06u, 1 − .2u, 1 − .3t + .06u)`, normalised to luma 1, times 2^EV;
- local tone LUT (highlights/shadows) and global tone LUT (whites, blacks, contrast, conditional shoulder), 0..1.5;
- 1024-entry curve LUTs;
- grading OKLab vectors.

Per pixel:
1. sRGB → linear (LUT) → calibration → gains → `encode` (sRGB with headroom above 1).
2. Tone:
   - L = Rec.709 luma; B = base-layer luma (blurred 1/48-scale linear luminance × exposure gain, encoded);
   - L' = global(L + 0.6·Δlocal(B) + 0.4·Δlocal(L) + clarity·0.6·(L − B)·(0.35 + 0.65·mid(L)));
   - RGB scaled by (L' + .015)/(L + .015).
3. Curves: per-channel LUTs.
4. Colour (only when needed), in OKLab:
   - chroma C, hue h; colourfulness = smoothstep(.012, .06, C).
   - Saturation × (1 + s).
   - Vibrance × (1 + v·(1 − smoothstep(0, .22, C))·(1 − skin)), where skin is a bell at 55° (60%).
   - HSL: bands at OKLCh hues 29.2, 53, 109.8, 142.5, 194.8, 264.1, 293.9, 328.4. Weights are smoothstep between neighbours.
     - hue += Σw·H/100·span·0.85·colourfulness, where span is the distance to the next/previous band;
     - C ×= 1 + Σw·S/100;
     - L += Σw·Lum/100·0.2·colourfulness·smoothstep(.02, .14, C).
   - Monochrome: L += B&W mix·0.3·colourfulness, then C = 0.
   - Grading: (a, b) += wheel vectors × tonal weights around pivot 0.5 + balance·0.25; shadow tint is an a-shift on shadows.
   - Gamut: binary-search chroma reduction to fit sRGB.
5. Defringe, local masks (geometric weights in-engine; brush/AI weights via `MaskSampler`), dehaze.
6. Frame effects (export and thumbnails only): vignette (elliptical ↔ circular by roundness, midpoint/feather window, darken or lighten), lens shading, grain (value noise with cell ∝ size, roughness mixes in per-pixel noise).

The detail pass in `ImageExporter` handles:
- sharpening (amount × detail × radius, edge masking);
- noise reduction (edge-preserving);
- texture;
- colour noise reduction: chroma blended toward a blurred copy's chroma, luminance kept.

Grain is applied in previews too; vignette and lens shading only in the final frame.

## 6. Geometry & frames

- **Export `transform`:**
  1. Orient: `postRotate(90k)`, then `postScale(flip)` (flip mirrors the displayed view), shifted to the origin. Pre-v3 recipes with flip and an odd quarter turn have the turn reversed on load.
  2. Straighten: draw through T(−c)·S(k)·R(θ)·T(c) on a same-size canvas.
  3. Crop: subset.
  4. Distortion: mesh, treated as identity for mapping.
  5. Perspective: poly-to-poly.

  `FrameMap` = before-matrix · radial distortion · after-matrix. It maps normalised points forward, scales lengths by `mapRadius`, and has an exact inverse, sampled as a 33×33 `SourceGrid` for AI-mask lookups.
- **Preview `FrameView`:** the photo is drawn Fit in the box, then the layer applies scale = s·k (×−1 for flip), rotation = 90k + θ (θ − 90k when flipped, since R(θ)·F·R(90k) = R(θ − 90k)·F), and translation = −s·(crop centre offset). Geometry is drawn on the frame through `perspectiveMatrix`. s fits the crop (or 0.84 × the whole frame in Crop mode), and the frame box clips. Pointer input goes through the layer, so guides use photo-normalised coordinates.
- **Crop box:** corner drags resize anchored at the opposite corner, keeping the normalised ratio `ratio / frameAspect` when locked; inside drags move the box; minimum side 0.08; it opts out of system edge gestures.

## 7. Retouch

- **Heal/Clone:** copy from source to target in a feathered circle.
- **`RemovalRegion.of`:** rasterises strokes, or the legacy circle, into a bounding box. `hole` = strokes widened by `ring` = r·(.1 + .25·feather) + 2. `alpha` = strokes widened by ring/2, blurred by ring/2, ×2, clamped, × hole.
- **`PushPullFill`:** a pyramid. Downsample by summing premultiplied colour and weights; upsample bilinearly into unknown pixels. Composited with `alpha`.
- **`Inpainter.inpaint`:**
  - Window = 2.2 × region (non-square if needed), stretched to 512×512.
  - Model mask = any hole pixel in the footprint; output is composited back with `alpha`.
  - `matchSeams` measures fill-vs-outside differences on the hole edge, spreads them over the hole with push-pull, and adds the correction. LaMa returns known pixels unchanged, so the edge is where colour drift shows.
  - Download: streams Qualcomm's zip, unzips the `.tflite` on the fly, checks SHA-256 `0d453a9a…84d3`, then renames into `files/models`. Status is a `StateFlow`; the interpreter is released 20 s after use.
- **Preview:** drafts use push-pull. Full renders use LaMa, cached per (source, spots).

## 8. AI models

- **People** (`selfie_multiclass_256x256.tflite`):
  - Input: RGB 256² stretched, (x − 127.5)/127.5.
  - Output: 6 logits → softmax (0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other).
  - Person = 1..4 plus class 5 gated by smoothstep(.15, .5, person).
  - Cached per source bitmap.
- **LaMa-Dilated:** image (1×512×512×3, 0..1) and mask (1×512×512×1, 1 = fill), bound by tensor name → painted image.

## 9. Input/output

- **`RawDecoder`:**
  - Decode order: BitmapFactory, then ImageDecoder, then the largest embedded JPEG in a memory-mapped RAW (found by walking JPEG segments so nested thumbnails don't truncate it).
  - EXIF orientation is applied, and the output is scaled to exactly `maxSide`.
- **Export:** JPEG (quality) / PNG, or 16-bit TIFF / PNG (off-heap `ShortBuffer`) through MediaStore `Pictures/LumaRoom` (app external files on API < 29).

## 10. Persistence

- **SQLite (`CatalogDatabase`, v2):** `photos` and `edits` as in architecture §8. `savePhotos` updates first and inserts if missing. `editedUris()` feeds the grid's edited markers.
- **Preferences:** customPresets JSON (`fields`, `rawWhiteBalance`), favorites, albums, export options, clipboard.
- **`LibraryStore(context, databaseName, prefsName)`:** the names can be overridden for tests.

## 11. Performance (emulator, release build)

| Operation | Time |
|---|---|
| Draft preview (720 px) | 7–24 ms |
| Full preview (1600 px) | ~90 ms |
| 24 MP export at full size with blur, distortion and segmentation, 192 MB heap | completes (memory-bounded stages) |
| LaMa inference, per spot | a few seconds |

## 12. Tests

- **JVM (`./gradlew testDebugUnitTest`):** `ColorEngineTest` (14), `PresetImportTest` (6), `PresetBlendTest` (6), `PushPullFillTest` (2). 28 tests in total.
- **On device (`./gradlew connectedDebugAndroidTest`):** `FeatureTest` (28) covers geometry, mask mapping, heal, Generative Remove (classic + LaMa when installed), brush/linear masks, lens blur, sharpening/NR, vignette/grain, clarity, segmentation, EXIF, decode size, 16-bit writers, full export, catalog integrity, bundled presets, and preview/export parity.

## 13. Branding, versioning and release

- **Name:** `app_name` is "LumaRoom" (launcher label and Settings).
- **Launcher icon:** adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`). The foreground is the logo mark from `mipmap-*/ic_launcher_foreground.png` (108–432 px, mark kept within the 66 dp safe zone). The background is the colour `ic_launcher_background` (#F8F7F1). The manifest sets `android:icon` and `android:roundIcon`.
- **In-app logo:** `drawable-nodpi/lumaroom_logo.webp` (720×589, with the wordmark), drawn by `BrandLogo` on a rounded cream card on the empty Library and in Settings.
- **Store/doc icon:** `docs/icon-512.png`.
- **Version:** `versionCode 20`, `versionName "0.20.0"`.
- **Release:** `./gradlew assembleRelease` gives an R8-shrunk arm64 APK (~22 MB), signed from `local.properties` (`lumaroom.*`) or `LUMAROOM_*` environment variables; unsigned when neither is set.
