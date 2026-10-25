# LumaRoom manual QA photo pack

These six generated images are intentionally varied so the editor can be tested consistently. Keep an untouched copy and export to a different filename when comparing preview and output.

| File | Primary tests | Useful checks |
|---|---|---|
| `01-portrait-person-masks.png` | Person/face, hair, skin and clothes masks; background effects; Lens Blur | Check curly-hair edges, face focus, skin-tone stability, hand detail, mask inversion and subject/background separation. |
| `02-landscape-sky-dynamic-range.png` | Sky mask, highlights/shadows, dehaze, color grading, crop | Check the mountain/sky boundary, cloud highlight recovery, forest shadows, red canoe HSL isolation, reflections and vignette parity. |
| `03-low-light-noise-detail.png` | Noise reduction, color noise, sharpening, white balance, grain, defringe | Check lamp halos, dark-detail preservation, colored edge noise, mixed-light neutrality and preview/export equality. |
| `04-architecture-geometry.png` | Vertical/horizontal geometry, aspect, distortion, rotate, crop | Check that window lines become straight, edge details remain intact, no empty borders appear, and export matches preview. |
| `05-still-life-remove-heal.png` | Remove, Heal, Clone, brush/radial masks, Lens Blur | Remove the yellow note and individual beans; clone texture on the wall/table; check spoon and mug edges plus overlapping shadows. |
| `06-market-color-hsl.png` | HSL/color mix, saturation/vibrance, curves, masks and skin tones | Adjust each color channel independently; verify both people remain natural; test bright canopy and deep stall shadows. |

## Short regression flow

1. Import all six images at once and confirm the library count increases by six.
2. Edit each image, leave the editor, reopen it, and confirm the recipe persists.
3. Undo back to the original and confirm the Edited badge disappears.
4. Export JPEG and PNG at full size and compare them with the editor preview.
5. Rotate/crop at least one portrait and one landscape image before exporting.
6. Remove one image from the library and confirm it disappears from every album without deleting external originals.

These are synthetic PNG test assets generated for this project; they contain no real customer photos or private metadata.
