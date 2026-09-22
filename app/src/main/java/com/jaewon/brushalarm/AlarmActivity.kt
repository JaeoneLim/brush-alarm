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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class AlarmActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)
    private val progress = BrushingProgressEngine(requiredMillis = REQUIRED_MILLIS, maxFrameGapMillis = 1_000)
    private val motion = LumaMotionDetector(threshold = 11.0)
    private var cameraProvider: ProcessCameraProvider? = null
    private var completed = false
    private var previousFaceCenter: Pair<Float, Float>? = null

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.25f)
            .build()
    )

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionRequired()
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!completed) analyzePreviewFrame()
            handler.postDelayed(this, FRAME_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        hideSystemBars()
        setContentView(buildContent())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                statusView.text = "양치 30초를 완료해야 종료할 수 있습니다."
            }
        })
        if (hasCameraPermission()) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
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
            text = "양치 동작을 보여주세요"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        timerView = TextView(this).apply {
            text = "0.0 / 30.0초"
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
            text = "전면 카메라 준비 중…"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        overlay.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return root
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            statusView.text = "얼굴과 입 주변 칫솔 움직임을 확인합니다."
            handler.removeCallbacks(frameLoop)
            handler.post(frameLoop)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzePreviewFrame() {
        if (!processing.compareAndSet(false, true)) return
        val source = previewView.bitmap
        if (source == null || source.width == 0 || source.height == 0) {
            processing.set(false)
            return
        }
        val width = 320
        val height = (source.height * width.toFloat() / source.width).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createScaledBitmap(source, width, height, true)
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces -> processDetection(bitmap, faces) }
            .addOnFailureListener {
                progress.update(SystemClock.elapsedRealtime(), false)
                statusView.text = "카메라 분석 오류—다시 비춰주세요."
            }
            .addOnCompleteListener {
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun processDetection(bitmap: Bitmap, faces: List<Face>) {
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            previousFaceCenter = null
            progress.update(SystemClock.elapsedRealtime(), false)
            renderProgress(false, "얼굴이 보이지 않습니다.")
            return
        }

        val box = face.boundingBox
        val center = box.exactCenterX() to box.exactCenterY()
        val oldCenter = previousFaceCenter
        previousFaceCenter = center
        val stable = oldCenter == null || (
            kotlin.math.abs(center.first - oldCenter.first) < bitmap.width * 0.09f &&
                kotlin.math.abs(center.second - oldCenter.second) < bitmap.height * 0.09f
            )

        val left = (box.left + box.width() * 0.18f).roundToInt()
        val right = (box.right - box.width() * 0.18f).roundToInt()
        val top = (box.top + box.height() * 0.52f).roundToInt()
        val bottom = (box.top + box.height() * 0.88f).roundToInt()
        val luma = bitmap.toLuma()
        val brushing = stable && motion.detect(
            luma,
            bitmap.width,
            bitmap.height,
            left,
            top,
            right,
            bottom,
        )
        progress.update(SystemClock.elapsedRealtime(), brushing)
        renderProgress(
            brushing,
            if (brushing) "양치 동작 감지 중 ✓" else "입 주변에서 칫솔을 움직여 주세요.",
        )
        if (progress.isComplete) completeAlarm()
    }

    private fun renderProgress(active: Boolean, message: String) {
        val seconds = progress.accumulatedMillis / 1000.0
        timerView.text = "%.1f / 30.0초".format(seconds)
        progressBar.progress = progress.accumulatedMillis.toInt()
        statusView.text = message
        statusView.setTextColor(if (active) Color.rgb(105, 240, 174) else Color.WHITE)
    }

    private fun completeAlarm() {
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
        statusView.text = "카메라 권한 없이는 양치를 확인할 수 없습니다."
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
        if (!completed && shouldBlockAlarmKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(frameLoop)
        cameraProvider?.unbindAll()
        detector.close()
        super.onDestroy()
    }

    companion object {
        private const val REQUIRED_MILLIS = 30_000L
        private const val FRAME_INTERVAL_MILLIS = 250L
    }
}
