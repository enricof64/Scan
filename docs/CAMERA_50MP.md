# 50 MP capture on the Galaxy Z Fold 8 — verification and Plan B (F0)

Status: **verified on-device (Fold 8 / SM-F971B, Android SDK 37, 2026-10-30)**.

## Verdict (on-device, 2026-10-30)

**50 MP is NOT reachable from a third-party app on the Fold 8.** The public
Camera2/CameraX ceiling is the default stream map, and on this device it caps
at **12.5 MP (4080×3060)** — and the maximum-resolution map adds nothing:

| camera | role | max JPEG (default map) | max-res map |
| --- | --- | --- | --- |
| 0 (back, 5.4 mm, 8.2×6.1 mm) | main 200 MP sensor | 4080×3060 (12.5 MP) | **not exposed** |
| 2 (back, 2.2 mm, 5.7×4.3 mm) | ultrawide | 4080×3060 (12.5 MP) | exposed, but max 1920×1080 (2.1 MP) |
| 1 (front) | wide self | 3648×2736 (10.0 MP) | not exposed |
| 3 (front) | ultra-wide self | 2784×2080 (5.8 MP) | not exposed |

Notes from the same dump:

- `ULTRA_HIGH_RESOLUTION_SENSOR` is advertised (capability 16 in the platform
  numbering — `RAW` is 3), yet the HAL still does not list any JPEG stream
  larger than 12.5 MP. The 200 MP readout of the main sensor is a Samsung
  proprietary capture mode, not exposed through the public Camera2 stream
  configuration maps.
- **CameraX already reaches the public ceiling** (12.5 MP = largest default-map
  JPEG), so **Plan B (direct Camera2 + `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`
  on the max-res map) would gain nothing on this device** and is not
  implemented in F0/F1. If a future device exposes a larger max-res map, the
  capture code can pick `max(default, maxRes)` cheaply — the diagnostics
  screen still reports both so the decision stays evidence-based.
- 12.5 MP is above the scanning quality target: A4 @ 300 DPI needs ≈ 8.7 MP
  (2480×3508); 4080×3060 gives ≈ 350 DPI on A4.

Consequence for the plan: the "50 MP" goal is dropped; the capture target
becomes "largest JPEG in the default stream map" (12.5 MP on the Fold 8).

---

## Question (plan §7, F0)

Are the full 50 MP of the Fold 8's main camera reachable from a third-party
app — via Camera2 `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` /
`ULTRA_HIGH_RESOLUTION_SENSOR` — or does CameraX cap us below that?

## Question (plan §7, F0)

Are the full 50 MP of the Fold 8's main camera reachable from a third-party
app — via Camera2 `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` /
`ULTRA_HIGH_RESOLUTION_SENSOR` — or does CameraX cap us below that?

## Static analysis of CameraX 1.6.2 (done, 2025)

CameraX 1.6.2 **cannot** select the ultra-high (full-resolution) sensor modes:

- `androidx.camera.camera2.internal.Camera2CameraImpl$ResolutionSelector`
  only ever reads `SCALER_STREAM_CONFIGURATION_MAP` (the default stream map)
  and states explicitly in its own source:

  > "This mode does not allow applications to select those ultra high
  > resolutions."

- The only class that sets `CaptureRequest.SENSOR_PIXEL_MODE` is
  `Camera2CaptureRequestConfigurator`, annotated
  `@RestrictTo(Scope.LIBRARY_GROUP)` — internal, not a public API.
- `CaptureRequestOptions` (the public still-capture API) exposes only
  flash / stabilization / zoom controls; nothing selects the sensor pixel
  mode or a resolution above the default map.

Conclusion: **CameraX will pick at most the largest JPEG in the default
stream map** on the Fold 8. Whether that is already 50 MP is a device fact —
some SoCs put the full array in the default map, others only in the
maximum-resolution map.

## On-device verification (owner, ~2 minutes)

1. Install the foss APK (CI artifact of this PR, or the local
   `app/build/outputs/apk/foss/debug/app-foss-debug.apk`).
2. Settings → **Camera diagnostics (50 MP check)** (read-only, no permission).
3. **Copy to clipboard** and send the text.

Per camera the dump reports:

