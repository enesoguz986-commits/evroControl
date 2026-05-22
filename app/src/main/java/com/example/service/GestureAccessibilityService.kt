package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.data.AppDatabase
import com.example.data.GestureRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureAccessibilityService : AccessibilityService(), LifecycleOwner {

    // LifecycleOwner implementation for CameraX bindings
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var repository: GestureRepository
    private val gestureMappings = ConcurrentHashMap<String, String>()

    // WindowManager controls
    private lateinit var windowManager: WindowManager
    private var toggleBubbleView: FrameLayout? = null
    private var cameraPanelView: LinearLayout? = null

    private var isPanelExpanded = false
    private var activeCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    // Tracking layouts
    private var previewView: PreviewView? = null
    private var feedbackOverlay: FeedbackOverlayView? = null
    private var lblStatus: TextView? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Init database repository
        val db = AppDatabase.getDatabase(this)
        repository = GestureRepository(db.gestureDao())

        // Collect custom settings in real-time
        serviceScope.launch {
            repository.initializeDefaultsIfNeeded()
            repository.allMappings.collectLatest { list ->
                list.forEach { entity ->
                    if (entity.isEnabled && entity.mappedAction != "NONE") {
                        gestureMappings[entity.gestureId] = entity.mappedAction
                    } else {
                        gestureMappings.remove(entity.gestureId)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Create full overlay components
        createToggleBubble()
        createCameraPanel()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceJob.cancel()
        cameraExecutor.shutdown()

        // Safely remove overlay views
        try {
            toggleBubbleView?.let { windowManager.removeView(it) }
            cameraPanelView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // --- Floating Elements Builder ---

    private fun createToggleBubble() {
        val size = dpToPx(56)
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 400
        }

        val frame = FrameLayout(this).apply {
            // Rounded background
            setBackgroundResource(android.R.drawable.toast_frame)
            backgroundTintList = ContextCompat.getColorStateList(this@GestureAccessibilityService, android.R.color.black)
            setPadding(12, 12, 12, 12)
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            imageTintList = ContextCompat.getColorStateList(this@GestureAccessibilityService, android.R.color.holo_blue_light)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(icon)

        // Draggable touch setup
        frame.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDrag = true
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(frame, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            // Single tap toggles camera panel expanded/collapsed state
                            toggleCameraPanel()
                        }
                        return true
                    }
                }
                return false
            }
        })

        toggleBubbleView = frame
        windowManager.addView(frame, params)
    }

    private fun createCameraPanel() {
        val width = dpToPx(160)
        val height = dpToPx(220)

        val params = WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 520
        }

        // Layout container
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.toast_frame)
            backgroundTintList = ContextCompat.getColorStateList(this@GestureAccessibilityService, android.R.color.background_dark)
            visibility = View.GONE // Hidden initially, activates on toggle bubble tap
        }

        // 1. Draggable header containing buttons
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#424242"))
            setPadding(14, 8, 14, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Yapay Kontrol"
            setTextColor(Color.WHITE)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        // Swap Camera Button
        val btnSwap = TextView(this).apply {
            text = "🔄"
            setPadding(8, 2, 8, 2)
            setOnClickListener {
                activeCameraSelector = if (activeCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                if (isPanelExpanded) {
                    startCamera()
                }
            }
        }
        header.addView(btnSwap)

        // Mini button
        val btnMini = TextView(this).apply {
            text = "➖"
            setPadding(8, 2, 8, 2)
            setOnClickListener {
                toggleCameraPanel()
            }
        }
        header.addView(btnMini)

        panel.addView(header)

        // Draggable gesture on panel header
        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(panel, params)
                        return true
                    }
                }
                return false
            }
        })

        // 2. Camera View & Overlay layered FrameLayout
        val frameStack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val pv = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        frameStack.addView(pv)
        previewView = pv

        // Transparent AR Overlay View sitting on top of Camera
        val fov = FeedbackOverlayView(this)
        frameStack.addView(fov)
        this.feedbackOverlay = fov

        panel.addView(frameStack)

        // Info footer
        val footStatus = TextView(this).apply {
            text = "Hazır..."
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.GREEN)
            textSize = 10f
            setPadding(4, 4, 4, 4)
        }
        panel.addView(footStatus)
        this.lblStatus = footStatus

        cameraPanelView = panel
        windowManager.addView(panel, params)
    }

    private fun toggleCameraPanel() {
        cameraPanelView?.let { panel ->
            if (isPanelExpanded) {
                // Collapse & Pause Camera
                panel.visibility = View.GONE
                isPanelExpanded = false
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                stopCamera()
                vibrate(60)
            } else {
                // Expand & Start Camera
                panel.visibility = View.VISIBLE
                isPanelExpanded = true
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                startCamera()
                vibrate(100)
            }
        }
    }

    // --- CameraX integration and tracking pipeline ---

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                e.printStackTrace()
                lblStatus?.text = "Bağlantı hatası!"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView?.surfaceProvider
        }

        // Custom analyzer matching our lightweight, ultra-low latency gesture algorithm
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, HandGestureAnalyzer(
                    onGestureDetected = { gesture ->
                        triggerMappedAction(gesture)
                    },
                    onAnalysisUpdate = { result ->
                        serviceScope.launch {
                            feedbackOverlay?.updateResult(result)
                            lblStatus?.text = when (result.detectedGesture) {
                                "NONE" -> "Takip ediliyor..."
                                else -> result.detectedGesture
                            }
                        }
                    }
                ))
            }

        try {
            provider.bindToLifecycle(
                this,
                activeCameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
            lblStatus?.text = "Kamera başlatılamadı"
        }
    }

    // --- Action Handler Mapping Integrator ---

    private fun triggerMappedAction(gestureId: String) {
        val action = gestureMappings[gestureId] ?: return
        serviceScope.launch(Dispatchers.Main) {
            vibrate(150) // Satisfying haptic feedback
            lblStatus?.text = "➔ $gestureId ➔ $action"

            when (action) {
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "SCROLL_UP" -> scrollDevice("UP")
                "SCROLL_DOWN" -> scrollDevice("DOWN")
                "VOLUME_UP" -> changeVolume(up = true)
                "VOLUME_DOWN" -> changeVolume(up = false)
            }
        }
    }

    private fun changeVolume(up: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun scrollDevice(direction: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels

            val startX = width / 2f
            // Scroll down simulates swiping up (moving page down)
            val startY = if (direction == "UP") height * 0.35f else height * 0.70f
            val endY = if (direction == "UP") height * 0.75f else height * 0.25f

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 50, 250)
            val builder = GestureDescription.Builder().apply {
                addStroke(stroke)
            }
            dispatchGesture(builder.build(), null, null)
        }
    }

    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
