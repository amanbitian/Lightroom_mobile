# RAW and lens pipeline dependencies

Vendored sources are retained in `app/src/main/cpp/vendor`. Native build outputs and source-download working directories are ignored by Git.

| Dependency | Pinned revision | Use | License/notice |
|---|---|---|---|
| [LibRaw 0.21.4](https://github.com/LibRaw/LibRaw/tree/0.21.4) | `9646d776c7c61976080a8f2be67928df0750493e` | Sensor decoding, demosaic, camera matrices, highlight reconstruction | CDDL 1.0 option; upstream COPYRIGHT and LICENSE.CDDL retained |
| [Little CMS 2.17](https://github.com/mm2/Little-CMS/tree/lcms2.17) | `5176347635785e53ee5cee92328f76fda766ecc6` | Imported camera/input ICC conversion | MIT; upstream LICENSE retained |
| [libjpeg-turbo 3.1.1](https://github.com/libjpeg-turbo/libjpeg-turbo/tree/3.1.1) | `7723f50f3f66b9da74376e6d8badb6162464212c` | JPEG-compressed RAW variants | Upstream LICENSE.md and referenced notices retained |
| [Lensfun database](https://github.com/lensfun/lensfun/tree/a36cf47998e325b2a44db2e18fa291130d864ea7/data/db) | `a36cf47998e325b2a44db2e18fa291130d864ea7` | Camera crop factors and lens calibration coefficients | CC BY-SA 3.0, Lensfun contributors; original XML files and license bundled |
| [ML Kit Face Detection 16.1.7](https://developers.google.com/ml-kit/vision/face-detection/android) | Maven `16.1.7` | Bundled, on-device face contours | Google ML Kit terms; Maven artifact |

The face-mask instrumentation fixture `app/src/androidTest/assets/face-portrait.jpg` is the official 2009 White House photograph of Barack Obama by Pete Souza, accessed through [Google MediaPipe test assets](https://storage.googleapis.com/mediapipe-assets/portrait.jpg). The photograph is a U.S. federal government work in the public domain.

The app implements its own Kotlin lens-model evaluation against the unmodified Lensfun calibration files; it does not link the Lensfun library. Unsupported models are excluded from selection. Native bridge/build configuration is app-specific; upstream library source files have not been patched.

Notice copies ship in `app/src/main/assets/licenses` and can be viewed in Settings. LibRaw has additional notices in its COPYRIGHT file; retain all upstream notices when redistributing source or binaries.
