# LumaRoom Mobile

An original Android photo library and non-destructive editor inspired by the workflow ideas in [LightCraft](https://github.com/storytold/lightcraft) and the editing model of Lightroom Mobile. It is an independent app and does not include Adobe source code, branding assets, or cloud services.

## Implemented

- Persistent multi-photo import using Android document permissions
- All Photos, Recent, Favorites, and Edited library views
- Indexed filename search and a SQLite/WAL-backed catalog that scales beyond a preference file
- Non-destructive edit recipes saved per photo
- Debounced autosave/history, undo, redo, reset, copy/paste settings, press-and-hold before/after, ratings, and flags
- Preset browser with Recommended, Favorites, Premium-style, Film, Vintage, B&W, and user categories
- Up to eight persistent brush, linear, and radial masks with painting or drag positioning, feather, size, flow, angle, invert, and local exposure/contrast/temperature/saturation
- Favorite presets, save-current-edit as a custom preset, and 0–200 preset strength
- Profile choices and Light controls: exposure, contrast, highlights, shadows, whites, and blacks
- Live RGB/luminance histogram and a saved/exported parametric tone curve
- Color controls: temperature, tint, vibrance, and saturation
- Effects controls: texture, clarity, dehaze, vignette, and grain
- Detail controls: sharpening, luminance noise reduction, and color noise recipe storage
- Center crop ratios, 90-degree rotation, and horizontal flip
- Interactive crop overlay with drag-to-pan, zoom, straighten, aspect ratios, rotation, and matching export geometry
- Three-way shadow/midtone/highlight color grading with independent hue, saturation, and balance
- Eight-channel HSL Color Mixer for red, orange, yellow, green, aqua, blue, purple, and magenta
- Projective Geometry workspace with vertical, horizontal, and aspect correction in preview and export
- Optics workspace with independent lens-shading compensation and purple/green edge defringe processing
- Safe removal from the local library without deleting the original source file
- Full JPEG rendering and export to `Pictures/LumaRoom`
- Sampled thumbnail/editor bitmap cache plus tile-based export processing to bound peak memory use
- Cached brush alpha maps keep freehand mask geometry outside the full-resolution export pixel loop
- Local-only operation with no account or telemetry

The app does not claim feature parity with Adobe's proprietary cloud and AI stack. Generative Remove/Expand, subject/sky AI masks, camera-specific RAW profiles, cloud sync, community feeds, and Adobe account services require separate models, infrastructure, and licensing.

## Build

Open the folder in Android Studio with JDK 17 and let Gradle sync. The project targets Android 8.0+ (`minSdk 26`) and uses Kotlin, Jetpack Compose, and Material 3.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
