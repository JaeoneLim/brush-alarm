package com.jaewon.brushalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


open class AlarmActivity : AppCompatActivity() {
    private val mode: AlarmScreenMode
        get() = if (this is PreviewActivity) AlarmScreenMode.PREVIEW else AlarmScreenMode.ALARM
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val progress = BrushingProgressEngine(requiredMillis = REQUIRED_MILLIS, maxFrameGapMillis = 500)
    private val frameProgress = FrameProgressController(progress)
    private val verifier = HybridBrushingVerifier()
    private val lumaAnalyzer = LumaSignalAnalyzer()
    private val inferenceGate = InferenceGate(minIntervalMillis = HEAVY_INFERENCE_INTERVAL_MILLIS)
    private val warmUpState = WarmUpStateCoordinator()
    private val frameLoopState = FrameLoopStateCoordinator()
    private lateinit var meshProcessor: FaceMeshProcessor
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var completed = false


    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!frameLoopState.running || completed) return
            analyzePreviewFrame()
            if (frameLoopState.running && !completed) {
                handler.postDelayed(this, FRAME_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (mode.wakeAndUnlockScreen) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
            hideSystemBars()
        }
        setContentView(buildContent())
        if (mode.runBrushingVerification) initializeFaceMesh()
        if (mode.blockDismissal) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    statusView.text = "양치 30초를 완료해야 종료할 수 있습니다."
                }
            })
        }
        if (hasCameraPermission()) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        if (!mode.runBrushingVerification) return
        if (frameLoopState.onStart()) handler.post(frameLoop)
        startFaceMeshWarmUp()
    }

    private fun buildContent(): FrameLayout {
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 72, 32, 48)
            setBackgroundColor(Color.argb(80, 0, 0, 0))
        }
        overlay.addView(TextView(this).apply {
            text = if (mode.showExitButton) "알람 화면 미리보기" else "양치 동작을 보여주세요"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        timerView = TextView(this).apply {
            text = if (mode.showExitButton) "0.0 / 30.0초 · 미리보기" else "0.0 / 30.0초"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 12)
        }
        overlay.addView(timerView, LinearLayout.LayoutParams(-1, -2))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = REQUIRED_MILLIS.toInt()
            progress = 0
        }
        overlay.addView(progressBar, LinearLayout.LayoutParams(-1, 28))
        statusView = TextView(this).apply {
            text = if (mode.showExitButton) "전면 카메라 화면만 보여줍니다. 양치 없이 닫을 수 있습니다."
                else "전면 카메라 준비 중…"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        overlay.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        if (mode.showExitButton) {
            overlay.addView(Button(this).apply {
                text = "미리보기 종료"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return root
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            if (mode.runBrushingVerification) provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            previewUseCase = preview
            if (mode.runBrushingVerification) {
                if (!warmUpState.modelReady) statusView.text = "얼굴 메시 모델 준비 중…"
                if (frameLoopState.onCameraReady()) handler.post(frameLoop)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeFaceMesh() {
        meshProcessor = MlKitFaceMeshProcessor(this)
    }

    private fun startFaceMeshWarmUp() {
        val attempt = warmUpState.onStart() ?: return
        statusView.text = "얼굴 메시 모델 준비 중…"
        meshProcessor.warmUp(
            onReady = {
                if (warmUpState.onReady(attempt)) {
                    statusView.text = "준비 완료—얼굴과 입 주변을 카메라에 보여주세요."
                }
            },
            onFailure = {
                if (warmUpState.onFailure(attempt)) {
                    statusView.text = "얼굴 메시 모델을 준비하지 못했습니다."
                }
            },
        )
    }

    private fun analyzePreviewFrame() {
        if (!warmUpState.modelReady) return
        val source = previewView.bitmap
        if (source == null || source.width == 0 || source.height == 0) return
        val inputSize = FaceMeshInputSize.forSource(source.width, source.height)
        val width = inputSize.width
        val height = inputSize.height
        val bitmap = Bitmap.createScaledBitmap(source, width, height, true)
        val luma = bitmap.toLuma()
        val timestamp = SystemClock.elapsedRealtime()
        if (!lumaAnalyzer.passesPreGate(luma, width, height)) {
            frameProgress.onPreGateRejected(timestamp)
            bitmap.recycle()
            return
        }
        if (!inferenceGate.tryAcquire(timestamp)) {
            frameProgress.onUnverifiedFrame()
            bitmap.recycle()
            return
        }
        meshProcessor.process(
            bitmap = bitmap,
            onResult = { mesh ->
                frameProgress.onVerified(
                    timestampMillis = timestamp,
                    evaluate = { evaluateDetection(luma, width, height, timestamp, mesh) },
                    isBrushing = { it.brushing },
                    onAccepted = { result ->
                        renderProgress(result)
                        if (progress.isComplete) completeAlarm()
                    },
                )
            },
            onFailure = {
                frameProgress.onFailure(timestamp) {
                    renderProgress(VerificationResult(VerificationState.ANALYSIS_ERROR, false, 0))
                }
            },
            onComplete = {
                bitmap.recycle()
                inferenceGate.release()
            },
        )
    }

    private fun evaluateDetection(
        luma: ByteArray,
        width: Int,
        height: Int,
        timestamp: Long,
        mesh: MeshObservation?,
    ): VerificationResult {
        return if (mesh == null || mesh.mouthLandmarks.isEmpty()) {
            verifier.update(BrushingSignal(timestamp, null, 0.0, 0.0, 0.5))
        } else {
            val analysis = lumaAnalyzer.analyze(luma, width, height, mesh)
            verifier.update(
                BrushingSignal(
                    timestampMillis = timestamp,
                    face = analysis.face,
                    localLumaDelta = analysis.localLumaDelta,
                    globalLumaDelta = analysis.globalLumaDelta,
                    mouthMotionX = analysis.mouthMotionX,
                ),
            )
        }
    }

    private fun renderProgress(result: VerificationResult) {
        val seconds = progress.accumulatedMillis / 1000.0
        timerView.text = "%.1f / 30.0초".format(seconds)
        progressBar.progress = progress.accumulatedMillis.toInt()
        statusView.text = when (result.state) {
            VerificationState.WARMING_UP -> "얼굴 메시 모델 준비 중…"
            VerificationState.NO_FACE -> "얼굴과 입이 보이도록 카메라를 맞춰주세요."
            VerificationState.HOLD_STILL -> "카메라와 머리를 안정적으로 유지해주세요."
            VerificationState.LIGHTING_CHANGE -> "조명 변화가 아닌 입 주변 동작이 필요합니다."
            VerificationState.MOVE_AT_MOUTH -> "입 주변에서 칫솔을 좌우로 움직여 주세요."
            VerificationState.SEEKING_CADENCE -> "반복 양치 리듬 확인 중 (${result.reversalCount}/3)"
            VerificationState.BRUSHING -> "반복 양치 동작 감지 중 ✓"
            VerificationState.ANALYSIS_ERROR -> "카메라 분석 오류—다시 비춰주세요."
        }
        statusView.setTextColor(if (result.brushing) Color.rgb(105, 240, 174) else Color.WHITE)
    }

    private fun completeAlarm() {
        if (!mode.stopRingingOnCompletion) return
        if (completed) return
        completed = true
        statusView.text = "완료! 알람을 종료합니다."
        timerView.text = "30.0 / 30.0초 ✓"
        cameraProvider?.unbindAll()
        startService(
            Intent(this, AlarmRingingService::class.java).apply {
                action = AlarmRingingService.ACTION_STOP_AFTER_BRUSHING
            }
        )
        handler.postDelayed({ finishAndRemoveTask() }, 1_200)
    }

    private fun Bitmap.toLuma(): ByteArray {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArray(pixels.size) { index ->
            val color = pixels[index]
            val r = color shr 16 and 0xff
            val g = color shr 8 and 0xff
            val b = color and 0xff
            ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
    }

    private fun showPermissionRequired() {
        statusView.text = if (mode.showExitButton) "화면 미리보기에 카메라 권한이 필요합니다. 바로 닫을 수도 있습니다."
            else "카메라 권한 없이는 양치를 확인할 수 없습니다."
        val button = Button(this).apply {
            text = "카메라 권한 허용"
            setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        }
        (statusView.parent as ViewGroup).addView(button)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mode.blockDismissal && !completed && shouldBlockAlarmKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        frameLoopState.onStop()
        handler.removeCallbacks(frameLoop)
        warmUpState.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(frameLoop)
        if (mode.runBrushingVerification) cameraProvider?.unbindAll()
        else previewUseCase?.let { previewUseCase -> cameraProvider?.unbind(previewUseCase) }
        if (::meshProcessor.isInitialized) meshProcessor.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUIRED_MILLIS = 30_000L
        private const val FRAME_INTERVAL_MILLIS = 125L
        private const val HEAVY_INFERENCE_INTERVAL_MILLIS = 250L
    }
}
