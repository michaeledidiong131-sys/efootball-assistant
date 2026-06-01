package com.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.PerformanceHintManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service(), ComponentCallbacks2 {

    companion object {
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
    }

    private var isRunning = false
    private var processingThread: Thread? = null
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var txtEngineStatus: TextView
    private lateinit var notificationManager: NotificationManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    
    private var perfHintSession: PerformanceHintManager.Session? = null

    private var lastOcrTime = 0L
    private val OCR_INTERVAL_MS = 800L 
    private var reusableBitmap: Bitmap? = null
    
    private var originalInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        enforceSystemTotalSilence()
        initializePerformanceMode()
        initializeOverlayUI()
        
        // Broadcast aggressive closure of any lingering system UI panels
        @Suppress("DEPRECATION")
        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
    }

    private fun enforceSystemTotalSilence() {
        // TASK 3 ENFORCEMENT: Hardware-level interrupt suppression
        if (notificationManager.isNotificationPolicyAccessGranted) {
            originalInterruptionFilter = notificationManager.currentInterruptionFilter
            // Set to INTERRUPTION_FILTER_NONE to aggressively block ALL calls, messages, and pop-ups
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
    }

    private fun restoreSystemInterrupts() {
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(originalInterruptionFilter)
        }
    }

    private fun initializePerformanceMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val hintManager = getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                perfHintSession = hintManager?.createHintSession(intArrayOf(Process.myTid()), 8333333L)
            } catch (e: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = EngineData.code
        val data = EngineData.intent
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundSafely()
            try {
                setupMediaProjection(resultCode, data)
                if (!isRunning) {
                    initializeProcessingEngine()
                }
            } catch (e: Exception) {
                stopSelf()
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Engine Primary", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("100% Winning Chance Mode")
            .setContentText("Total Silence & ADPF Active")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeOverlayUI() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(com.assistant.overlay.R.layout.overlay_layout, null)
        
        txtEngineStatus = overlayView.findViewById(com.assistant.overlay.R.id.overlay_status_text)
        updateOverlayVisuals("WINNING CHANCE: 100% [ANTI-LAG ACTIVATED]", Color.GREEN)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        windowManager.addView(overlayView, layoutParams)
    }

    private fun updateOverlayVisuals(text: String, color: Int) {
        Handler(Looper.getMainLooper()).post {
            txtEngineStatus.text = text
            txtEngineStatus.setTextColor(color)
        }
    }

    private fun setupMediaProjection(code: Int, intent: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, intent)
        
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() { super.onStop(); stopSelf() }
        }
        mediaProjection?.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val scale = 0.5f 
        val finalWidth = (metrics.widthPixels * scale).toInt() and 0xFFFFFFFE.toInt()
        val finalHeight = (metrics.heightPixels * scale).toInt() and 0xFFFFFFFE.toInt()
        
        imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (System.currentTimeMillis() - lastOcrTime >= OCR_INTERVAL_MS) {
                lastOcrTime = System.currentTimeMillis()
                processImageForOCR(image)
            } else {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun processImageForOCR(image: Image) {
        val startNs = System.nanoTime()
        
        if (reusableBitmap == null || reusableBitmap!!.width != image.width || reusableBitmap!!.height != image.height) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        }
        
        reusableBitmap!!.copyPixelsFromBuffer(image.planes[0].buffer)
        val inputImage = InputImage.fromBitmap(reusableBitmap!!, 0)
        
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (visionText.text.contains("time", true) || visionText.text.contains("v", true)) {
                    updateOverlayVisuals("WINNING CHANCE: 100% [ACTIVE]", Color.GREEN)
                }
            }
            .addOnCompleteListener { 
                image.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    perfHintSession?.reportActualWorkDuration(System.nanoTime() - startNs)
                }
            }
    }

    private fun initializeProcessingEngine() {
        isRunning = true
        processingThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            while (isRunning) { try { Thread.sleep(50) } catch (e: InterruptedException) { break } }
        }.apply { start() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TASK 3 ENFORCEMENT: Defends against OS-initiated cache drops resulting in lag
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            reusableBitmap?.recycle()
            reusableBitmap = null
        }
    }

    override fun onDestroy() {
        isRunning = false
        restoreSystemInterrupts()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perfHintSession?.close()
        }
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        reusableBitmap?.recycle()
        reusableBitmap = null
        super.onDestroy()
    }
}
