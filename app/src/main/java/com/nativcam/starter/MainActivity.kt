package com.nativcam.starter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nativcam.starter.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Native Camera Starter — Pixel 7 Pro
 * 
 * This is a faithful clone of Theo's "Native Camera" technique:
 * - Camera2 API direct (bypass HAL processing with EDGE_MODE_OFF etc.)
 * - Dual mode: 8-bit SDR (easy share) + 10-bit HLG10 (pro grade, holds highlights)
 * - Split EIS/OIS control (critical for Pixel jitter)
 * - High bitrate HEVC/H264 + Boost ISO + Manual controls
 * 
 * Based on: https://developer.android.com/media/camera/camera2/hdr-video-capture
 * and n8made.com/native-camera guide.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera state
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Pixel 7 Pro lenses - filled dynamically
    private data class Lens(val label: String, val cameraId: String, val focal: String)
    private val lenses = mutableListOf<Lens>()
    private var currentLensId: String = "0"
    private var currentResolution = Size(3840, 2160) // 4K default
    private var currentFps = 30
    private var availableResolutions = listOf<Size>()
    private var isHLGEnabled = false
    private var isHLGSupportedOnDevice = false

    // UI state
    private var bitrateMbps = 80
    private var useEIS = true
    private var useOIS = false // Theo recommends OFF for Pixel handheld
    private var useBoost = false
    private var manualMode = false
    private var manualIso = 400
    private var manualShutterIndex = 3 // maps to shutter speeds
    private val shutterSpeeds = listOf("1/24s", "1/30s", "1/48s", "1/60s", "1/120s", "1/250s", "1/500s")
    private val shutterNs = listOf(41666666L, 33333333L, 20833333L, 16666666L, 8333333L, 4000000L, 2000000L)

    private var recordingStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartMs
                val sec = (elapsed / 1000) % 60
                val min = (elapsed / 60000) % 60
                binding.tvRecIndicator.text = "● REC %02d:%02d • %s".format(min, sec, if (isHLGEnabled) "10-bit HLG" else "8-bit")
                timerHandler.postDelayed(this, 500)
            }
        }
    }

    // Permissions
    private val REQ_PERMS = 101
    private val REQUIRED_PERMS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        setupUI()
        checkPermissionsAndInit()
    }

    private fun setupUI() {
        // Lens buttons
        binding.btnLensWide.setOnClickListener { switchLens("wide") }
        binding.btnLensUW.setOnClickListener { switchLens("uw") }
        binding.btnLensTele.setOnClickListener { switchLens("tele") }
        binding.toggleGroupLens.check(binding.btnLensWide.id)

        // HLG switch - core Theo feature
        binding.switchHLG.setOnCheckedChangeListener { _, isChecked ->
            isHLGEnabled = isChecked
            binding.switchHLG.text = if (isChecked) getString(R.string.hlg_on) else getString(R.string.hlg_off)
            if (isChecked && !isHLGSupportedOnDevice) {
                Toast.makeText(this, "HLG not supported on this lens/Android version - staying in 8-bit. Try Wide lens.", Toast.LENGTH_LONG).show()
                binding.switchHLG.isChecked = false
                isHLGEnabled = false
                return@setOnCheckedChangeListener
            }
            restartPreview("Switching to ${if (isChecked) "10-bit HLG" else "8-bit SDR"}")
        }

        // Boost
        binding.switchBoost.setOnCheckedChangeListener { _, isChecked ->
            useBoost = isChecked
            binding.switchBoost.text = if (isChecked) getString(R.string.boost_on) else getString(R.string.boost_off)
            updatePreviewRequest()
        }

        // EIS / OIS - split control like Native Camera
        binding.switchEIS.setOnCheckedChangeListener { _, c -> useEIS = c; updatePreviewRequest() }
        binding.switchOIS.setOnCheckedChangeListener { _, c -> useOIS = c; updatePreviewRequest() }

        // Bitrate slider
        binding.sliderBitrate.addOnChangeListener { _, value, _ ->
            bitrateMbps = value.roundToInt()
            binding.tvBitrate.text = "$bitrateMbps Mbps"
        }

        // Manual controls
        binding.switchManual.setOnCheckedChangeListener { _, isChecked ->
            manualMode = isChecked
            binding.layoutManual.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            updatePreviewRequest()
        }
        binding.sliderIso.addOnChangeListener { _, value, _ ->
            manualIso = value.roundToInt()
            binding.tvIsoLabel.text = "ISO: $manualIso"
            if (manualMode) updatePreviewRequest()
        }
        binding.sliderShutter.addOnChangeListener { _, value, _ ->
            manualShutterIndex = value.roundToInt().coerceIn(0, shutterSpeeds.size - 1)
            binding.tvShutterLabel.text = "Shutter: ${shutterSpeeds[manualShutterIndex]}"
            if (manualMode) updatePreviewRequest()
        }
        binding.sliderFocus.addOnChangeListener { _, value, _ ->
            val v = value.roundToInt()
            binding.tvFocusLabel.text = if (v == 0) "Focus: Auto (Infinity)" else "Focus: Manual $v/10"
            if (manualMode) updatePreviewRequest()
        }

        // Record
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        // Help
        binding.btnHelp.setOnClickListener { showHelp() }

        // TextureView
        binding.textureView.surfaceTextureListener = surfaceListener

        // Resolution dropdowns - populated after camera enumeration
        binding.spinnerResolution.setOnItemClickListener { _, _, position, _ ->
            availableResolutions.getOrNull(position)?.let {
                currentResolution = it
                restartPreview("Resolution → ${it.width}x${it.height}")
            }
        }
        binding.spinnerFps.setOnItemClickListener { _, _, position, _ ->
            val fpsOptions = listOf(24, 30, 60)
            fpsOptions.getOrNull(position)?.let {
                currentFps = it
                restartPreview("FPS → $it")
            }
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.help_title))
            .setMessage(
                "8-BIT SDR (switch OFF):\n" +
                "• Ready to share anywhere. Looks great instantly.\n" +
                "• But sky blows white, less grading room — like stock but without sharpening.\n\n" +
                "10-BIT HLG (switch ON):\n" +
                "• Pro mode. Holds highlights + skin at same time (~1B colors).\n" +
                "• Looks WASHED OUT in gallery — that's normal, it's HDR!\n" +
                "• View on Pixel's HDR screen, or upload to Instagram Reels (HDR).\n" +
                "• For YouTube SDR: import to DaVinci Resolve → apply LUT or:\n" +
                "  Node 01 CST: Rec.2100 HLG → DWG Intermediate\n" +
                "  Node 02: YOUR GRADE\n" +
                "  Node 03 CST: DWG → Rec.709 Gamma 2.4\n\n" +
                "Free LUT: n8made.com/native-camera#lut (Pixel Tuned v1)\n\n" +
                "PIXEL 7 PRO TIPS:\n" +
                "• Handheld: EIS ON, OIS OFF (Theo's fix for jitter)\n" +
                "• Tripod: Both OFF\n" +
                "• Night: Boost ON + Manual ISO 800-1600\n" +
                "• Bitrate 80 Mbps = sweet spot (120 = huge files ~1GB/3min)"
            )
            .setPositiveButton("Got it") { d, _ -> d.dismiss() }
            .setNeutralButton("Download LUT info") { _, _ ->
                Toast.makeText(this, "Get LUT at n8made.com/native-camera#lut", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    // ---------------- PERMISSIONS & INIT ----------------
    private fun checkPermissionsAndInit() {
        val missing = REQUIRED_PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        } else {
            initCameraDiscovery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCameraDiscovery()
        } else {
            binding.tvStatus.text = "Permissions required: Camera + Mic. Please grant in Settings."
        }
    }

    @SuppressLint("MissingPermission")
    private fun initCameraDiscovery() {
        try {
            lenses.clear()
            // Enumerate cameras - Pixel 7 Pro has 3 physical + logical
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val lensName = when {
                    focalLengths != null && focalLengths.any { it < 3.0f } -> "Ultra-Wide"
                    focalLengths != null && focalLengths.any { it > 6.0f } -> "Tele 5x"
                    else -> "Wide"
                }
                // Only add physical distinct lenses - Pixel 7 Pro IDs are typically 0=Wide, 2=UW, 3=Tele
                // Avoid duplicates
                if (lenses.none { it.label == lensName }) {
                    lenses.add(Lens(lensName, id, focalLengths?.firstOrNull()?.toString() ?: "?"))
                }
            }
            // Fallback if enumeration weird
            if (lenses.isEmpty()) {
                lenses.add(Lens("Wide", "0", "6.81"))
            }
            // Sort: UW, Wide, Tele
            lenses.sortBy { when (it.label) { "Ultra-Wide" -> 0; "Wide" -> 1; else -> 2 } }
            currentLensId = lenses.find { it.label == "Wide" }?.cameraId ?: lenses.first().cameraId

            updateLensButtons()
            checkHLGSupportAndPopulateResolutions()
            binding.tvStatus.text = "Ready • ${lenses.size} lenses found • Tap REC to start\nFiles → DCIM/NativeCamera"
        } catch (e: Exception) {
            binding.tvStatus.text = "Error discovering cameras: ${e.message}"
        }
    }

    private fun updateLensButtons() {
        // Enable/disable based on availability
        val hasUW = lenses.any { it.label == "Ultra-Wide" }
        val hasTele = lenses.any { it.label == "Tele 5x" }
        binding.btnLensUW.isEnabled = hasUW
        binding.btnLensTele.isEnabled = hasTele
        binding.btnLensUW.alpha = if (hasUW) 1f else 0.4f
        binding.btnLensTele.alpha = if (hasTele) 1f else 0.4f
    }

    private fun switchLens(type: String) {
        val target = when (type) {
            "uw" -> lenses.find { it.label == "Ultra-Wide" }?.cameraId
            "tele" -> lenses.find { it.label == "Tele 5x" }?.cameraId
            else -> lenses.find { it.label == "Wide" }?.cameraId
        } ?: return
        if (target == currentLensId && captureSession != null) return
        currentLensId = target
        restartPreview("Switching lens → ${lenses.find { it.cameraId == target }?.label}")
    }

    private fun checkHLGSupportAndPopulateResolutions() {
        try {
            val chars = cameraManager.getCameraCharacteristics(currentLensId)
            // Check 10-bit support (Android 13+)
            isHLGSupportedOnDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isTenBitSupported(chars) && isHLGSupported(chars)
            } else false

            binding.tvHLGSupport.text = if (isHLGSupportedOnDevice) {
                "✓ 10-bit HLG supported on ${lenses.find { it.cameraId == currentLensId }?.label} (Android 13+)"
            } else {
                "✗ 10-bit HLG not available on this lens / Android <13 — 8-bit will be used (still better than stock)"
            }
            binding.switchHLG.isEnabled = isHLGSupportedOnDevice
            binding.switchHLG.alpha = if (isHLGSupportedOnDevice) 1f else 0.5f

            // Resolutions
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            // Filter to 16:9 common video sizes
            availableResolutions = sizes.filter { it.width >= 1280 }.sortedByDescending { it.width * it.height }.take(6)
            if (availableResolutions.isEmpty()) availableResolutions = listOf(Size(3840,2160), Size(1920,1080), Size(1280,720))
            if (!availableResolutions.contains(currentResolution)) currentResolution = availableResolutions.first()

            val resStrings = availableResolutions.map { "${it.width}x${it.height} ${if (it.width >= 3840) "4K" else if (it.width >= 1920) "FHD" else "HD"}" }
            binding.spinnerResolution.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resStrings))
            binding.spinnerResolution.setText(resStrings.firstOrNull { it.contains("${currentResolution.width}x") } ?: resStrings.first(), false)

            val fpsStrings = listOf("24 fps (cinema)", "30 fps (standard)", "60 fps (smooth)")
            binding.spinnerFps.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, fpsStrings))
            binding.spinnerFps.setText(fpsStrings[1], false)

        } catch (e: Exception) {
            binding.tvHLGSupport.text = "HLG check failed: ${e.message}"
        }
    }

    // Theo's HLG check - official Android docs
    @SuppressLint("NewApi")
    private fun isTenBitSupported(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
    }

    @SuppressLint("NewApi")
    private fun isHLGSupported(chars: CameraCharacteristics): Boolean {
        return try {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES) ?: return false
            profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)
        } catch (e: Exception) { false }
    }

    // ---------------- LIFECYCLE ----------------
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: Exception) {}
        backgroundThread = null; backgroundHandler = null
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.textureView.isAvailable) openCamera()
    }
    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun restartPreview(reason: String) {
        binding.tvStatus.text = reason
        closeCamera()
        // Re-check HLG for new lens
        checkHLGSupportAndPopulateResolutions()
        if (binding.textureView.isAvailable) openCamera() else binding.textureView.surfaceTextureListener = surfaceListener
    }

    // ---------------- TEXTURE LISTENER ----------------
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    // ---------------- OPEN CAMERA ----------------
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!hasPermissions()) return
        try {
            // Close previous
            closeCamera()
            val chars = cameraManager.getCameraCharacteristics(currentLensId)
            // Must configure MediaRecorder BEFORE opening session if we want to preview with correct profile
            // For preview-only, we just open; recording will reconfigure
            cameraManager.openCamera(currentLensId, stateCallback, backgroundHandler)
            binding.tvStatus.text = "Opening ${lenses.find { it.cameraId == currentLensId }?.label} • ${currentResolution.width}x${currentResolution.height} • ${if (isHLGEnabled) "HLG 10-bit" else "8-bit SDR"}"
        } catch (e: Exception) {
            Toast.makeText(this, "Open failed: ${e.message}", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "Open failed: ${e.message}"
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); cameraDevice = null
            binding.tvStatus.text = "Camera error: $error"
        }
    }

    private fun createPreviewSession() {
        try {
            val texture = binding.textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(currentResolution.width, currentResolution.height)
            val previewSurface = Surface(texture)

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(previewSurface)
            applyBypassSettings(previewRequestBuilder!!) // THEO'S BYPASS

            val surfaces = listOf(previewSurface)

            // If HLG is enabled and supported, we MUST use SessionConfiguration with profile
            if (isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val outputConfig = OutputConfiguration(previewSurface).apply {
                    setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                                binding.tvStatus.text = "Preview: 10-bit HLG • ${currentResolution.width}x${currentResolution.height} • Ready to REC"
                            } catch (e: Exception) { binding.tvStatus.text = "Preview failed: ${e.message}" }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { binding.tvStatus.text = "Configure failed (HLG)" }
                    }
                )
                cameraDevice!!.createCaptureSession(sessionConfig)
            } else {
                // 8-bit path - classic
                cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                            binding.tvStatus.text = "Preview: 8-bit SDR • ${currentResolution.width}x${currentResolution.height} • Ready to REC"
                        } catch (e: Exception) { binding.tvStatus.text = "Preview failed: ${e.message}" }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { binding.tvStatus.text = "Configure failed" }
                }, backgroundHandler)
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "Preview session error: ${e.message}"
        }
    }

    /**
     * THIS IS THE CORE - Theo's bypass.
     * Disables Google's aggressive processing that causes over-sharpening / waxy skin.
     */
    private fun applyBypassSettings(builder: CaptureRequest.Builder) {
        try {
            val chars = cameraManager.getCameraCharacteristics(currentLensId)

            // 1. BYPASS SHARPENING & NOISE REDUCTION (most visible improvement)
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) // <- disables over-sharpening
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            // Optional: keep OFF for clean image, or FAST if you prefer some NR at night
            // builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)

            // 2. COLOR & TONEMAP - let sensor breathe
            // We use FAST or HIGH_QUALITY depending - OFF would require manual matrix
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            // Don't force manual tonemap in preview - let HLG handle it. For 8-bit, GAMMA_VALUE keeps it natural.
            if (!isHLGEnabled) {
                // Keep tonemap natural for SDR
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            }

            // 3. SPLIT STABILIZATION - Theo's jitter fix
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (useEIS) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (useOIS) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )

            // 4. MANUAL vs AUTO
            if (manualMode) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(
                    chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower ?: 100,
                    chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 3200
                ))
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs[manualShutterIndex])
                // Manual focus: 0=auto, else map 1-10 to diopter
                val focusVal = binding.sliderFocus.value.roundToInt()
                if (focusVal == 0) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    // Approx mapping 0.0 (infinity) to 10.0 (close)
                    val focusDistance = focusVal / 10f * 5f // 0..5 diopters
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                }
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            // 5. BOOST ISO - Theo's bright viewfinder / night trick
            if (useBoost) {
                val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                if (boostRange != null) {
                    builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boostRange.upper)
                }
            }

            // 6. AE target FPS - lock to currentFps for smooth
            val fpsRange = Range(currentFps, currentFps)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

        } catch (e: Exception) {
            // Some devices throw if feature not supported - ignore gracefully
        }
    }

    private fun updatePreviewRequest() {
        try {
            previewRequestBuilder?.let {
                applyBypassSettings(it)
                captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
            }
        } catch (_: Exception) {}
    }

    // ---------------- RECORDING ----------------
    private fun hasPermissions() = REQUIRED_PERMS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun createMediaRecorder(): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "NC_${if (isHLGEnabled) "HLG" else "SDR"}_${currentResolution.width}x${currentResolution.height}_${currentFps}fps_${bitrateMbps}Mbps_$ts.mp4"

        // Save to DCIM/NativeCamera for gallery visibility
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "NativeCamera")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)

        mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(file.absolutePath)
        mr.setVideoEncodingBitRate(bitrateMbps * 1_000_000) // Theo's high bitrate
        mr.setVideoFrameRate(currentFps)
        mr.setVideoSize(currentResolution.width, currentResolution.height)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioSamplingRate(48000)
        mr.setAudioEncodingBitRate(128000)

        if (isHLGEnabled && isHLGSupportedOnDevice) {
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            // Note: MediaRecorder doesn't expose profile/level easily; HEVC Main10 is inferred from HLG session
            // For true Main10, you would use MediaCodec + MediaMuxer manually. This is the simple path that still writes HLG correctly on Pixel.
        } else {
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        }
        // Orientation - Pixel 7 Pro sensor 90 deg
        mr.setOrientationHint(90)

        // Store file path for MediaStore scan
        mr.setOnInfoListener { _, _, _ -> }
        // Keep for later scan
        lastFile = file
        pendingFilename = filename

        return mr
    }

    private var lastFile: File? = null
    private var pendingFilename: String? = null

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        try {
            closePreviewSessionOnly()
            mediaRecorder = createMediaRecorder()
            mediaRecorder!!.prepare()

            val texture = binding.textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(currentResolution.width, currentResolution.height)
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface

            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface)
            builder.addTarget(recorderSurface)
            applyBypassSettings(builder)

            val surfaces = listOf(previewSurface, recorderSurface)

            // Create session with BOTH surfaces - with HLG profile if needed (must match preview profile)
            if (isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val previewConfig = OutputConfiguration(previewSurface).apply { setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val recordConfig = OutputConfiguration(recorderSurface).apply { setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewConfig, recordConfig),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            previewRequestBuilder = builder
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                mediaRecorder!!.start()
                                onRecordingStarted()
                            } catch (e: Exception) { binding.tvStatus.text = "Record start failed: ${e.message}" }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { binding.tvStatus.text = "Record configure failed (HLG)" }
                    }
                )
                cameraDevice!!.createCaptureSession(sessionConfig)
            } else {
                cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        previewRequestBuilder = builder
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            mediaRecorder!!.start()
                            onRecordingStarted()
                        } catch (e: Exception) { binding.tvStatus.text = "Record start failed: ${e.message}" }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { binding.tvStatus.text = "Record configure failed" }
                }, backgroundHandler)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Start failed: ${e.message}", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "Start failed: ${e.message}"
            releaseMediaRecorder()
            // Try to restore preview
            createPreviewSession()
        }
    }

    private fun onRecordingStarted() {
        isRecording = true
        recordingStartMs = System.currentTimeMillis()
        binding.btnRecord.text = getString(R.string.stop)
        binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.tvRecIndicator.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "🔴 Recording ${if (isHLGEnabled) "10-bit HLG" else "8-bit SDR"} • ${currentResolution.width}x${currentResolution.height} • $bitrateMbps Mbps"
        binding.switchHLG.isEnabled = false
        timerHandler.post(timerRunnable)
        // Dim controls
        binding.bottomControls.alpha = 0.6f
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            // Stop must be on same thread
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
            try { mediaRecorder?.stop() } catch (e: Exception) { Toast.makeText(this, "Stop: ${e.message}", Toast.LENGTH_SHORT).show() }
            releaseMediaRecorder()
            isRecording = false
            binding.btnRecord.text = getString(R.string.record)
            binding.btnRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_rec)
            binding.tvRecIndicator.visibility = android.view.View.GONE
            binding.switchHLG.isEnabled = isHLGSupportedOnDevice
            binding.bottomControls.alpha = 1f
            timerHandler.removeCallbacks(timerRunnable)
            galleryScan()
            Toast.makeText(this, "Saved: ${pendingFilename}\nDCIM/NativeCamera", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "Saved ✓ ${pendingFilename} • ${if (isHLGEnabled) "HLG - grade for SDR" else "SDR ready to share"}"
        } catch (e: Exception) {
            isRecording = false
            releaseMediaRecorder()
            Toast.makeText(this, "Stop error: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            // Re-create preview session
            closePreviewSessionOnly()
            createPreviewSession()
        }
    }

    private fun galleryScan() {
        try {
            lastFile?.let { file ->
                // MediaStore scan so it appears in Photos immediately (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/NativeCamera")
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    // File already written to DCIM, MediaScanner will pick it up; this just triggers scan
                    sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file)))
                } else {
                    sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file)))
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseMediaRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun closePreviewSessionOnly() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        releaseMediaRecorder()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
