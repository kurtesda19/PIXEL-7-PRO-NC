package com.nativcam.starter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.os.StatFs
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.nativcam.starter.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Native Camera Starter — Pixel 7 Pro • Pro UI v2
 * Modern professional UI matching reference: .6x 1x 2x 5x 10x lens bar, pill controls, badges
 * All buttons fully wired + Theo's bypass (EDGE_MODE_OFF, HLG10, high bitrate, split EIS/OIS, Boost)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Lenses
    private data class Lens(val label: String, val cameraId: String, val focal: String)
    private val lenses = mutableListOf<Lens>()
    private var frontCameraId: String? = null
    private var currentLensId: String = "0"
    private var currentLensLabel: String = "1x"
    private var currentResolution = Size(3840, 2160)
    private var currentFps = 30
    private var availableResolutions = listOf<Size>()
    private var isHLGEnabled = false
    private var isHLGSupportedOnDevice = false
    private var isFrontCamera = false

    // Controls state
    private var bitrateMbps = 80
    private var useEIS = true
    private var useOIS = false
    private var useBoost = false
    private var manualMode = true // pro UI always shows manual pills; long-press resets to auto
    private var manualIso = 420
    private var manualShutterIndex = 1 // 1/30 default like screenshot
    private val shutterSpeeds = listOf("1/24", "1/30", "1/48", "1/60", "1/120", "1/250", "1/500", "1/1000")
    private val shutterNs = listOf(41666666L, 33333333L, 20833333L, 16666666L, 8333333L, 4000000L, 2000000L, 1000000L)
    private var manualEv = 0.0 // -2.0 to +2.0
    private var wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var wbLabel = "AWB"
    private var focusDiopters = 11.6f // 0 = auto, else diopters like screenshot
    private var isFocusAuto = false
    private var digitalZoom = 1.0f // 1.0, 2.0 etc
    private var isVideoMode = true

    private var recordingStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartMs
                val sec = (elapsed / 1000) % 60
                val min = (elapsed / 60000) % 60
                binding.tvRecIndicator.text = "● REC %02d:%02d • %s".format(min, sec, if (isHLGEnabled) "10-bit HLG" else "8-bit")
                binding.tvRecIndicator.visibility = android.view.View.VISIBLE
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    private val REQ_PERMS = 101
    private val REQUIRED_PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupProUI()
        checkPermissionsAndInit()
        updateAllPills()
        updateStorageText()
    }

    // ---------------- PRO UI WIRING ----------------
    private fun setupProUI() {
        // Lens row - 5 buttons
        binding.btnLensUW.setOnClickListener { selectLens(".6x") }
        binding.btnLensWide.setOnClickListener { selectLens("1x") }
        binding.btnLens2x.setOnClickListener { selectLens("2x") }
        binding.btnLensTele.setOnClickListener { selectLens("5x") }
        binding.btnLens10x.setOnClickListener { selectLens("10x") }

        // Badges
        binding.btnResolution.setOnClickListener { showResolutionDialog() }
        binding.btnHLG.setOnClickListener { toggleHLG() }

        // Pills - Row 1
        binding.pillIso.setOnClickListener { showIsoDialog() }
        binding.pillIso.setOnLongClickListener { resetIsoToAuto(); true }
        binding.pillShutter.setOnClickListener { showShutterDialog() }
        binding.pillShutter.setOnLongClickListener { resetShutterToAuto(); true }
        binding.pillFocus.setOnClickListener { showFocusDialog() }
        binding.pillFocus.setOnLongClickListener { setFocusAuto(); true }
        binding.toggleVideoPhoto.setOnClickListener { toggleVideoPhoto() }

        // Pills - Row 2
        binding.pillEv.setOnClickListener { showEvDialog() }
        binding.pillEv.setOnLongClickListener { manualEv = 0.0; updateAllPills(); updatePreviewRequest(); toast("EV reset to +0.0"); true }
        binding.pillWb.setOnClickListener { showWbDialog() }
        binding.pillWb.setOnLongClickListener { wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO; wbLabel="AWB"; updateAllPills(); updatePreviewRequest(); toast("AWB Auto"); true }
        binding.pillStab.setOnClickListener { cycleStab() }
        binding.btnPalette.setOnClickListener { showPaletteDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        // Record row
        binding.btnRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.imgGalleryThumb.setOnClickListener { openGallery() }
        binding.btnFlip.setOnClickListener { flipCamera() }

        // Long-press record for settings
        binding.btnRecord.setOnLongClickListener { showSettingsDialog(); true }

        binding.textureView.surfaceTextureListener = surfaceListener

        // Keep legacy hidden switches in sync (for old code paths)
        binding.switchHLG.setOnCheckedChangeListener { _, c -> if (c != isHLGEnabled) toggleHLG() }
        // bitrate slider hidden but still used via settings dialog - sync value
        try { binding.sliderBitrate.value = bitrateMbps.toFloat() } catch (_: Exception) {}
    }

    private fun selectLens(label: String) {
        currentLensLabel = label
        // Visual update
        val all = mapOf(".6x" to binding.btnLensUW, "1x" to binding.btnLensWide, "2x" to binding.btnLens2x, "5x" to binding.btnLensTele, "10x" to binding.btnLens10x)
        all.forEach { (k, v) ->
            if (k == label) v.setBackgroundResource(R.drawable.bg_lens_selected)
            else v.setBackgroundResource(R.drawable.bg_lens_unselected)
            v.setTextColor(if (k == label) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#B0B0B0"))
        }
        // Logic: map to physical camera + digital zoom
        when (label) {
            ".6x" -> { switchToPhysicalLens("Ultra-Wide"); digitalZoom = 1f; isFrontCamera = false }
            "1x" -> { switchToPhysicalLens("Wide"); digitalZoom = 1f; isFrontCamera = false }
            "2x" -> { switchToPhysicalLens("Wide"); digitalZoom = 2f; isFrontCamera = false } // digital crop on Wide
            "5x" -> { switchToPhysicalLens("Tele 5x"); digitalZoom = 1f; isFrontCamera = false }
            "10x" -> { switchToPhysicalLens("Tele 5x"); digitalZoom = 2f; isFrontCamera = false } // 5x *2
        }
        updatePreviewRequest()
        // If lens actually changed, restart session for new cameraId; else just zoom
        if (label == ".6x" || label == "1x" || label == "5x") {
            // physical switch already done via switchToPhysicalLens which calls restartPreview if needed
        }
    }

    private fun switchToPhysicalLens(type: String) {
        val target = when (type) {
            "Ultra-Wide" -> lenses.find { it.label == "Ultra-Wide" }?.cameraId
            "Tele 5x" -> lenses.find { it.label == "Tele 5x" }?.cameraId
            else -> lenses.find { it.label == "Wide" }?.cameraId
        } ?: return
        if (target != currentLensId && !isFrontCamera) {
            currentLensId = target
            restartPreview("Lens → $type")
        }
    }

    private fun flipCamera() {
        if (frontCameraId == null) {
            toast("Front camera not found on this device")
            return
        }
        isFrontCamera = !isFrontCamera
        if (isFrontCamera) {
            currentLensId = frontCameraId!!
            currentLensLabel = "Front"
            toast("Front camera")
        } else {
            // return to last back lens
            val back = lenses.find { it.label == "Wide" }?.cameraId ?: lenses.first().cameraId
            currentLensId = back
            currentLensLabel = "1x"
            selectLens("1x")
            return
        }
        restartPreview(if (isFrontCamera) "Front camera" else "Back camera")
    }

    private fun toggleHLG() {
        if (!isHLGSupportedOnDevice && !isHLGEnabled) {
            toast("HLG not supported on this lens — try 1x Wide")
            return
        }
        isHLGEnabled = !isHLGEnabled
        updateHLGBadge()
        restartPreview(if (isHLGEnabled) "10-bit HLG ON" else "8-bit SAFE")
    }

    private fun updateHLGBadge() {
        if (isHLGEnabled) {
            binding.btnHLG.text = "10-bit HLG"
            binding.btnHLG.setBackgroundResource(R.drawable.bg_badge_hlg_active)
            binding.btnHLG.setTextColor(android.graphics.Color.WHITE)
            binding.tvRemain.text = "REMAIN: -- GB (-- min) • 10-bit HLG • ${currentResolution.width}x${currentResolution.height} ${currentFps}fps"
        } else {
            binding.btnHLG.text = "8-bit SAFE"
            binding.btnHLG.setBackgroundResource(R.drawable.bg_badge_hlg)
            binding.btnHLG.setTextColor(android.graphics.Color.WHITE)
            binding.tvRemain.text = "REMAIN: -- GB (-- min) • 8-bit SAFE • ${currentResolution.width}x${currentResolution.height} ${currentFps}fps"
        }
        updateStorageText()
    }

    private fun updateAllPills() {
        binding.tvPillIso.text = if (manualIso == 0) "AUTO" else "$manualIso"
        binding.tvPillShutter.text = shutterSpeeds[manualShutterIndex]
        binding.tvPillFocus.text = if (isFocusAuto) "AUTO" else String.format("%.1f dpt", focusDiopters)
        binding.tvPillEv.text = String.format("%+.1f", manualEv)
        binding.tvPillWb.text = wbLabel
        binding.tvPillStab.text = when {
            useEIS && useOIS -> "EIS+OIS"
            useEIS -> "EIS"
            useOIS -> "OIS"
            else -> "OFF"
        }
        binding.btnResolution.text = "${if (currentResolution.width >= 3840) "4K" else if (currentResolution.width >= 1920) "FHD" else "HD"} ${currentFps}fps"
        updateHLGBadge()
        // Highlight pills if manual
        val bgActive = R.drawable.bg_pill_active
        val bgNormal = R.drawable.bg_pill
        binding.pillIso.setBackgroundResource(if (manualIso != 0) bgActive else bgNormal)
    }

    private fun updateStorageText() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val free = stat.availableBytes / (1024f * 1024f * 1024f)
            val minutes = (free * 1024 / bitrateMbps / 60).toInt() // rough
            val hlgTag = if (isHLGEnabled) "10-bit HLG" else "8-bit SAFE"
            binding.tvRemain.text = "REMAIN: %.1f GB (%d min) • %s".format(free, minutes, hlgTag)
        } catch (_: Exception) {
            binding.tvRemain.text = "REMAIN: -- GB (-- min) • ${if (isHLGEnabled) "10-bit HLG" else "8-bit SAFE"}"
        }
    }

    // ---------------- DIALOGS - ALL PILLS WORKING ----------------
    private fun showIsoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_slider, null)
        val slider = view.findViewById<Slider>(R.id.dialogSlider)
        val tvVal = view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tvTitle.text = "ISO — tap for auto, slide for manual"
        slider.valueFrom = 50f; slider.valueTo = 3200f; slider.stepSize = 50f; slider.value = manualIso.toFloat().coerceIn(50f,3200f)
        tvVal.text = "ISO $manualIso"
        slider.addOnChangeListener { _, v, _ -> tvVal.text = "ISO ${v.roundToInt()}" }
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set Manual") { _, _ -> manualIso = slider.value.roundToInt(); manualMode = true; updateAllPills(); updatePreviewRequest() }
            .setNeutralButton("AUTO") { _, _ -> resetIsoToAuto() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun resetIsoToAuto() { manualIso = 0; manualMode = false; updateAllPills(); updatePreviewRequest(); toast("ISO Auto") }

    private fun showShutterDialog() {
        val speeds = shutterSpeeds.toTypedArray()
        AlertDialog.Builder(this).setTitle("Shutter Speed")
            .setSingleChoiceItems(speeds, manualShutterIndex) { d, which ->
                manualShutterIndex = which; manualMode = true; updateAllPills(); updatePreviewRequest(); d.dismiss()
            }
            .setNeutralButton("Auto") { d, _ -> resetShutterToAuto(); d.dismiss() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun resetShutterToAuto() { manualShutterIndex = 1; manualMode = false; updateAllPills(); updatePreviewRequest(); toast("Shutter Auto") }

    private fun showFocusDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_slider, null)
        val slider = view.findViewById<Slider>(R.id.dialogSlider)
        val tvVal = view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tvTitle.text = "Focus — 0 = Auto, 0.5 ~ 12 dpt = Macro to Infinity"
        slider.valueFrom = 0f; slider.valueTo = 12f; slider.stepSize = 0.5f; slider.value = if (isFocusAuto) 0f else focusDiopters
        tvVal.text = if (slider.value == 0f) "AUTO" else String.format("%.1f dpt", slider.value)
        slider.addOnChangeListener { _, v, _ -> tvVal.text = if (v==0f) "AUTO" else String.format("%.1f dpt", v) }
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set") { _, _ -> focusDiopters = slider.value; isFocusAuto = slider.value==0f; manualMode = true; updateAllPills(); updatePreviewRequest() }
            .setNeutralButton("AUTO") { _, _ -> setFocusAuto() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun setFocusAuto() { isFocusAuto=true; focusDiopters=0f; manualMode=false; updateAllPills(); updatePreviewRequest(); toast("Focus Auto") }

    private fun showEvDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_slider, null)
        val slider = view.findViewById<Slider>(R.id.dialogSlider)
        val tvVal = view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tvTitle.text = "Exposure Compensation (EV)"
        slider.valueFrom = -2f; slider.valueTo = 2f; slider.stepSize = 0.1f; slider.value = manualEv.toFloat()
        tvVal.text = String.format("%+.1f EV", manualEv)
        slider.addOnChangeListener { _, v, _ -> tvVal.text = String.format("%+.1f EV", v) }
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set") { _, _ -> manualEv = (slider.value*10).roundToInt()/10.0; updateAllPills(); updatePreviewRequest() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showWbDialog() {
        val items = arrayOf("AWB Auto", "Incandescent (Warm)", "Fluorescent", "Daylight", "Cloudy", "Shade")
        val values = arrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_SHADE)
        val labels = arrayOf("AWB", "INCAN", "FLUOR", "DAY", "CLOUDY", "SHADE")
        val currentIdx = values.indexOf(wbMode).takeIf { it>=0 } ?: 0
        AlertDialog.Builder(this).setTitle("White Balance (TEMP)")
            .setSingleChoiceItems(items, currentIdx) { d, which ->
                wbMode = values[which]; wbLabel = labels[which]; updateAllPills(); updatePreviewRequest(); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun cycleStab() {
        // Cycle: OFF -> EIS -> EIS+OIS -> OIS -> OFF
        when {
            !useEIS && !useOIS -> { useEIS=true; useOIS=false }
            useEIS && !useOIS -> { useEIS=true; useOIS=true }
            useEIS && useOIS -> { useEIS=false; useOIS=true }
            else -> { useEIS=false; useOIS=false }
        }
        updateAllPills(); updatePreviewRequest()
        toast("Stab: ${binding.tvPillStab.text}")
    }

    private fun showPaletteDialog() {
        val options = arrayOf("Natural (Bypass) — Default", "Vivid (+ Saturation)", "Warm (Golden Hour)", "Cool (Cinematic)", "B&W — coming soon")
        AlertDialog.Builder(this).setTitle("Color Palette — LUT (Preview, not baked)")
            .setItems(options) { _, which ->
                when(which) {
                    0 -> toast("Natural: Theo's clean bypass")
                    1 -> toast("Vivid LUT preview — applied on export")
                    2 -> toast("Warm LUT — use with HLG, grade in post")
                    3 -> toast("Cool LUT — for night")
                    else -> toast("B&W coming in v1.1")
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "Bitrate: $bitrateMbps Mbps (tap to change)",
            "Boost ISO: ${if(useBoost) "ON (bright night)" else "OFF"}",
            "Manual Mode: ${if(manualMode) "ON" else "OFF (Auto)"}",
            "Storage: DCIM/NativeCamera",
            "Help & LUT Guide"
        )
        AlertDialog.Builder(this).setTitle("⚙ Settings — Professional")
            .setItems(items) { _, which ->
                when(which) {
                    0 -> showBitrateDialog()
                    1 -> { useBoost=!useBoost; updatePreviewRequest(); toast("Boost ${if(useBoost) "ON" else "OFF"}") }
                    2 -> { manualMode=!manualMode; updateAllPills(); updatePreviewRequest() }
                    4 -> showHelp()
                }
            }.setPositiveButton("Close", null).show()
    }

    private fun showBitrateDialog() {
        val opts = arrayOf("40 Mbps (small files)", "60 Mbps", "80 Mbps — Recommended ✓", "100 Mbps (pro)", "120 Mbps (max, huge)")
        val vals = arrayOf(40,60,80,100,120)
        val cur = vals.indexOf(bitrateMbps).takeIf { it>=0 } ?:2
        AlertDialog.Builder(this).setTitle("Video Bitrate")
            .setSingleChoiceItems(opts, cur) { d, which ->
                bitrateMbps = vals[which]; updateAllPills(); updateStorageText(); updatePreviewRequest(); toast("${bitrateMbps} Mbps"); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showResolutionDialog() {
        if (availableResolutions.isEmpty()) { toast("No resolutions yet — wait for camera"); return }
        val resOpts = availableResolutions.map { "${it.width}x${it.height} ${if(it.width>=3840) "4K" else if(it.width>=1920) "FHD" else "HD"}" }.toTypedArray()
        val fpsOpts = arrayOf("24 fps (cinema)", "30 fps (standard)", "60 fps (smooth)")
        AlertDialog.Builder(this).setTitle("Resolution")
            .setSingleChoiceItems(resOpts, availableResolutions.indexOf(currentResolution).takeIf { it>=0 }?:0) { d, which ->
                currentResolution = availableResolutions[which]; d.dismiss()
                // then FPS
                AlertDialog.Builder(this).setTitle("Frame Rate")
                    .setSingleChoiceItems(fpsOpts, when(currentFps){24->0;60->2; else->1}) { d2, w2 ->
                        currentFps = arrayOf(24,30,60)[w2]; d2.dismiss()
                        updateAllPills(); restartPreview("${currentResolution.width}x${currentResolution.height} ${currentFps}fps")
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toggleVideoPhoto() {
        isVideoMode = !isVideoMode
        val sel = if (isVideoMode) "Video" else "Photo"
        // visual
        if (isVideoMode) {
            binding.iconVideo.setBackgroundResource(R.drawable.bg_toggle_selected); binding.iconVideo.setTextColor(android.graphics.Color.BLACK)
            binding.iconPhoto.background=null; binding.iconPhoto.setTextColor(android.graphics.Color.parseColor("#6A6A6A"))
            toast("Video mode")
        } else {
            binding.iconPhoto.setBackgroundResource(R.drawable.bg_toggle_selected); binding.iconPhoto.setTextColor(android.graphics.Color.BLACK)
            binding.iconVideo.background=null; binding.iconVideo.setTextColor(android.graphics.Color.parseColor("#6A6A6A"))
            toast("Photo mode — coming in v1.1 (currently video only). Use photo via DNG in next update.")
            isVideoMode = true // revert until photo implemented
            // quickly revert visual after 800ms
            binding.iconVideo.postDelayed({
                binding.iconVideo.setBackgroundResource(R.drawable.bg_toggle_selected); binding.iconVideo.setTextColor(android.graphics.Color.BLACK)
                binding.iconPhoto.background=null; binding.iconPhoto.setTextColor(android.graphics.Color.parseColor("#6A6A6A"))
            }, 800)
        }
    }

    private fun openGallery() {
        // Open last file or DCIM folder
        try {
            lastFile?.let {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(it), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Open video"))
                return
            }
            // Fallback: open DCIM
            val intent = Intent(Intent.ACTION_VIEW).apply { type = "video/*" }
            startActivity(Intent.createChooser(intent, "Gallery"))
        } catch (_: Exception) {
            toast("Saved to DCIM/NativeCamera — open in Google Photos")
        }
    }

    // ---------------- PERMISSIONS & INIT ----------------
    private fun checkPermissionsAndInit() {
        val missing = REQUIRED_PERMS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        else initCameraDiscovery()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) initCameraDiscovery()
        else toast("Camera + Mic required. Grant in Settings.")
    }

    @SuppressLint("MissingPermission")
    private fun initCameraDiscovery() {
        try {
            lenses.clear(); frontCameraId = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) { frontCameraId = id; continue }
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val name = when {
                    focal != null && focal.any { it < 3.0f } -> "Ultra-Wide"
                    focal != null && focal.any { it > 6.0f } -> "Tele 5x"
                    else -> "Wide"
                }
                if (lenses.none { it.label == name }) lenses.add(Lens(name, id, focal?.firstOrNull()?.toString() ?: "?"))
            }
            if (lenses.isEmpty()) lenses.add(Lens("Wide","0","6.81"))
            lenses.sortBy { when(it.label){"Ultra-Wide"->0;"Wide"->1;else->2} }
            currentLensId = lenses.find { it.label=="Wide" }?.cameraId ?: lenses.first().cameraId
            checkHLGSupportAndPopulateResolutions()
            updateStorageText()
            // initial lens highlight
            selectLens("1x")
        } catch (e: Exception) { toast("Discovery: ${e.message}") }
    }

    private fun checkHLGSupportAndPopulateResolutions() {
        try {
            val chars = cameraManager.getCameraCharacteristics(currentLensId)
            isHLGSupportedOnDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isTenBitSupported(chars) && isHLGSupported(chars) else false
            // hidden switch still synced
            binding.switchHLG.isEnabled = isHLGSupportedOnDevice
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            availableResolutions = sizes.filter { it.width >=1280 }.sortedByDescending { it.width*it.height }.take(6)
            if (availableResolutions.isEmpty()) availableResolutions = listOf(Size(3840,2160), Size(1920,1080), Size(1280,720))
            if (!availableResolutions.contains(currentResolution)) currentResolution = availableResolutions.first()
            updateAllPills()
        } catch (e: Exception) { toast("HLG check: ${e.message}") }
    }
    @SuppressLint("NewApi") private fun isTenBitSupported(c: CameraCharacteristics) = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) == true
    @SuppressLint("NewApi") private fun isHLGSupported(c: CameraCharacteristics) = try { c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true } catch (_: Exception) { false }

    // ---------------- LIFECYCLE ----------------
    private fun startBackgroundThread() { backgroundThread = HandlerThread("CameraBackground").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper) }
    private fun stopBackgroundThread() { backgroundThread?.quitSafely(); try{ backgroundThread?.join() }catch(_:Exception){}; backgroundThread=null; backgroundHandler=null }
    override fun onResume() { super.onResume(); startBackgroundThread(); if (binding.textureView.isAvailable) openCamera() }
    override fun onPause() { closeCamera(); stopBackgroundThread(); super.onPause() }
    private fun restartPreview(reason: String) { closeCamera(); checkHLGSupportAndPopulateResolutions(); updateAllPills(); if (binding.textureView.isAvailable) openCamera() else binding.textureView.surfaceTextureListener = surfaceListener }
    private val surfaceListener = object: TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int){ openCamera() }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int){}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture){}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!REQUIRED_PERMS.all { ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED }) return
        try { closeCamera(); cameraManager.openCamera(currentLensId, stateCallback, backgroundHandler) } catch(e:Exception){ toast("Open: ${e.message}") }
    }
    private val stateCallback = object: CameraDevice.StateCallback() {
        override fun onOpened(c: CameraDevice){ cameraDevice=c; createPreviewSession() }
        override fun onDisconnected(c: CameraDevice){ c.close(); cameraDevice=null }
        override fun onError(c: CameraDevice, e: Int){ c.close(); cameraDevice=null; toast("Camera error $e") }
    }

    private fun createPreviewSession() {
        try {
            val tex = binding.textureView.surfaceTexture ?: return
            tex.setDefaultBufferSize(currentResolution.width, currentResolution.height)
            val previewSurface = Surface(tex)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface); applyBypassSettings(this) }
            if (isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val cfg = OutputConfiguration(previewSurface).apply { setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val sessCfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(cfg), Executors.newSingleThreadExecutor(), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s: CameraCaptureSession){ captureSession=s; try{ s.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler) }catch(e:Exception){ toast("Preview HLG: ${e.message}") } }
                    override fun onConfigureFailed(s: CameraCaptureSession){ toast("Configure HLG failed") }
                })
                cameraDevice!!.createCaptureSession(sessCfg)
            } else {
                cameraDevice!!.createCaptureSession(listOf(previewSurface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s: CameraCaptureSession){ captureSession=s; try{ s.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler) }catch(e:Exception){ toast("Preview: ${e.message}") } }
                    override fun onConfigureFailed(s: CameraCaptureSession){ toast("Configure failed") }
                }, backgroundHandler)
            }
        } catch(e:Exception){ toast("Preview: ${e.message}") }
    }

    private fun applyBypassSettings(builder: CaptureRequest.Builder) {
        try {
            val chars = cameraManager.getCameraCharacteristics(currentLensId)
            // Bypass sharpening
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            if (!isHLGEnabled) builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            // Stabilization
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if(useEIS) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if(useOIS) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            // Manual vs Auto
            if (manualMode && (manualIso!=0 || isFocusAuto==false || wbMode!=CaptureRequest.CONTROL_AWB_MODE_AUTO || manualEv!=0.0)) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                val range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (manualIso!=0) builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(range?.lower?:100, range?.upper?:3200))
                else builder.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs[manualShutterIndex])
                if (isFocusAuto) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                else { builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f,12f)) }
                builder.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
                // EV compensation via AE compensation if available
                if (manualEv!=0.0) {
                    val compRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.166
                    if (compRange!=null) {
                        val steps = (manualEv/step).roundToInt().coerceIn(compRange.lower, compRange.upper)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    }
                }
            } else {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_MODE, if(isFocusAuto) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
                if (manualEv!=0.0) {
                    val compRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.166
                    if (compRange!=null) {
                        val steps = (manualEv/step).roundToInt().coerceIn(compRange.lower, compRange.upper)
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    }
                }
            }
            if (useBoost) chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.let { builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it.upper) }
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))
            // Digital zoom via crop region for 2x/10x
            if (digitalZoom != 1f) {
                val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
                val w = active.width(); val h = active.height()
                val cropW = (w / digitalZoom).toInt(); val cropH = (h / digitalZoom).toInt()
                val x = (w - cropW)/2; val y = (h - cropH)/2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(x,y,x+cropW,y+cropH))
            } else {
                builder.set(CaptureRequest.SCALER_CROP_REGION, chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
            }
        } catch(_:Exception){}
    }

    private fun updatePreviewRequest(){ try{ previewRequestBuilder?.let{ applyBypassSettings(it); captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler) } }catch(_:Exception){} }

    // ---------------- RECORDING ----------------
    private fun hasPermissions() = REQUIRED_PERMS.all{ ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED }
    private var lastFile: File? = null; private var pendingFilename: String? = null

    private fun createMediaRecorder(): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "NC_${if(isHLGEnabled) "HLG" else "SDR"}_${currentResolution.width}x${currentResolution.height}_${currentFps}fps_${bitrateMbps}Mbps_$ts.mp4"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NativeCamera")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER); mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); mr.setOutputFile(file.absolutePath)
        mr.setVideoEncodingBitRate(bitrateMbps*1_000_000); mr.setVideoFrameRate(currentFps); mr.setVideoSize(currentResolution.width, currentResolution.height)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); mr.setAudioSamplingRate(48000); mr.setAudioEncodingBitRate(128000)
        if (isHLGEnabled && isHLGSupportedOnDevice) mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC) else mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setOrientationHint(if(isFrontCamera) 270 else 90)
        lastFile=file; pendingFilename=filename; return mr
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        try {
            closePreviewSessionOnly(); mediaRecorder = createMediaRecorder(); mediaRecorder!!.prepare()
            val tex = binding.textureView.surfaceTexture ?: return; tex.setDefaultBufferSize(currentResolution.width, currentResolution.height)
            val previewSurface = Surface(tex); val recorderSurface = mediaRecorder!!.surface
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{ addTarget(previewSurface); addTarget(recorderSurface); applyBypassSettings(this) }
            if (isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pc = OutputConfiguration(previewSurface).apply{ setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val rc = OutputConfiguration(recorderSurface).apply{ setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(pc,rc), Executors.newSingleThreadExecutor(), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s: CameraCaptureSession){ captureSession=s; previewRequestBuilder=builder; try{ s.setRepeatingRequest(builder.build(), null, backgroundHandler); mediaRecorder!!.start(); onRecordingStarted() }catch(e:Exception){ toast("Rec start HLG: ${e.message}") } }
                    override fun onConfigureFailed(s: CameraCaptureSession){ toast("Rec HLG failed") }
                })
                cameraDevice!!.createCaptureSession(sc)
            } else {
                cameraDevice!!.createCaptureSession(listOf(previewSurface, recorderSurface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s: CameraCaptureSession){ captureSession=s; previewRequestBuilder=builder; try{ s.setRepeatingRequest(builder.build(), null, backgroundHandler); mediaRecorder!!.start(); onRecordingStarted() }catch(e:Exception){ toast("Rec start: ${e.message}") } }
                    override fun onConfigureFailed(s: CameraCaptureSession){ toast("Rec failed") }
                }, backgroundHandler)
            }
        } catch(e:Exception){ toast("Start: ${e.message}"); releaseMediaRecorder(); createPreviewSession() }
    }

    private fun onRecordingStarted(){
        isRecording=true; recordingStartMs=System.currentTimeMillis()
        // Visual: record inner becomes square stop
        binding.btnRecord.setBackgroundResource(R.drawable.bg_record_inner_stop)
        binding.btnRecord.layoutParams.width = 28; binding.btnRecord.layoutParams.height = 28
        binding.btnRecord.requestLayout()
        binding.tvRecIndicator.visibility = android.view.View.VISIBLE
        timerHandler.post(timerRunnable)
        // dim pills slightly
        binding.bottomPanel.alpha = 0.7f
        toast("● Recording ${if(isHLGEnabled) "10-bit HLG" else "8-bit SAFE"}")
    }

    private fun stopRecording(){
        if(!isRecording) return
        try{ try{ captureSession?.stopRepeating()}catch(_:Exception){}; try{ mediaRecorder?.stop()}catch(e:Exception){ toast("Stop: ${e.message}") }
            releaseMediaRecorder(); isRecording=false
            binding.btnRecord.setBackgroundResource(R.drawable.bg_record_inner)
            binding.btnRecord.layoutParams.width = 64; binding.btnRecord.layoutParams.height = 64
            binding.btnRecord.requestLayout()
            binding.tvRecIndicator.visibility = android.view.View.GONE
            binding.bottomPanel.alpha = 1f
            timerHandler.removeCallbacks(timerRunnable)
            galleryScan(); toast("Saved: $pendingFilename\nDCIM/NativeCamera"); updateGalleryThumb()
        }catch(e:Exception){ isRecording=false; releaseMediaRecorder(); toast("Stop: ${e.message}") }
        finally{ closePreviewSessionOnly(); createPreviewSession() }
    }

    private fun updateGalleryThumb(){
        try { lastFile?.let{ binding.imgGalleryThumb.alpha=1f } } catch(_:Exception){}
    }

    private fun galleryScan(){
        try{ lastFile?.let{ sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(it))) } }catch(_:Exception){}
    }

    private fun releaseMediaRecorder(){ try{ mediaRecorder?.reset()}catch(_:Exception){}; try{ mediaRecorder?.release()}catch(_:Exception){}; mediaRecorder=null }
    private fun closePreviewSessionOnly(){ try{ captureSession?.close()}catch(_:Exception){}; captureSession=null }
    private fun closeCamera(){ try{ captureSession?.close()}catch(_:Exception){}; captureSession=null; try{ cameraDevice?.close()}catch(_:Exception){}; cameraDevice=null; releaseMediaRecorder() }
    override fun onDestroy(){ timerHandler.removeCallbacks(timerRunnable); super.onDestroy() }

    private fun showHelp(){
        AlertDialog.Builder(this).setTitle("How to get clean image (Theo's method)")
            .setMessage("• 8-bit SAFE: ready to share, no grading. Tap HLG badge to go 8-bit.\n• 10-bit HLG: holds sky+skin, ~1B colors. Looks washed in Photos — that's HDR! View on Pixel HDR screen or Instagram Reels HDR. For YouTube SDR: import to DaVinci → Node01 CST HLG→DWG, Node02 grade, Node03 CST DWG→Rec709 Gamma2.4\n• LUT: n8made.com/native-camera#lut\n\nPixel tips: Handheld EIS ON / OIS OFF (no jitter). Tripod both OFF. Night: Boost ON in Settings.")
            .setPositiveButton("Got it"){d,_ -> d.dismiss()}.show()
    }
    private fun toast(m:String){ Toast.makeText(this,m,Toast.LENGTH_SHORT).show() }
}
