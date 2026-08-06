# Native Camera Starter — Pixel 7 Pro
### 8-bit SDR + 10-bit HLG Bypass • Personal Use APK

This is a **working Android Studio starter project** cloning Theo's [Native Camera](https://play.google.com/store/apps/details?id=com.rawcam.app) technique from the video [Google Won't Fix The Android Camera, So I Did...](https://www.youtube.com/watch?v=C2b4P94QSKE&t=585s)

**You have both modes in one APK** (toggle at top):
- **8-bit SDR (switch OFF)** — Ready to share instantly. Bypasses Google's over-sharpening but stays in Rec.709. Less highlight latitude.
- **10-bit HLG10 (switch ON)** — Pro grade. Holds sky + skin. ~1B colors. Needs grading for SDR or HDR playback.

---

## ✨ What This Starter Implements (Theo's core)

| Feature | Implementation |
|---|---|
| **Bypass processing** | `EDGE_MODE_OFF` + `NOISE_REDUCTION_MODE_OFF` + `DISTORTION_CORRECTION_OFF` in `applyBypassSettings()` — removes waxy skin / crunchy leaves |
| **10-bit HLG** | `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT` check + `OutputConfiguration.setDynamicRangeProfile(HLG10)` + `SessionConfiguration` (Android 13+). HEVC encoder. |
| **8-bit fallback** | Classic `createCaptureSession` without HLG — still bypassed, so better than stock |
| **Split Stabilization** | Separate toggles `CONTROL_VIDEO_STABILIZATION_MODE` (EIS) and `LENS_OPTICAL_STABILIZATION_MODE` (OIS). Tip: Pixel handheld = EIS ON / OIS OFF |
| **Boost ISO** | `CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE` → max — Theo's bright viewfinder + clean night shots |
| **High Bitrate** | Slider 20–120 Mbps (`setVideoEncodingBitRate`). Stock ~50. His uses ~80-100. |
| **Manual controls** | ISO / Shutter (ns) / Focus distance (diopters) + AE/AF/AWB auto fallback |
| **Multi-lens** | Auto-discovers Pixel 7 Pro lenses: 0.5× UW, 1× Wide (GN1), 5× Tele — switch without restart |
| **Local files** | Saves to `DCIM/NativeCamera/NC_HLG_3840x2160_30fps_80Mbps_20250806_143022.mp4` + MediaStore scan |

---

## 📱 Pixel 7 Pro Specific

- **Tested target:** Pixel 7 Pro, Android 14/15, Tensor G2. Min SDK 33 (Android 13) required for HLG. App will auto-disable HLG toggle on unsupported lens/OS but 8-bit still works and is still cleaner than stock.
- **Resolution:** Auto-populated from `SCALER_STREAM_CONFIGURATION_MAP` (filter ≥720p). Default 4K. Override via dropdown.
- **FPS:** 24 / 30 / 60 (if sensor supports; 4K60 HLG may limit to 30 on tele).
- **Why EIS OFF / OIS OFF on tripod?** Theo found Pixel 10 Pro's OIS causes micro-shakes handheld — same on 7 Pro to lesser extent. Electronic only is smoother handheld.

### Recommended Settings (from video + guide)

- **Day handheld B-roll:** 10-bit HLG, EIS ON OIS OFF, 4K30, 80 Mbps, Auto, Boost OFF
- **Low light / Night:** 10-bit HLG, EIS ON OIS OFF, Manual ISO 1200-2000, Shutter 1/30, Boost ON
- **Tripod:** Both stabilizations OFF, 4K30/60, 100 Mbps
- **Instant share (no grading):** 8-bit SDR, Auto, both stabilizations ON

---

## 🚀 How to Build (2 minutes)

### Option A: Android Studio (recommended)
1. Open **Android Studio Hedgehog or newer** (needs Kotlin 1.9.22, Gradle 8.6)
2. **File → Open** → select folder `NativeCamera-Starter`
3. Wait for Gradle sync (needs internet first time)
4. Plug in Pixel 7 Pro via USB → Enable **Developer Options → USB Debugging**
5. Click **Run ▶** (or **Build → Build APK(s)** for personal APK)
6. Grant **Camera + Microphone** when asked — app goes straight to preview

### Option B: Command line (inside this folder)
```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**First launch checklist:**
- Point at face + bright window/sky → record 10 sec in **8-bit**, then toggle **10-bit HLG** → record same 10 sec → compare in gallery. HLG will look washed out until graded — that's normal HDR.
- Pinch the preview? (Not yet implemented — use lens buttons. Add pinch-zoom via `SCALER_CROP_REGION` if you want.)
- Check `DCIM/NativeCamera` in Files — no cloud, no tracking.

---

## 🎨 Grading Your 10-bit HLG

**Straight from phone:** HLG plays correctly on Pixel's own HDR screen + Instagram Reels (HLG is Reels' preferred HDR). Upload HLG directly for HDR.

**For YouTube SDR / normal screens:**

1. **Fastest:** Download Theo's free LUT: [Native Camera HLG (Pixel Tuned) v1 .cube](https://n8made.com/native-camera#lut) (5.9MB). In DaVinci Resolve/Premiere: apply LUT to HLG clip on Rec.709 timeline → done.

2. **Manual (Theo's own 3-node):**
   ```
   Node 01: Color Space Transform
     Input Color Space: Rec.2100
     Input Gamma: Rec.2100 HLG
     Output: DaVinci Wide Gamut / DaVinci Intermediate
   Node 02: YOUR GRADE HERE
   Node 03: Color Space Transform
     Input: DaVinci Wide Gamut / Intermediate
     Output: Rec.709 / Gamma 2.4
   ```

---

## 📂 Project Structure

```
NativeCamera-Starter/
├── app/src/main/java/com/nativcam/starter/MainActivity.kt  ← All logic (read this first)
├── app/src/main/res/layout/activity_main.xml                ← Single-page UI (Theo-style)
├── app/src/main/AndroidManifest.xml
├── app/build.gradle.kts  (minSdk 33, target 34, viewBinding)
└── README.md (this file)
```

**Key function to study:** `applyBypassSettings()` in `MainActivity.kt` — that's the 20 lines that fix the Pixel.

---

## 🔧 Want to Extend?

- **Add pinch-zoom:** Use `CaptureRequest.SCALER_CROP_REGION` + `CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`
- **Add histogram / zebras:** Read `CaptureResult` via `CameraCaptureSession.CaptureCallback`
- **True 10-bit Main10:** Currently uses `MediaRecorder` HEVC (Pixel infers Main10). For strict control, switch to `MediaCodec` + `MediaMuxer` + `MediaFormat.KEY_COLOR_TRANSFER = COLOR_TRANSFER_HLG`.
- **Photo mode:** Add `ImageReader` for DNG RAW (like `CONTROL_ENABLE_ZSL`).
- **Lower minSdk:** Change to 26 and wrap HLG code in `if (SDK_INT >= 33)` — 8-bit still works on older Pixels.

---

## ⚠️ Notes

- Some HAL processing can't be fully bypassed (at chip level) — that's why reviews say "a bit jumpy" vs stock. Toggle EIS/OIS and try compatibility via resolution dropdown.
- File sizes are huge at high bitrate — 80 Mbps ≈ 600 MB per 10 min. That's intentional for grading. Use 40 Mbps if you want smaller.
- This is for **personal use** — if you publish to Play Store, add privacy policy ("No data collected") + test on many devices like Theo does.

---

**Enjoy!** Shoot a face + sky test and you'll see why Theo went viral — same sensor, totally different image.

Helpful links:
- Guide: https://n8made.com/native-camera
- Play Store (reference): https://play.google.com/store/apps/details?id=com.rawcam.app
- Android HDR docs: https://developer.android.com/media/camera/camera2/hdr-video-capture
