package com.assistant.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var canvasView: AssistCanvasView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultData: Intent? = intent.getParcelableExtra("EXTRA_RESULT_DATA")
            if (resultData != null) {
                startForegroundNotification()
                initOverlayWindow()
                initCapturePipeline(resultData)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "overlay_assist_channel"
        val channelName = "Smart Assist Engine"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Smart Assist Active")
            .setContentText("Processing real-time screen telemetry...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    private fun initOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = FrameLayout(this)
        canvasView = AssistCanvasView(this)

        overlayView?.addView(canvasView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        
        windowManager?.addView(overlayView, params)
    }

    private fun initCapturePipeline(resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(-1, resultData)

        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        
        val width = 1280
        val height = 720
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        backgroundThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader?.acquireLatestImage()
            if (image != null) {
                // Pixel matrix verification step
                image.close()
            }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        backgroundThread?.quitSafely()
        if (overlayView != null && windowManager != null) {
            windowManager?.removeView(overlayView)
        }
    }

    // Custom view engine that draws directly over the display layer
    private class AssistCanvasView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 5f
            antiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Draws a target crosshair in the center of the viewport for alignment confirmation
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawCircle(centerX, centerY, 40f, paint)
            canvas.drawLine(centerX - 60f, centerY, centerX + 60f, centerY, paint)
            canvas.drawLine(centerX, centerY - 60f, centerX, centerY + 60f, paint)
        }
    }
}
