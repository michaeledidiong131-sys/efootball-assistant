package com.assistant

import android.media.MediaRecorder
import android.content.Context
import android.content.Intent

import android.media.MediaRecorder
package com.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
    }

    private var isRunning = false
    private var processingThread: Thread? = null
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // OCR Throttle State
    private var lastOcrTime = 0L
    private val OCR_INTERVAL_MS = 1000L // Scan screen 1 time per second

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initializeOverlayUI()
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
                // If it fails during setup, throw to GlobalCrashHandler
                throw RuntimeException("VirtualDisplay Initialization Failed: ${e.message}", e)
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Assistant Engine Background", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Splendor Assist Active")
            .setContentText("Hybrid Engine scanning frames...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeOverlayUI() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(com.assistant.overlay.R.layout.overlay_layout, null)

        @Suppress("DEPRECATION")
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(overlayView, layoutParams)
    }

    private fun setupMediaProjection(code: Int, intent: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, intent)
        
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopSelf()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (width <= 0 || height <= 0) { width = 720; height = 1280 }

        val maxDimension = Math.max(width, height)
        val scale = if (maxDimension > 720) 720f / maxDimension else 1f
        var finalWidth = (width * scale).toInt()
        var finalHeight = (height * scale).toInt()
        
        if (finalWidth % 2 != 0) finalWidth -= 1
        if (finalHeight % 2 != 0) finalHeight -= 1
        
        imageReader = ImageReader.newInstance(finalWidth, finalHeight, PixelFormat.RGBA_8888, 2)
        
        // --- PHASE B: THE OCR THROTTLE PIPELINE ---
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastOcrTime >= OCR_INTERVAL_MS) {
                lastOcrTime = currentTime
                processImageForOCR(image)
            } else {
                image.close() // Discard frame to prevent OOM memory leaks
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
        startHardwareDVR()
            "HybridCoachScreen", finalWidth, finalHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        ) ?: throw Exception("Kernel denied VirtualDisplay creation.")
    }

    private fun processImageForOCR(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride

            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Clean padding
            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            val inputImage = InputImage.fromBitmap(cleanBitmap, 0)
            
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    // Proof of Concept: Log detected match states to UI
                    if (visionText.text.contains("Full Time", ignoreCase = true) || visionText.text.contains("Half Time", ignoreCase = true)) {
                        Toast.makeText(applicationContext, "MATCH STATE DETECTED", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnCompleteListener {
                    image.close() // CRITICAL: Release hardware buffer
                }
        } catch (e: Exception) {
            image.close()
        }
    }

    private fun initializeProcessingEngine() {
        isRunning = true
        processingThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            while (isRunning) {
                try { Thread.sleep(16) } catch (e: InterruptedException) { break }
            }
        }.apply { start() }
    }

    override fun onDestroy() {
        isRunning = false
        processingThread?.interrupt()
        processingThread = null
        if (::overlayView.isInitialized) { windowManager.removeView(overlayView) }
        virtualDisplay?.release()
        imageReader?.close()
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        mediaProjection?.stop()
        super.onDestroy()
    }
}

    private fun startHardwareDVR() {
        mediaRecorder = android.media.MediaRecorder()
        mediaRecorder?.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
        mediaRecorder?.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder?.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
        mediaRecorder?.setVideoSize(1280, 720)
        mediaRecorder?.setVideoFrameRate(30)
        mediaRecorder?.setOutputFile(externalCacheDir?.absolutePath + "/match_chunk.mp4")
        mediaRecorder?.prepare()
        mediaRecorder?.start()
    }