| line | meaning |
| --- | --- |
| `active array (default mode)` | sensor active array as CameraX sees it |
| `max JPEG via default map` | **largest resolution CameraX can capture** |
| `max-res mode available (API 31)` | the device exposes the maximum-resolution stream map |
| `max JPEG via max-res map` | **largest resolution a direct Camera2 `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` session can capture** |
| `ULTRA_HIGH_RESOLUTION_SENSOR` | Android 14 capability flag for full-resolution sensors |
| focal length / sensor size | which camera is the 50 MP one (vs the 12 MP ultrawide) |

Reading:

- `max JPEG via default map` ≈ 50 MP (≈ 8000×6000) → CameraX already suffices,
  no Plan B.
- `max JPEG via default map` < 50 MP **and** `max JPEG via max-res map` ≈
  50 MP → **Plan B confirmed**: 50 MP is only reachable through direct
  Camera2.

## Plan B (the fallback, plan §13): direct Camera2 for the final still

Keep the CameraX viewfinder (preview + torch + live quad detection — all of
which work at preview resolution), and for the **final still capture only**
open a direct `android.hardware.camera2` session on the same camera device:

- `CameraDevice.create` with the same `CameraId` CameraX used (back camera
  with the largest sensor — selection per plan §7: enumerate via
  `CameraCharacteristics`, pick the one whose max-res array matches the
  50 MP lens).
- Capture session configuration: single `CaptureRequest` output stream of
  the max-res JPEG size (from `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`).
- Set `CaptureRequest.SENSOR_PIXEL_MODE =
  SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION` (Android 12+ / API 31) — the flag
  that moves the pipeline to the full-resolution mode. On Android 14+ the
  device must additionally advertise
  `REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR` (the
  diagnostics report this flag).
- Before the capture: **lock AF/AE** (`CONTROL_AE_MODE` lock →
  `CONTROL_AF_MODE` off/locked), per plan §7 (avoid drift); flash on request
  with the glare warning.
- Result arrives as a `ByteBuffer` JPEG at full resolution → straight into the
  existing pipeline (`ScanPipeline` crop/filter at full res, then downscale
  to the real target DPI, plan §8).

Costs / constraints to keep in mind:

- **RAM** (plan §13): a 7000-px-wide JPEG decoded is > 100 MB. Plan: cap the
  working size at ~6000 px or process in tiles; the JPEG itself (~5–15 MB)
  streams to disk before decoding.
- Camera hand-off: releasing the CameraX use case and re-opening the device
  on Camera2 takes a few frames; acceptable for a snap-on-demand flow (F2
  will implement the hand-off; the diagnostics screen only *reports*).
- No extra permission: characteristics and (with the app in foreground)
  camera access are covered by the `CAMERA` permission the foss flavor
  already declares.

## F2 outcome (no Camera2 hand-off needed)

Given the on-device verdict above, the Plan B hand-off (direct Camera2
session with `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`) was **not
implemented**: on the Fold 8 the max-res map offers nothing beyond the
default map (12.5 MP main, 2.1 MP ultrawide), so a Camera2 session would
gain zero resolution and only cost complexity and hand-off latency.

F2 implemented the rest of plan §7 on top of the existing CameraX flow:

- **Explicit lens selection** (`LensPicker`): the app enumerates the back
  lenses' default-map JPEG sizes itself and binds the largest (on the
  Fold 8: camera 0, 4080×3060), instead of trusting
  `CameraSelector.DEFAULT_BACK_CAMERA`. Fallback to the default selector
  when no usable lens is found.
- **Full-resolution stills**: `SAVE_MAX_DIM` raised 3200 → 4500, so the
  stored still keeps the full lens resolution (≈ 350 DPI on A4 after the
  perspective warp); the cap only guards worst-case RAM on bigger sensors.
- **Blur gate**: Laplacian-variance check on every still; below threshold
  an automatic retake fires (max 3 per shot, same focus-lock path);
  whatever frame is left is saved anyway and flagged "sfocato" in the
  review grid. AF/AE lock before each shot was already in the inherited
  `focusAndShoot` path; the torch already existed, F2 adds the reflection
  warning chip.

The done-criterion of §7 ("50 MP selezionati") is therefore met as:
**largest public lens (12.5 MP) selected, frame mosso rilevato e
ri-scattato, originale salvabile** — to be confirmed on the device.
