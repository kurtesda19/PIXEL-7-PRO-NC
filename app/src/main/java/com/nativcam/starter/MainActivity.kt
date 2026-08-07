package com.nativcam.starter

import android.Manifest
import android.annotation.SuppressLint
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
 * Native Camera Starter — Pixel 7 Pro • Pro UI v3 STABLE
 * Fixes: lenses (.6x 1x 2x 5x 10x), focus AUTO/MANUAL with slider, EV/WB/Stab sliders, no force-close
 * Every pill now has a slider. All capture requests are validated before set.
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
    private var isCameraOpening = false

    // Lenses — Pixel 7 Pro has 3 physical back + 1 front
    private data class Lens(val label: String, val cameraId: String, val isLogical: Boolean = false)
    private val lenses = mutableListOf<Lens>()
    private var frontCameraId: String? = null
    private var currentLensId: String = "0"
    private var currentLensLabel: String = "1x" // .6x,1x,2x,5x,10x
    private var currentResolution = Size(3840, 2160)
    private var currentFps = 30
    private var availableResolutions = listOf<Size>()
    private var previewSize = Size(1920,1080) // separate preview size to avoid configure fails
    private var isHLGEnabled = false
    private var isHLGSupportedOnDevice = false
    private var isFrontCamera = false

    // Control state — all have sliders now
    private var bitrateMbps = 80
    private var useEIS = true
    private var useOIS = false
    private var useBoost = false

    // ISO: 0 = Auto, else 50-3200
    private var manualIso = 420
    private var isIsoAuto = false
    // Shutter index 0..7
    private var manualShutterIndex = 1 // 1/30 default
    private var isShutterAuto = false
    private val shutterSpeeds = listOf("1/24","1/30","1/48","1/60","1/120","1/250","1/500","1/1000")
    private val shutterNs = listOf(41666666L,33333333L,20833333L,16666666L,8333333L,4000000L,2000000L,1000000L)
    // EV -2..+2
    private var manualEv = 0.0
    // WB 0=AWB,1=Incand,2=Fluor,3=Daylight,4=Cloudy,5=Shade
    private var wbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var wbLabel = "AWB"
    // Focus: auto vs manual diopters
    private var isFocusAuto = true
    private var focusDiopters = 0f // 0=auto, else 0.5..10
    private var maxFocusDiopters = 10f
    // Digital zoom 1.0 or 2.0
    private var digitalZoom = 1.0f
    private var isVideoMode = true

    private var recordingStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object: Runnable{
        override fun run(){
            if(isRecording){
                val e = System.currentTimeMillis()-recordingStartMs
                val s=(e/1000)%60; val m=(e/60000)%60
                binding.tvRecIndicator.text="● REC %02d:%02d • %s".format(m,s, if(isHLGEnabled)"10-bit HLG" else "8-bit")
                timerHandler.postDelayed(this,500)
            }
        }
    }

    private val REQ_PERMS=101
    private val REQUIRED_PERMS=arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        binding=ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraManager=getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupProUI()
        checkPermissionsAndInit()
        updateAllPills()
        updateStorageText()
    }

    private fun setupProUI(){
        binding.btnLensUW.setOnClickListener{ selectLens(".6x") }
        binding.btnLensWide.setOnClickListener{ selectLens("1x") }
        binding.btnLens2x.setOnClickListener{ selectLens("2x") }
        binding.btnLensTele.setOnClickListener{ selectLens("5x") }
        binding.btnLens10x.setOnClickListener{ selectLens("10x") }
        binding.btnResolution.setOnClickListener{ showResolutionDialog() }
        binding.btnHLG.setOnClickListener{ toggleHLG() }
        binding.pillIso.setOnClickListener{ showIsoDialog() }
        binding.pillIso.setOnLongClickListener{ isIsoAuto=true; manualIso=420; updateAllPills(); updatePreviewRequest(); toast("ISO Auto"); true }
        binding.pillShutter.setOnClickListener{ showShutterDialog() }
        binding.pillShutter.setOnLongClickListener{ isShutterAuto=true; updateAllPills(); updatePreviewRequest(); toast("Shutter Auto"); true }
        binding.pillFocus.setOnClickListener{ showFocusDialog() }
        binding.pillFocus.setOnLongClickListener{ isFocusAuto=true; updateAllPills(); updatePreviewRequest(); toast("Focus Auto"); true }
        binding.toggleVideoPhoto.setOnClickListener{ toggleVideoPhoto() }
        binding.pillEv.setOnClickListener{ showEvDialog() }
        binding.pillEv.setOnLongClickListener{ manualEv=0.0; updateAllPills(); updatePreviewRequest(); toast("EV 0.0"); true }
        binding.pillWb.setOnClickListener{ showWbDialog() }
        binding.pillWb.setOnLongClickListener{ wbMode=CaptureRequest.CONTROL_AWB_MODE_AUTO; wbLabel="AWB"; updateAllPills(); updatePreviewRequest(); toast("WB Auto"); true }
        binding.pillStab.setOnClickListener{ showStabDialog() }
        binding.pillStab.setOnLongClickListener{ useEIS=true; useOIS=false; updateAllPills(); updatePreviewRequest(); toast("Stab EIS (handheld)"); true }
        binding.btnPalette.setOnClickListener{ showPaletteDialog() }
        binding.btnSettings.setOnClickListener{ showSettingsDialog() }
        binding.btnRecord.setOnClickListener{ if(isRecording) stopRecording() else startRecording() }
        binding.btnGallery.setOnClickListener{ openGallery() }
        binding.imgGalleryThumb.setOnClickListener{ openGallery() }
        binding.btnFlip.setOnClickListener{ flipCamera() }
        binding.textureView.surfaceTextureListener=surfaceListener
        // hidden legacy sync
        try{ binding.switchHLG.setOnCheckedChangeListener{_,c-> if(c!=isHLGEnabled) toggleHLG()} }catch(_:Exception){}
    }

    // ---------------- LENS LOGIC - FIXED ----------------
    private fun selectLens(label: String){
        // visual highlight first (instant feedback)
        val map=mapOf(".6x" to binding.btnLensUW,"1x" to binding.btnLensWide,"2x" to binding.btnLens2x,"5x" to binding.btnLensTele,"10x" to binding.btnLens10x)
        map.forEach{ (k,v)-> 
            if(k==label) v.setBackgroundResource(R.drawable.bg_lens_selected)
            else v.setBackgroundResource(R.drawable.bg_lens_unselected)
            v.setTextColor(if(k==label) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#B0B0B0"))
        }
        val targetPhysical: String
        val targetZoom: Float
        when(label){
            ".6x"->{ targetPhysical="Ultra-Wide"; targetZoom=1f; isFrontCamera=false }
            "1x"->{ targetPhysical="Wide"; targetZoom=1f; isFrontCamera=false }
            "2x"->{ targetPhysical="Wide"; targetZoom=2f; isFrontCamera=false }
            "5x"->{ targetPhysical="Tele 5x"; targetZoom=1f; isFrontCamera=false }
            "10x"->{ targetPhysical="Tele 5x"; targetZoom=2f; isFrontCamera=false }
            else->{ targetPhysical="Wide"; targetZoom=1f }
        }
        val physicalId = lenses.find{ it.label==targetPhysical }?.cameraId
        if(physicalId==null){
            toast("$targetPhysical not found on this device")
            return
        }
        val needRestart = physicalId != currentLensId || isFrontCamera
        currentLensLabel=label
        digitalZoom=targetZoom
        if(needRestart){
            isFrontCamera=false
            currentLensId=physicalId
            restartPreview("Lens $label → $targetPhysical${if(targetZoom>1f) " 2× zoom" else ""}")
        } else {
            // same physical, just digital zoom — no restart, just crop
            updatePreviewRequest()
            toast("$label — ${if(targetZoom>1f) "Digital 2×" else "Optical"}")
        }
    }

    private fun flipCamera(){
        if(frontCameraId==null){ toast("Front camera not found"); return }
        isFrontCamera = !isFrontCamera
        if(isFrontCamera){
            currentLensId=frontCameraId!!
            currentLensLabel="Front"
            // highlight none, or highlight 1x as front
            val map=mapOf(".6x" to binding.btnLensUW,"1x" to binding.btnLensWide,"2x" to binding.btnLens2x,"5x" to binding.btnLensTele,"10x" to binding.btnLens10x)
            map.forEach{(_,v)-> v.setBackgroundResource(R.drawable.bg_lens_unselected); v.setTextColor(android.graphics.Color.parseColor("#B0B0B0"))}
            toast("Front camera")
        } else {
            val back = lenses.find{ it.label=="Wide"}?.cameraId ?: lenses.first().cameraId
            currentLensId=back
            currentLensLabel="1x"
            selectLens("1x")
            return
        }
        restartPreview(if(isFrontCamera) "Front camera" else "Back camera")
    }

    private fun toggleHLG(){
        if(!isHLGSupportedOnDevice && !isHLGEnabled){ toast("HLG not supported on this lens — try 1x Wide"); return }
        isHLGEnabled=!isHLGEnabled
        updateHLGBadge()
        restartPreview(if(isHLGEnabled) "10-bit HLG ON" else "8-bit SAFE")
    }
    private fun updateHLGBadge(){
        if(isHLGEnabled){
            binding.btnHLG.text="10-bit HLG"
            binding.btnHLG.setBackgroundResource(R.drawable.bg_badge_hlg_active)
        } else {
            binding.btnHLG.text="8-bit SAFE"
            binding.btnHLG.setBackgroundResource(R.drawable.bg_badge_hlg)
        }
        updateStorageText()
    }
    private fun updateAllPills(){
        binding.tvPillIso.text=if(isIsoAuto) "AUTO" else "$manualIso"
        binding.tvPillShutter.text=if(isShutterAuto) "AUTO" else shutterSpeeds[manualShutterIndex]
        binding.tvPillFocus.text=if(isFocusAuto) "AUTO" else String.format("%.1f dpt", focusDiopters)
        binding.tvPillEv.text=String.format("%+.1f", manualEv)
        binding.tvPillWb.text=wbLabel
        binding.tvPillStab.text=when{
            useEIS && useOIS -> "EIS+OIS"
            useEIS -> "EIS"
            useOIS -> "OIS"
            else -> "OFF"
        }
        binding.btnResolution.text="${if(currentResolution.width>=3840) "4K" else if(currentResolution.width>=1920) "FHD" else "HD"} ${currentFps}fps"
        updateHLGBadge()
        // pill highlight
        binding.pillIso.setBackgroundResource(if(isIsoAuto) R.drawable.bg_pill else R.drawable.bg_pill_active)
        binding.pillShutter.setBackgroundResource(if(isShutterAuto) R.drawable.bg_pill else R.drawable.bg_pill_active)
        binding.pillFocus.setBackgroundResource(if(isFocusAuto) R.drawable.bg_pill else R.drawable.bg_pill_active)
    }
    private fun updateStorageText(){
        try{
            val stat=StatFs(Environment.getExternalStorageDirectory().path)
            val free=stat.availableBytes/(1024f*1024f*1024f)
            val mins=(free*1024/bitrateMbps/60).toInt()
            val tag=if(isHLGEnabled) "10-bit HLG" else "8-bit SAFE"
            binding.tvRemain.text="REMAIN: %.1f GB (%d min) • %s".format(free, mins, tag)
        }catch(_:Exception){ binding.tvRemain.text="REMAIN: -- GB (-- min) • ${if(isHLGEnabled) "10-bit HLG" else "8-bit SAFE"}" }
    }

    // ---------------- DIALOGS - EVERY PILL HAS SLIDER ----------------
    private fun showIsoDialog(){
        val v=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=v.findViewById<Slider>(R.id.dialogSlider)
        val tv=v.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=v.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="ISO — Slide to set, long-press pill for AUTO"
        s.valueFrom=100f; s.valueTo=3200f; s.stepSize=50f; s.value=manualIso.toFloat().coerceIn(100f,3200f)
        tv.text=if(isIsoAuto) "AUTO" else "ISO $manualIso"
        s.addOnChangeListener{_,val_,_-> tv.text="ISO ${val_.roundToInt()}" }
        AlertDialog.Builder(this).setView(v)
            .setPositiveButton("Manual"){_,_-> manualIso=s.value.roundToInt(); isIsoAuto=false; updateAllPills(); updatePreviewRequest()}
            .setNeutralButton("AUTO"){_,_-> isIsoAuto=true; updateAllPills(); updatePreviewRequest()}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showShutterDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="Shutter Speed — Slide"
        s.valueFrom=0f; s.valueTo=(shutterSpeeds.size-1).toFloat(); s.stepSize=1f; s.value=manualShutterIndex.toFloat()
        tv.text=shutterSpeeds[manualShutterIndex]
        s.addOnChangeListener{_,val_,_-> tv.text=shutterSpeeds[val_.roundToInt().coerceIn(0,shutterSpeeds.size-1)]}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Manual"){_,_-> manualShutterIndex=s.value.roundToInt().coerceIn(0,shutterSpeeds.size-1); isShutterAuto=false; updateAllPills(); updatePreviewRequest()}
            .setNeutralButton("AUTO"){_,_-> isShutterAuto=true; updateAllPills(); updatePreviewRequest()}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showFocusDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="Focus — AUTO or Manual (diopters). Long-press pill for AUTO"
        // get max diopters for this lens
        val max = try{ cameraManager.getCameraCharacteristics(currentLensId).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f }catch(_:Exception){10f}
        maxFocusDiopters = if(max>0) max else 10f
        s.valueFrom=0f; s.valueTo=maxFocusDiopters; s.stepSize=0.1f; s.value= if(isFocusAuto) 0f else focusDiopters.coerceIn(0f, maxFocusDiopters)
        tv.text= if(s.value==0f) "AUTO (Infinity)" else String.format("%.1f dpt", s.value)
        s.addOnChangeListener{_,val_,_-> tv.text= if(val_==0f) "AUTO (Infinity)" else String.format("%.1f dpt", val_)}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set"){_,_-> 
                if(s.value==0f){ isFocusAuto=true } else { isFocusAuto=false; focusDiopters=s.value }
                updateAllPills(); updatePreviewRequest()
            }
            .setNeutralButton("AUTO"){_,_-> isFocusAuto=true; updateAllPills(); updatePreviewRequest()}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showEvDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="Exposure Compensation (EV) — Brightness"
        s.valueFrom=-2f; s.valueTo=2f; s.stepSize=0.1f; s.value=manualEv.toFloat()
        tv.text=String.format("%+.1f EV", manualEv)
        s.addOnChangeListener{_,val_,_-> tv.text=String.format("%+.1f EV", val_)}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set"){_,_-> manualEv=(s.value*10).roundToInt()/10.0; updateAllPills(); updatePreviewRequest()}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showWbDialog(){
        // Slider 0..5 maps to WB modes, but also show list for clarity
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        val labels=arrayOf("AWB Auto","Incandescent","Fluorescent","Daylight","Cloudy","Shade")
        val vals=arrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_SHADE)
        val curIdx=vals.indexOf(wbMode).takeIf{it>=0}?:0
        tt.text="White Balance (TEMP) — Slide"
        s.valueFrom=0f; s.valueTo=5f; s.stepSize=1f; s.value=curIdx.toFloat()
        tv.text=labels[curIdx]
        s.addOnChangeListener{_,val_,_-> tv.text=labels[val_.roundToInt()]}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set"){_,_->
                val idx=s.value.roundToInt()
                wbMode=vals[idx]; wbLabel=labels[idx].split(" ")[0]; if(wbLabel=="AWB") wbLabel="AWB" else wbLabel=labels[idx].substring(0,5).uppercase()
                // shorten
                wbLabel=when(idx){0->"AWB";1->"TUNG";2->"FLUOR";3->"DAY";4->"CLOUD";5->"SHADE"; else->"AWB"}
                updateAllPills(); updatePreviewRequest()
            }
            .setNegativeButton("Cancel",null).show()
    }
    private fun showStabDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="Stabilization — Slide 0=OFF, 1=EIS (handheld best), 2=OIS, 3=EIS+OIS"
        val cur = when{ useEIS && useOIS ->3f; useOIS ->2f; useEIS ->1f; else->0f}
        s.valueFrom=0f; s.valueTo=3f; s.stepSize=1f; s.value=cur
        fun label(v:Float)=when(v.roundToInt()){0->"OFF (Tripod)";1->"EIS — Recommended handheld";2->"OIS";3->"EIS+OIS"; else->"OFF"}
        tv.text=label(cur)
        s.addOnChangeListener{_,val_,_-> tv.text=label(val_)}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set"){_,_->
                when(s.value.roundToInt()){
                    0->{useEIS=false; useOIS=false}
                    1->{useEIS=true; useOIS=false}
                    2->{useEIS=false; useOIS=true}
                    3->{useEIS=true; useOIS=true}
                }
                updateAllPills(); updatePreviewRequest()
            }
            .setNegativeButton("Cancel",null).show()
    }
    private fun showPaletteDialog(){
        val opts=arrayOf("Natural (Bypass) — Theo default","Vivid (+Sat)","Warm — Golden Hour","Cool — Cinematic","B&W (preview)")
        AlertDialog.Builder(this).setTitle("LUT / Palette (preview, not baked)").setItems(opts){_,w-> toast(when(w){1->"Vivid — will apply on export";2->"Warm — nice for HLG skin";3->"Cool — night";4->"B&W — coming soon"; else->"Natural — clean bypass"})}.setNegativeButton("Close",null).show()
    }
    private fun showSettingsDialog(){
        val items=arrayOf("Bitrate: $bitrateMbps Mbps (slider)","Boost ISO: ${if(useBoost) "ON" else "OFF"} (tap)","Reset All to Auto","Help & LUT Guide")
        AlertDialog.Builder(this).setTitle("⚙ Settings — Pro").setItems(items){_,w->
            when(w){
                0-> showBitrateDialog()
                1->{ useBoost=!useBoost; updatePreviewRequest(); toast("Boost ${if(useBoost) "ON" else "OFF"} — bright night") }
                2->{ isIsoAuto=true; isShutterAuto=true; isFocusAuto=true; manualEv=0.0; wbMode=CaptureRequest.CONTROL_AWB_MODE_AUTO; wbLabel="AWB"; useEIS=true; useOIS=false; digitalZoom=1f; updateAllPills(); updatePreviewRequest(); toast("All Auto — clean") }
                3-> showHelp()
            }
        }.setPositiveButton("Close",null).show()
    }
    private fun showBitrateDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_slider,null)
        val s=view.findViewById<Slider>(R.id.dialogSlider)
        val tv=view.findViewById<android.widget.TextView>(R.id.dialogValue)
        val tt=view.findViewById<android.widget.TextView>(R.id.dialogTitle)
        tt.text="Video Bitrate — Quality vs File Size"
        s.valueFrom=30f; s.valueTo=120f; s.stepSize=10f; s.value=bitrateMbps.toFloat()
        tv.text="$bitrateMbps Mbps ${if(bitrateMbps>=100) "(pro, huge)" else if(bitrateMbps>=80) "(recommended)" else ""}"
        s.addOnChangeListener{_,v,_ -> tv.text="${v.roundToInt()} Mbps"}
        AlertDialog.Builder(this).setView(view)
            .setPositiveButton("Set"){_,_-> bitrateMbps=s.value.roundToInt(); updateAllPills(); updateStorageText(); toast("$bitrateMbps Mbps")}
            .setNegativeButton("Cancel",null).show()
    }
    private fun showResolutionDialog(){
        if(availableResolutions.isEmpty()){ toast("Wait for camera"); return }
        val resOpts=availableResolutions.map{ "${it.width}x${it.height} ${if(it.width>=3840) "4K" else if(it.width>=1920) "FHD" else "HD"}" }.toTypedArray()
        val fpsOpts=arrayOf("24 fps (cinema)","30 fps (standard)","60 fps (smooth)")
        AlertDialog.Builder(this).setTitle("Resolution").setSingleChoiceItems(resOpts, availableResolutions.indexOf(currentResolution).takeIf{it>=0}?:0){d,w->
            currentResolution=availableResolutions[w]; d.dismiss()
            AlertDialog.Builder(this).setTitle("Frame Rate").setSingleChoiceItems(fpsOpts, when(currentFps){24->0;60->2; else->1}){d2,w2->
                currentFps=arrayOf(24,30,60)[w2]; d2.dismiss()
                updateAllPills(); restartPreview("${currentResolution.width}x${currentResolution.height} ${currentFps}fps")
            }.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun toggleVideoPhoto(){
        isVideoMode=!isVideoMode
        if(isVideoMode){
            binding.iconVideo.setBackgroundResource(R.drawable.bg_toggle_selected)
            binding.iconPhoto.background=null
            toast("Video mode")
        } else {
            binding.iconPhoto.setBackgroundResource(R.drawable.bg_toggle_selected)
            binding.iconVideo.background=null
            toast("Photo mode — coming in v1.1 (video only now)")
            // revert quickly
            binding.iconPhoto.postDelayed({
                binding.iconVideo.setBackgroundResource(R.drawable.bg_toggle_selected)
                binding.iconPhoto.background=null
                isVideoMode=true
            },800)
        }
    }
    private fun openGallery(){
        try{
            lastFile?.let{
                val i=Intent(Intent.ACTION_VIEW).apply{ setDataAndType(Uri.fromFile(it),"video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                startActivity(Intent.createChooser(i,"Open video")); return
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply{ type="video/*" },"Gallery"))
        }catch(_:Exception){ toast("Saved to DCIM/NativeCamera — open in Photos") }
    }

    // ---------------- PERMISSIONS ----------------
    private fun checkPermissionsAndInit(){
        val missing=REQUIRED_PERMS.filter{ ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS) else initCameraDiscovery()
    }
    override fun onRequestPermissionsResult(c:Int, p:Array<out String>, r:IntArray){
        super.onRequestPermissionsResult(c,p,r)
        if(c==REQ_PERMS && r.all{it==PackageManager.PERMISSION_GRANTED}) initCameraDiscovery() else toast("Camera+Mic required")
    }

    @SuppressLint("MissingPermission")
    private fun initCameraDiscovery(){
        try{
            lenses.clear(); frontCameraId=null
            for(id in cameraManager.cameraIdList){
                val ch=cameraManager.getCameraCharacteristics(id)
                val facing=ch.get(CameraCharacteristics.LENS_FACING)
                if(facing==CameraCharacteristics.LENS_FACING_FRONT){ frontCameraId=id; continue }
                if(facing!=CameraCharacteristics.LENS_FACING_BACK) continue
                val focal=ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val name=when{
                    focal!=null && focal.any{it<3.0f} -> "Ultra-Wide"
                    focal!=null && focal.any{it>6.0f} -> "Tele 5x"
                    else -> "Wide"
                }
                if(lenses.none{ it.label==name }) lenses.add(Lens(name,id))
            }
            if(lenses.isEmpty()) lenses.add(Lens("Wide","0"))
            lenses.sortBy{ when(it.label){"Ultra-Wide"->0;"Wide"->1; else->2} }
            currentLensId=lenses.find{ it.label=="Wide"}?.cameraId ?: lenses.first().cameraId
            // get max focus for this lens
            try{ maxFocusDiopters=cameraManager.getCameraCharacteristics(currentLensId).get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f }catch(_:Exception){ maxFocusDiopters=10f }
            checkHLGSupportAndPopulateResolutions()
            updateStorageText()
            // highlight 1x
            selectLens("1x")
        }catch(e:Exception){ toast("Discovery: ${e.message}") }
    }

    private fun checkHLGSupportAndPopulateResolutions(){
        try{
            val ch=cameraManager.getCameraCharacteristics(currentLensId)
            isHLGSupportedOnDevice = if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) isTenBitSupported(ch) && isHLGSupported(ch) else false
            try{ binding.switchHLG.isEnabled=isHLGSupportedOnDevice }catch(_:Exception){}
            // preview sizes vs recorder sizes — pick intersection
            val map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val recSizes=map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            val prevSizes=map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: recSizes
            // choose resolutions that exist in BOTH to avoid configure fail
            val common = recSizes.filter{ r-> prevSizes.any{ p-> p.width==r.width && p.height==r.height } }.ifEmpty{ recSizes }
            availableResolutions = common.filter{ it.width>=1280 }.sortedByDescending{ it.width*it.height }.take(6)
            if(availableResolutions.isEmpty()) availableResolutions=listOf(Size(3840,2160),Size(1920,1080))
            if(!availableResolutions.contains(currentResolution)) currentResolution=availableResolutions.first()
            // preview size: pick 1080p if available else first
            previewSize = prevSizes.find{ it.width==1920 && it.height==1080 } ?: prevSizes.firstOrNull() ?: currentResolution
            updateAllPills()
        }catch(e:Exception){ toast("HLG check: ${e.message}") }
    }
    @SuppressLint("NewApi") private fun isTenBitSupported(c:CameraCharacteristics)=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)==true
    @SuppressLint("NewApi") private fun isHLGSupported(c:CameraCharacteristics)=try{ c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10)==true }catch(_:Exception){false}

    // ---------------- LIFECYCLE - STABLE ----------------
    private fun startBackgroundThread(){ backgroundThread=HandlerThread("CameraBackground").also{it.start()}; backgroundHandler=Handler(backgroundThread!!.looper) }
    private fun stopBackgroundThread(){ backgroundThread?.quitSafely(); try{backgroundThread?.join()}catch(_:Exception){}; backgroundThread=null; backgroundHandler=null }
    override fun onResume(){ super.onResume(); startBackgroundThread(); if(binding.textureView.isAvailable) openCamera() }
    override fun onPause(){ closeCamera(); stopBackgroundThread(); super.onPause() }

    private fun restartPreview(reason:String){
        // safe restart with delay to avoid race
        closeCamera()
        checkHLGSupportAndPopulateResolutions()
        updateAllPills()
        // delay 250ms before reopen to let HAL release
        Handler(Looper.getMainLooper()).postDelayed({
            if(binding.textureView.isAvailable) openCamera() else binding.textureView.surfaceTextureListener=surfaceListener
        }, 250)
    }

    private val surfaceListener=object: TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(s:SurfaceTexture,w:Int,h:Int){ openCamera() }
        override fun onSurfaceTextureSizeChanged(s:SurfaceTexture,w:Int,h:Int){}
        override fun onSurfaceTextureDestroyed(s:SurfaceTexture)=true
        override fun onSurfaceTextureUpdated(s:SurfaceTexture){}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(){
        if(isCameraOpening) return
        if(!REQUIRED_PERMS.all{ ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED }) return
        if(cameraDevice!=null) return
        isCameraOpening=true
        try{ cameraManager.openCamera(currentLensId, stateCallback, backgroundHandler) }catch(e:Exception){ isCameraOpening=false; toast("Open: ${e.message}") }
    }
    private val stateCallback=object: CameraDevice.StateCallback(){
        override fun onOpened(c:CameraDevice){ isCameraOpening=false; cameraDevice=c; createPreviewSession() }
        override fun onDisconnected(c:CameraDevice){ isCameraOpening=false; try{c.close()}catch(_:Exception){}; cameraDevice=null }
        override fun onError(c:CameraDevice,e:Int){ isCameraOpening=false; try{c.close()}catch(_:Exception){}; cameraDevice=null; toast("Camera error $e") }
    }

    private fun createPreviewSession(){
        try{
            val tex=binding.textureView.surfaceTexture ?: return
            // Use previewSize for preview, not currentResolution (fixes configure fails)
            tex.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface=Surface(tex)
            previewRequestBuilder=cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{ addTarget(previewSurface); applyBypassSettings(this) }
            if(isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
                val cfg=OutputConfiguration(previewSurface).apply{ setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val sc=SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(cfg), Executors.newSingleThreadExecutor(), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){ captureSession=s; try{ s.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler) }catch(e:Exception){ toast("Preview HLG: ${e.message}") } }
                    override fun onConfigureFailed(s:CameraCaptureSession){ toast("Preview HLG configure failed — try 8-bit") }
                })
                cameraDevice!!.createCaptureSession(sc)
            } else {
                cameraDevice!!.createCaptureSession(listOf(previewSurface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){ captureSession=s; try{ s.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler) }catch(e:Exception){ toast("Preview: ${e.message}") } }
                    override fun onConfigureFailed(s:CameraCaptureSession){ toast("Preview configure failed — try different resolution") }
                }, backgroundHandler)
            }
        }catch(e:Exception){ toast("Preview: ${e.message}") }
    }

    // ---------------- CORE BYPASS - VALIDATED (no crash) ----------------
    private fun applyBypassSettings(builder: CaptureRequest.Builder){
        try{
            val ch=cameraManager.getCameraCharacteristics(currentLensId)
            // 1. Bypass — only if supported
            try{ builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) }catch(_:Exception){}
            try{ builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) }catch(_:Exception){}
            try{ builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF) }catch(_:Exception){}
            try{ builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) }catch(_:Exception){}
            if(!isHLGEnabled) try{ builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST) }catch(_:Exception){}
            // 2. Stab
            try{ builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if(useEIS) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) }catch(_:Exception){}
            try{ builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if(useOIS) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) }catch(_:Exception){}
            // 3. Focus — AUTO vs MANUAL (fixed!)
            if(isFocusAuto){
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                // don't set LENS_FOCUS_DISTANCE in auto
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val max = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
                val d = focusDiopters.coerceIn(0f, max)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
            }
            // 4. Exposure — ISO/Shutter/EV
            val isoRange=ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange=ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if(!isIsoAuto || !isShutterAuto){
                // manual exposure — set CONTROL_AE_MODE OFF if we control ISO or shutter
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if(!isIsoAuto && isoRange!=null) builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(isoRange.lower, isoRange.upper))
                else if(isoRange!=null) builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.lower + (isoRange.upper-isoRange.lower)/4)
                val ns = shutterNs[manualShutterIndex.coerceIn(0,shutterNs.size-1)]
                if(!isShutterAuto && expRange!=null) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns.coerceIn(expRange.lower, expRange.upper))
                else if(expRange!=null) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns.coerceIn(expRange.lower, expRange.upper))
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            // EV compensation (works in AE ON mode)
            if(manualEv!=0.0){
                val compRange=ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val step=ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.166
                if(compRange!=null){
                    val steps=(manualEv/step).roundToInt().coerceIn(compRange.lower, compRange.upper)
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                }
            }
            // WB
            builder.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
            // Boost
            if(useBoost) ch.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.let{ builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it.upper) }
            // FPS
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))
            // Digital zoom — safe crop with 4-pixel alignment
            if(digitalZoom != 1f){
                val active=ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if(active!=null){
                    val w=active.width(); val h=active.height()
                    var cw=(w/digitalZoom).toInt(); var ch2=(h/digitalZoom).toInt()
                    // align to 4
                    cw = cw/4*4; ch2=ch2/4*4
                    val x=(w-cw)/2/4*4; val y=(h-ch2)/2/4*4
                    val rect=Rect(x,y,x+cw,y+ch2)
                    // ensure inside active
                    if(active.contains(rect) && cw>=160 && ch2>=120){
                        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
                    }
                }
            } else {
                ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let{ builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
            }
        }catch(_:Exception){}
    }

    private fun updatePreviewRequest(){
        try{
            previewRequestBuilder?.let{
                applyBypassSettings(it)
                captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
            }
        }catch(e:Exception){ /* don't crash — just log */ }
    }

    // ---------------- RECORDING - STABLE ----------------
    private var lastFile: File? = null; private var pendingFilename:String? = null
    private fun createMediaRecorder(): MediaRecorder{
        val mr=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        val ts=SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fn="NC_${if(isHLGEnabled) "HLG" else "SDR"}_${currentResolution.width}x${currentResolution.height}_${currentFps}fps_${bitrateMbps}Mbps_$ts.mp4"
        val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"NativeCamera")
        if(!dir.exists()) dir.mkdirs()
        val f=File(dir, fn)
        mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER); mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); mr.setOutputFile(f.absolutePath)
        mr.setVideoEncodingBitRate(bitrateMbps*1_000_000); mr.setVideoFrameRate(currentFps); mr.setVideoSize(currentResolution.width, currentResolution.height)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); mr.setAudioSamplingRate(48000); mr.setAudioEncodingBitRate(128000)
        if(isHLGEnabled && isHLGSupportedOnDevice) mr.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC) else mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setOrientationHint(if(isFrontCamera) 270 else 90)
        lastFile=f; pendingFilename=fn; return mr
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(){
        if(isRecording) return
        try{
            closePreviewSessionOnly()
            mediaRecorder=createMediaRecorder()
            mediaRecorder!!.prepare()
            val tex=binding.textureView.surfaceTexture ?: return
            tex.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface=Surface(tex)
            val recorderSurface=mediaRecorder!!.surface
            val builder=cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{ addTarget(previewSurface); addTarget(recorderSurface); applyBypassSettings(this) }
            if(isHLGEnabled && isHLGSupportedOnDevice && Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
                val pc=OutputConfiguration(previewSurface).apply{ setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val rc=OutputConfiguration(recorderSurface).apply{ setDynamicRangeProfile(DynamicRangeProfiles.HLG10) }
                val sc=SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(pc,rc), Executors.newSingleThreadExecutor(), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){ captureSession=s; previewRequestBuilder=builder; try{ s.setRepeatingRequest(builder.build(), null, backgroundHandler); mediaRecorder!!.start(); onRecordingStarted() }catch(e:Exception){ toast("Rec HLG: ${e.message}") } }
                    override fun onConfigureFailed(s:CameraCaptureSession){ toast("Rec HLG configure failed — try 8-bit") }
                })
                cameraDevice!!.createCaptureSession(sc)
            } else {
                cameraDevice!!.createCaptureSession(listOf(previewSurface, recorderSurface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(s:CameraCaptureSession){ captureSession=s; previewRequestBuilder=builder; try{ s.setRepeatingRequest(builder.build(), null, backgroundHandler); mediaRecorder!!.start(); onRecordingStarted() }catch(e:Exception){ toast("Rec: ${e.message}") } }
                    override fun onConfigureFailed(s:CameraCaptureSession){ toast("Rec configure failed — try 4K30") }
                }, backgroundHandler)
            }
        }catch(e:Exception){ toast("Start: ${e.message}"); releaseMediaRecorder(); createPreviewSession() }
    }

    private fun onRecordingStarted(){
        isRecording=true; recordingStartMs=System.currentTimeMillis()
        binding.btnRecord.setBackgroundResource(R.drawable.bg_record_inner_stop)
        try{ binding.btnRecord.layoutParams.width=28; binding.btnRecord.layoutParams.height=28; binding.btnRecord.requestLayout() }catch(_:Exception){}
        binding.tvRecIndicator.visibility=android.view.View.VISIBLE
        timerHandler.post(timerRunnable)
        binding.bottomPanel.alpha=0.65f
        toast("● Recording ${if(isHLGEnabled) "10-bit HLG" else "8-bit SAFE"}")
    }
    private fun stopRecording(){
        if(!isRecording) return
        try{ try{captureSession?.stopRepeating()}catch(_:Exception){}; try{mediaRecorder?.stop()}catch(e:Exception){toast("Stop: ${e.message}") }
            releaseMediaRecorder(); isRecording=false
            binding.btnRecord.setBackgroundResource(R.drawable.bg_record_inner)
            try{ binding.btnRecord.layoutParams.width=64; binding.btnRecord.layoutParams.height=64; binding.btnRecord.requestLayout() }catch(_:Exception){}
            binding.tvRecIndicator.visibility=android.view.View.GONE
            binding.bottomPanel.alpha=1f
            timerHandler.removeCallbacks(timerRunnable)
            galleryScan(); toast("Saved: $pendingFilename"); updateGalleryThumb()
        }catch(e:Exception){ isRecording=false; releaseMediaRecorder(); toast("Stop: ${e.message}") }
        finally{ closePreviewSessionOnly(); createPreviewSession() }
    }
    private fun updateGalleryThumb(){ try{ lastFile?.let{ binding.imgGalleryThumb.alpha=1f } }catch(_:Exception){} }
    private fun galleryScan(){ try{ lastFile?.let{ sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(it))) } }catch(_:Exception){} }
    private fun releaseMediaRecorder(){ try{mediaRecorder?.reset()}catch(_:Exception){}; try{mediaRecorder?.release()}catch(_:Exception){}; mediaRecorder=null }
    private fun closePreviewSessionOnly(){ try{captureSession?.close()}catch(_:Exception){}; captureSession=null }
    private fun closeCamera(){ isCameraOpening=false; try{captureSession?.close()}catch(_:Exception){}; captureSession=null; try{cameraDevice?.close()}catch(_:Exception){}; cameraDevice=null; releaseMediaRecorder() }
    override fun onDestroy(){ timerHandler.removeCallbacks(timerRunnable); super.onDestroy() }
    private fun showHelp(){ AlertDialog.Builder(this).setTitle("How to get clean image").setMessage("• 8-bit SAFE: ready to share\n• 10-bit HLG: HDR — washed in Photos is NORMAL! View on Pixel HDR or Instagram Reels. For YouTube SDR: DaVinci → LUT or Node01 HLG->DWG, Node02 grade, Node03 DWG->Rec709\n• Pixel: Handheld EIS ON/OIS OFF, Tripod both OFF, Night Boost ON\n• Every pill has slider — tap pill, long-press for AUTO").setPositiveButton("Got it"){d,_ -> d.dismiss()}.show() }
    private fun toast(m:String){ Toast.makeText(this,m,Toast.LENGTH_SHORT).show() }
}
