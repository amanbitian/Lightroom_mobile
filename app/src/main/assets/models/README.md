# On-device AI models

All models run through **LiteRT** (`com.google.ai.edge.litert`) on the CPU with XNNPACK. Photos never leave the device. Reviewed for LumaRoom 0.20.0 (2026-10-24); see [diff.md](../../../../../diff.md) for when each model was added.

| Model | Feature | How it ships | Size | Licence |
|---|---|---|---|---|
| `selfie_multiclass_256x256.tflite` (MediaPipe) | Person / Hair / Skin / Clothes masks, background effects | Bundled here | 16.4 MB | Apache-2.0 |
| `lama_dilated.tflite` (Qualcomm AI Hub LaMa-Dilated, 512×512) | Generative Remove | Downloaded on first use into app storage, SHA-256 verified (`Inpainter.kt`) | 174 MB (170 MB zip) | Apache-2.0 |
| — | Sky mask | Heuristic in `SkySegmenter.kt` (no model yet) | — | — |

## Notes
- `.tflite` files are stored uncompressed in the APK (`androidResources.noCompress`), so they can be memory-mapped.
- **People model:**
  - Expects RGB scaled to −1..1 (mean = std = 127.5), stretched to 256×256.
  - Outputs 6 class logits: background, hair, body skin, face skin, clothes, other.
  - "Other" (accessories) counts toward Person only next to person pixels, because on its own it fires on flat areas.
- **LaMa:**
  - Takes an RGB image in 0..1 plus a mask (1 = fill) and returns the filled image. Known pixels come back unchanged.
  - The app runs it on a window about 2.2× the painted area, matches the colours at the fill's edge to the surroundings, and blends the result back with a feather.
  - Its interpreter is closed 20 s after last use, because it needs ~600–800 MB while running.

## Replacing or adding a model
1. Put the file here (bundled) or download it into `files/models` (large models).
2. Load it with `LiteRtModels.mapAsset` / `mapFile`, then call `LiteRtModels.interpreter`.
3. Keep the fallback contract: return null/false on any failure so the caller can use its classic method.
