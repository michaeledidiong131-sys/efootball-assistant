# Update build.gradle.kts
cat > app/build.gradle.kts << EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant.overlay"

    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistant.overlay"
        minSdk = 26
        targetSdk = 34

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Add other release specific configurations if needed
        }
        debug {
            // Debug specific configurations
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true // Enable view binding
    }
}

dependencies {
    // Core Kotlin
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0") // Material Design components
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // ConstraintLayout for UI

    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.0") // Use a stable version

    // Coroutines for background tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Activity KTX for Activity Result APIs
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Testing dependencies (optional, but good practice)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
EOF

# Replace EngineData.kt
cat > app/src/main/java/com/assistant/EngineData.kt << EOF
package com.assistant

import android.content.Intent
import androidx.annotation.Keep

@Keep
object EngineData {
    var code: Int = Activity.RESULT_CANCELED
    var intent: Intent? = null
    var isUpdateAvailable: Boolean = false
    var updateUrl: String? = null
    var matchHistoryJson: String? = null
    var videoReplayData: ByteArray? = null // Placeholder for video replay data
}
EOF

# Replace ErrorActivity.kt
cat > app/src/main/java/com/assistant/ErrorActivity.kt << EOF
package com.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R

class ErrorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ErrorActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)

        val log = intent.getStringExtra("CRASH_LOG") ?: "No log provided."
        val txtLog = findViewById<TextView>(R.id.txtCrashLog)
        val btnCopy = findViewById<Button>(R.id.btnCopyLog)

        txtLog.text = log
        Log.e(TAG, "Crash Log:\n$log")

        btnCopy.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash Log", log)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log Copied. Send to Lead Engineer.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy log to clipboard", e)
                Toast.makeText(this, "Failed to copy log.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
EOF

# Replace MainActivity.kt
cat > app/src/main/java/com/assistant/MainActivity.kt << EOF
package com.assistant

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.assistant.overlay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_NOTIFICATION_ID = "notification_permission"
        private const val PERMISSION_OVERLAY_ID = "overlay_permission"
        private const val PERMISSION_INSTALL_PACKAGES_ID = "install_packages_permission"
    }

    private lateinit var projectionManager: MediaProjectionManager

    // Activity Result Launchers
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            EngineData.code = result.resultCode
            EngineData.intent = result.data
            startOverlayService()
            Toast.makeText(this, "Hybrid Coach Engine Online", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Engine Authorization Denied.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "MediaProjection denied or failed. ResultCode: ${result.resultCode}")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted.")
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "Notifications required for background engine.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Notification permission denied.")
            // Optionally, disable functionality or prompt again
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission granted.")
            requestNotificationPermission() // Request notification permission after overlay is granted
        } else {
            Toast.makeText(this, "Overlay Permission Required.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Overlay permission denied.")
            // Optionally, disable functionality or prompt again
        }
    }

    private val installPackagesPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Install packages permission granted.")
            // Proceed with installation logic if needed
        } else {
            Log.w(TAG, "Install packages permission denied.")
            Toast.makeText(this, "Install Packages permission denied. Updates may not be installable.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Using View Binding
        // val binding = ActivityMainBinding.inflate(layoutInflater)
        // setContentView(binding.root)
        setContentView(R.layout.activity_main) // Assuming activity_main.xml exists

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize UI elements from activity_main.xml
        // binding.btnStartEngine.setOnClickListener { checkPermissionsAndStartEngine() }
        // binding.btnViewHistory.setOnClickListener { openMatchHistory() }
        val btnStartEngine = findViewById<Button>(R.id.btnStartEngine) // Replace with actual ID
        val btnViewHistory = findViewById<Button>(R.id.btnViewHistory) // Replace with actual ID

        btnStartEngine.setOnClickListener { checkPermissionsAndStartEngine() }
        btnViewHistory.setOnClickListener { openMatchHistory() }

        // Check if the app needs to be updated on startup
        checkUpdateStatus()

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))
    }

    private fun checkUpdateStatus() {
        // In a real app, you'd check a remote server for updates.
        // For this override, we'll simulate an update being available if a specific condition is met (e.g., a flag in EngineData).
        if (EngineData.isUpdateAvailable && EngineData.updateUrl != null) {
            Toast.makeText(this, "Update available!", Toast.LENGTH_LONG).show()
            // Optionally, navigate to UpdateActivity automatically or show a prompt
            // startActivity(Intent(this, UpdateActivity::class.java))
            // finish()
        }
    }

    private fun checkPermissionsAndStartEngine() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission()
                return
            }
        }
        checkOverlayPermission()
    }

    private fun requestNotificationPermission() {
        Log.d(TAG, "Requesting notification permission.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // On older versions, notification permission is granted by default or via settings.
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Requesting overlay permission.")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                Log.d(TAG, "Overlay permission already granted.")
                // If notification permission was also requested and granted, proceed.
                // Otherwise, this might be called directly by the user clicking the button.
                requestScreenCapture()
            }
        } else {
            requestScreenCapture() // Overlays are generally allowed on versions below M without explicit permission
        }
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission.")
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startOverlayService() {
        if (!isServiceRunning(OverlayService::class.java)) {
            Log.d(TAG, "Starting OverlayService.")
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("code", EngineData.code)
                // Pass the data Intent extras if needed, be mindful of size limitations
                // result.data?.extras?.let { putExtras(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Log.d(TAG, "OverlayService is already running.")
            // Optionally, send a new intent to the running service if needed
        }
    }

    private fun isServiceRunning(serviceClass: Class<out Service>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            serviceClass.name == it.service.className
        }
    }

    private fun openMatchHistory() {
        // Placeholder for navigating to a Match History screen/fragment
        Toast.makeText(this, "Opening Match History...", Toast.LENGTH_SHORT).show()
        // Example: startActivity(Intent(this, MatchHistoryActivity::class.java))
    }

    // Check if install packages permission is needed and request it
    private fun checkAndRequestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.REQUEST_INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting INSTALL_PACKAGES permission.")
                installPackagesPermissionLauncher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
            } else {
                Log.d(TAG, "INSTALL_PACKAGES permission already granted.")
            }
        }
    }
}
EOF

# Replace OverlayService.kt
cat > app/src/main/java/com/assistant/OverlayService.kt << EOF
package com.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.assistant.overlay.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
        private const val OCR_PROCESSING_INTERVAL_MS = 1000L // Scan screen once per second
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // OCR Throttle State
    private val isProcessing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Video Recording placeholders
    private var isRecording = false
    private var videoRecorder: Any? = null // Replace with actual video recorder class (e.g., MediaRecorder)
    private var recordingStartTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.extras

        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "MediaProjection data is missing or invalid. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isProcessing.get()) {
            startForegroundAndInitialize(code, data)
        } else {
            // Service is already running, potentially update data or re-initialize
            Log.d(TAG, "Service already running, re-initializing MediaProjection.")
            mediaProjection?.unregisterCallback(projectionCallback!!)
            initializeMediaProjection(code, data)
        }

        // Ensure the video recording is started if it wasn't already
        startVideoRecording()

        return START_STICKY
    }

    private fun startForegroundAndInitialize(code: Int, data: Bundle?) {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Splendor Assist")
            .setContentText("Engine is running...")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's launcher icon
            .setContentIntent(pendingIntent)
            .setTicker("Engine is running...")
            .setPriority(NotificationCompat.PRIORITY_LOW) // Lower priority for background service
            .build()

        // Specify foreground service type
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0 // Not applicable for older versions
        }

        try {
            ContextCompat.startForegroundService(this, notification, foregroundServiceType)
            Log.d(TAG, "Foreground service started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            // Handle cases where startForegroundService fails (e.g., permission issues)
            return
        }

        initializeOverlay()
        initializeMediaProjection(code, data)
        startOcrProcessing()
    }

    private fun initializeOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutInflater = LayoutInflater.from(this)
        overlayView = layoutInflater.inflate(R.layout.overlay_layout, null) // Assuming overlay_layout.xml exists

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(overlayView, params)
            overlayView.visibility = View.VISIBLE
            Log.d(TAG, "Overlay initialized and added.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            Toast.makeText(this, "Failed to display overlay. Check permissions.", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeMediaProjection(code: Int, data: Bundle?) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, data ?: Intent())

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection instance.")
            stopSelf()
            return
        }

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped.")
                handler.removeCallbacks(ocrRunnable) // Stop OCR processing
                virtualDisplay?.release()
                imageReader?.close()
                mediaProjection?.unregisterCallback(this)
                stopSelf()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, handler)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, ImageFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessing.compareAndSet(false, true)) {
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    processImageForOCR(image)
                    image.close() // Close the image after processing
                }
                isProcessing.set(false)
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay.")
            stopSelf()
            return
        }
        Log.d(TAG, "MediaProjection and VirtualDisplay initialized.")
    }

    private fun processImageForOCR(image: Image) {
        val planes = image.planes
        val yuvImage = image.planes[0].buffer
        val width = image.width
        val height = image.height
        val rowStride = planes[0].rowStride
        val pixelStride = planes[0].pixelStride

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(yuvImage)

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                // Process OCR results - Add logic here to display/use the recognized text
                val recognizedText = result.text
                Log.d(TAG, "OCR Result: $recognizedText")

                // Example: Update overlay text
                //runOnUiThread {
                //    overlayView.findViewById<TextView>(R.id.overlay_text_view).text = recognizedText
                //}

                // Video Recording: Capture frame data if recording
                if (isRecording) {
                    captureFrameForVideo(bitmap)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR processing failed", e)
            }
            .addOnCompleteListener {
                // Cleanup bitmap to free memory
                bitmap.recycle()
            }
    }

    private fun startOcrProcessing() {
        Log.d(TAG, "Starting OCR processing loop.")
        handler.post(ocrRunnable)
    }

    private val ocrRunnable = object : Runnable {
        override fun run() {
            if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
                Log.w(TAG, "Cannot start OCR: MediaProjection not ready.")
                handler.postDelayed(this, OCR_PROCESSING_INTERVAL_MS)
                return
            }
            // The ImageReader listener handles the actual processing,
            // this runnable ensures it's triggered periodically.
            // The listener itself throttles based on isProcessing flag.
            handler.postDelayed(this, OCR_PROCESSING_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        handler.removeCallbacks(ocrRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback!!)
        mediaProjection?.stop()

        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }

        stopVideoRecording() // Stop video recording

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Splendor Assist Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service channel for Splendor Assist"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    // Placeholder for video recording logic
    private fun startVideoRecording() {
        if (!isRecording) {
            Log.d(TAG, "Starting video recording...")
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            // Initialize MediaRecorder or similar here
            // videoRecorder = MediaRecorder().apply {
            //    setVideoSource(MediaRecorder.VideoSource.SURFACE)
            //    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            //    setOutputFile(getRecordingFilePath()) // Define a function to get a unique file path
            //    setVideoSize(imageReader?.width ?: 720, imageReader?.height ?: 1280)
            //    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            //    setVideoEncodingBitRate(10 * 1024 * 1024) // Example bitrate
            //    setVideoFrameRate(30) // Example frame rate
            //    prepare()
            //    start()
            // }
            Toast.makeText(this, "Video recording started.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideoRecording() {
        if (isRecording) {
            Log.d(TAG, "Stopping video recording...")
            isRecording = false
            // Release MediaRecorder here
            // videoRecorder?.stop()
            // videoRecorder?.release()
            // videoRecorder = null
            val recordingDuration = System.currentTimeMillis() - recordingStartTime
            Toast.makeText(this, "Video recording stopped. Duration: ${recordingDuration}ms", Toast.LENGTH_LONG).show()
        }
    }

    // Capture a frame from the bitmap for video recording
    private fun captureFrameForVideo(bitmap: Bitmap) {
        if (!isRecording) return

        // This is a placeholder. In a real implementation, you would encode the bitmap
        // into a video frame and append it to the recording.
        Log.d(TAG, "Capturing frame for video recording.")
        // Example: convert bitmap to byte array or process with MediaCodec
        // val outputStream = ByteArrayOutputStream()
        // bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        // val frameData = outputStream.toByteArray()
        // appendFrameToVideo(frameData) // Define a function to handle appending frame data
    }

    // Placeholder function to get a unique file path for recordings
    private fun getRecordingFilePath(): String {
        val dir =getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val timestamp = System.currentTimeMillis()
        return File(dir, "splendor_assist_recording_$timestamp.mp4").absolutePath
    }

    // Placeholder function to append frame data to the video file
    // private fun appendFrameToVideo(frameData: ByteArray) {
    //     // Logic to append frame data to the MediaRecorder or video file
    // }

    // Placeholder for Accessibility Service initialization and logic
    private fun initializeAccessibilityService() {
        Log.d(TAG, "Initializing Accessibility Service.")
        // Logic to start or interact with the Accessibility Service
        // This would typically involve binding to the Accessibility Service if it's in a different process,
        // or directly using its APIs if it's part of the same application.
    }

    // Placeholder for VpnService initialization and logic
    private fun initializeVpnService() {
        Log.d(TAG, "Initializing VPN Service.")
        // Logic to start or interact with the VpnService
        // This would typically involve managing network interfaces and routing.
    }

}
EOF

# Replace MainActivity.kt (ensure it uses the new EngineData and calls appropriate permission checks)
cat > app/src/main/java/com/assistant/MainActivity.kt << EOF
package com.assistant

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.assistant.overlay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_NOTIFICATION_ID = "notification_permission"
        private const val PERMISSION_OVERLAY_ID = "overlay_permission"
        private const val PERMISSION_INSTALL_PACKAGES_ID = "install_packages_permission"
    }

    private lateinit var projectionManager: MediaProjectionManager

    // Activity Result Launchers
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            EngineData.code = result.resultCode
            EngineData.intent = result.data
            startOverlayService()
            Toast.makeText(this, "Hybrid Coach Engine Online", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Engine Authorization Denied.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "MediaProjection denied or failed. ResultCode: ${result.resultCode}")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted.")
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "Notifications required for background engine.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Notification permission denied.")
            // Optionally, disable functionality or prompt again
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission granted.")
            requestNotificationPermission() // Request notification permission after overlay is granted
        } else {
            Toast.makeText(this, "Overlay Permission Required.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Overlay permission denied.")
            // Optionally, disable functionality or prompt again
        }
    }

    private val installPackagesPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Install packages permission granted.")
            // Proceed with installation logic if needed
        } else {
            Log.w(TAG, "Install packages permission denied.")
            Toast.makeText(this, "Install Packages permission denied. Updates may not be installable.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Assuming activity_main.xml exists

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStartEngine = findViewById<Button>(R.id.btnStartEngine) // Replace with actual ID
        val btnViewHistory = findViewById<Button>(R.id.btnViewHistory) // Replace with actual ID

        btnStartEngine.setOnClickListener { checkPermissionsAndStartEngine() }
        btnViewHistory.setOnClickListener { openMatchHistory() }

        // Check if the app needs to be updated on startup
        checkUpdateStatus()

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))
    }

    private fun checkUpdateStatus() {
        // Simulate update check: if EngineData flag is set, show toast and navigate to UpdateActivity
        if (EngineData.isUpdateAvailable) {
            Toast.makeText(this, "Update available!", Toast.LENGTH_LONG).show()
            val updateIntent = Intent(this, UpdateActivity::class.java).apply {
                putExtra("updateUrl", EngineData.updateUrl ?: "")
            }
            startActivity(updateIntent)
            finish() // Close MainActivity after launching UpdateActivity
        }
    }

    private fun checkPermissionsAndStartEngine() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission()
                return
            }
        }
        checkOverlayPermission()
    }

    private fun requestNotificationPermission() {
        Log.d(TAG, "Requesting notification permission.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // On older versions, notification permission is granted by default or via settings.
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Requesting overlay permission.")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                Log.d(TAG, "Overlay permission already granted.")
                requestScreenCapture()
            }
        } else {
            requestScreenCapture() // Overlays are generally allowed on versions below M without explicit permission
        }
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission.")
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startOverlayService() {
        if (!isServiceRunning(OverlayService::class.java)) {
            Log.d(TAG, "Starting OverlayService.")
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("code", EngineData.code)
                // Pass the data Intent extras if needed, be mindful of size limitations
                putExtras(Intent().apply { data = EngineData.intent?.data }) // Pass only necessary data
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Log.d(TAG, "OverlayService is already running.")
            // Optionally, send a new intent to the running service if needed
        }
    }

    private fun isServiceRunning(serviceClass: Class<out Service>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            serviceClass.name == it.service.className
        }
    }

    private fun openMatchHistory() {
        Toast.makeText(this, "Opening Match History...", Toast.LENGTH_SHORT).show()
        // Navigate to Match History UI
        // Example: startActivity(Intent(this, MatchHistoryActivity::class.java))
    }

    // Check if install packages permission is needed and request it
    private fun checkAndRequestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.REQUEST_INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting INSTALL_PACKAGES permission.")
                installPackagesPermissionLauncher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
            } else {
                Log.d(TAG, "INSTALL_PACKAGES permission already granted.")
            }
        }
    }
}
EOF

# Replace GlobalCrashHandler.kt
cat > app/src/main/java/com/assistant/GlobalCrashHandler.kt << EOF
package com.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GlobalCrashHandler"
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        Log.e(TAG, "Uncaught exception in thread ${thread.name}", exception)

        val sw = StringWriter()
        exception.printStackTrace(PrintWriter(sw))
        val log = sw.toString()

        // Set update available flag and URL in EngineData before navigating to ErrorActivity
        // This is a placeholder; in a real app, update checks would be more robust.
        EngineData.isUpdateAvailable = true // Simulate an update being available after a crash
        EngineData.updateUrl = "https://github.com/your-repo/splendor-assist/releases/latest/download/app-release.apk" // Example update URL

        val intent = Intent(context, ErrorActivity::class.java).apply {
            putExtra("CRASH_LOG", log)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ErrorActivity", e)
            // Fallback: print to log and exit
            println("CRASH LOG:\n$log")
            exitProcess(1)
        }

        // Ensure the service is stopped gracefully
        context.stopService(Intent(context, OverlayService::class.java))
        
        // Give the ErrorActivity time to start before exiting
        Thread.sleep(1000) // Adjust delay as needed
        exitProcess(1)
    }
}
EOF

# Replace UpdateActivity.kt
cat > app/src/main/java/com/assistant/UpdateActivity.kt << EOF
package com.assistant

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class UpdateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdateActivity"
        private const val AUTHORITY = "com.assistant.overlay.provider" // Must match AndroidManifest.xml
    }

    private var downloadId: Long = -1L
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: Button
    private lateinit var downloadManager: DownloadManager
    private var currentUpdateUrl: String? = null

    // Broadcast receiver for download completion
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId || downloadId == -1L) return

            Log.d(TAG, "Download complete. ID: $id")
            progressBar.visibility = ProgressBar.GONE
            progressText.visibility = TextView.GONE
            btnDownload.isEnabled = true
            btnDownload.text = "Install Update"
            btnDownload.setOnClickListener { installApk() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        btnDownload = findViewById(R.id.btnDownloadUpdate)
        val btnSkip = findViewById<Button>(R.id.btnSkipUpdate)
        progressBar = findViewById(R.id.updateProgressBar)
        progressText = findViewById(R.id.progressText)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        currentUpdateUrl = intent.getStringExtra("updateUrl") ?: EngineData.updateUrl // Get URL from intent or EngineData

        if (currentUpdateUrl.isNullOrEmpty()) {
            Log.e(TAG, "No update URL provided. Disabling download button.")
            Toast.makeText(this, "Update URL not configured.", Toast.LENGTH_LONG).show()
            btnDownload.isEnabled = false
            btnDownload.text = "Update Unavailable"
        } else {
            btnDownload.setOnClickListener { startDownload() }
        }

        btnSkip.setOnClickListener {
            // If an update was available and skipped, we might want to reset the flag or notify the user
            EngineData.isUpdateAvailable = false
            EngineData.updateUrl = null
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Register the broadcast receiver
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        Log.d(TAG, "UpdateActivity created. Update URL: $currentUpdateUrl")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver) // Unregister receiver to prevent leaks
    }

    private fun startDownload() {
        if (currentUpdateUrl.isNullOrEmpty()) {
            Toast.makeText(this, "Cannot download: Update URL is missing.", Toast.LENGTH_LONG).show()
            return
        }

        btnDownload.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressText.visibility = TextView.VISIBLE
        progressText.text = "Downloading..."

        val request = DownloadManager.Request(Uri.parse(currentUpdateUrl))
            .setTitle("Splendor Assist Update")
            .setDescription("Downloading new version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "splendor_assist_update.apk")
            .setMimeType("application/vnd.android.package-archive")
            .addRequestHeader("User-Agent", "SplendorAssistUpdater") // Optional: Identify your app

        downloadId = downloadManager.enqueue(request)
        Log.d(TAG, "Download started. ID: $downloadId")

        // Periodically update progress (simplified - a more robust solution would use DownloadManager query)
        updateDownloadProgress()
    }

    private fun updateDownloadProgress() {
        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor = downloadManager.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloadedBytes = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SPEC))
            val totalBytes = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

            when (status) {
                DownloadManager.STATUS_RUNNING -> {
                    val progress = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes) else 0
                    progressBar.progress = progress
                    progressText.text = "Downloading: $progress%"
                    handler.postDelayed({ updateDownloadProgress() }, 500) // Update every 500ms
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progressBar.progress = 100
                    progressText.text = "Download Complete"
                    // Install handled by receiver, but can also be triggered here if receiver fails
                }
                DownloadManager.STATUS_FAILED -> {
                    Log.e(TAG, "Download failed.")
                    progressBar.visibility = ProgressBar.GONE
                    progressText.text = "Download Failed"
                    btnDownload.isEnabled = true
                    btnDownload.text = "Retry Download"
                    btnDownload.setOnClickListener { startDownload() }
                    Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                }
                else -> {
                    // Other statuses like PAUSED, etc.
                    progressText.text = "Download Status: $status"
                    handler.postDelayed({ updateDownloadProgress() }, 500)
                }
            }
            cursor.close()
        } else {
            // Cursor is null or empty, maybe downloadId is invalid or download finished before query
            Log.w(TAG, "Download cursor is null or empty.")
            // Check if download completed successfully already
            if (progressBar.progress < 100) {
                 progressBar.visibility = ProgressBar.GONE
                 progressText.text = "Download Error"
                 btnDownload.isEnabled = true
                 btnDownload.text = "Retry Download"
                 btnDownload.setOnClickListener { startDownload() }
                 Toast.makeText(this, "Download error.", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun installApk() {
        val apkFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "splendor_assist_update.apk")
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found at: ${apkFile.absolutePath}")
            Toast.makeText(this, "Installation failed: APK not found.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(this@UpdateActivity, AUTHORITY, apkFile)
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            startActivity(intent)
            Log.d(TAG, "Starting APK installation.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity to install APK", e)
            Toast.makeText(this, "Could not start installer. Check application settings.", Toast.LENGTH_LONG).show()
        }
    }
}
EOF

# Replace AndroidManifest.xml
cat > app/src/main/AndroidManifest.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- Required for screen capture -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <!-- Required for VPN Service (if implemented fully) -->
    <uses-permission android:name="android.permission.BIND_VPN_SERVICE" />
    <!-- Required for Accessibility Service -->
    <uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
    <!-- For video recording -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- Optional: if audio is recorded -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" /> <!-- For saving recordings -->


    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.SplendorAssist"
        tools:targetApi="31">

        <!-- MainActivity -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.AppCompat.NoActionBar">
             <!-- Removed MAIN and LAUNCHER actions from here -->
        </activity>

        <!-- UpdateActivity -->
        <activity
            android:name=".UpdateActivity"
            android:exported="false"
            android:label="Update Available"
            android:theme="@style/Theme.UpdateTheme" /> <!-- Use a specific theme for update -->

        <!-- ErrorActivity -->
        <activity
            android:name=".ErrorActivity"
            android:exported="false"
            android:label="Error Occurred"
            android:theme="@style/Theme.ErrorTheme" /> <!-- Use a specific theme for error -->

        <!-- Overlay Service -->
        <service
            android:name=".OverlayService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

        <!-- Accessibility Service -->
        <service
            android:name=".SmartAssistAccessibilityService"
            android:enabled="true"
            android:exported="true"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <!-- VPN Service -->
        <service
            android:name=".PingEliminatorVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <meta-data
                android:name="android.support.UIABLE_LOG_TAG"
                android:value="PingEliminatorVpnService" />
        </service>

        <!-- FileProvider -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/provider_paths" />
        </provider>

        <!-- Add a placeholder for MAIN/LAUNCHER activity -->
        <activity android:name=".SplashActivity" android:exported="true" android:theme="@style/Theme.App.Starting">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
EOF

# Create SplashActivity.kt (as placeholder for MAIN/LAUNCHER)
cat > app/src/main/java/com/assistant/SplashActivity.kt << EOF
package com.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val SPLASH_DELAY: Long = 1500 // 1.5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set a theme that provides a splash screen background
        setTheme(R.style.Theme_App_Starting) // Define this theme in styles.xml
        setContentView(R.layout.activity_splash) // Create activity_splash.xml for splash screen background

        // Simulate a delay before navigating to MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            // Check for updates before navigating to MainActivity
            checkUpdatesAndNavigate()
        }, SPLASH_DELAY)
    }

    private fun checkUpdatesAndNavigate() {
        // In a real application, you would check for updates here.
        // For now, we'll directly navigate to MainActivity.
        // If an update is found, EngineData.isUpdateAvailable would be set to true
        // and UpdateActivity would be launched from MainActivity.

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Close SplashActivity
    }
}
EOF

# Create activity_splash.xml (placeholder)
mkdir -p app/src/main/res/layout
cat > app/src/main/res/layout/activity_splash.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/splash_background"> <!-- Define splash_background in drawables -->

    <!-- Optional: Add a logo or app name centered -->
    <ImageView
        android:layout_width="150dp"
        android:layout_height="150dp"
        android:src="@drawable/ic_launcher_foreground"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        tools:ignore="ContentDescription" />

</androidx.constraintlayout.widget.ConstraintLayout>
EOF

# Create splash_background.xml (placeholder)
mkdir -p app/src/main/res/drawable
cat > app/src/main/res/drawable/splash_background.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="135"
        android:startColor="#4CAF50"
        android:endColor="#2196F3"
        android:type="linear" />
</shape>
EOF

# Create styles.xml (for splash screen and other themes)
mkdir -p app/src/main/res/values
cat > app/src/main/res/values/styles.xml << EOF
<resources>
    <!-- Base application theme. -->
    <style name="Theme.SplendorAssist" parent="Theme.AppCompat.Light.DarkActionBar">
        <!-- Primary brand color. -->
        <item name="colorPrimary">#6200EE</item>
        <item name="colorPrimaryVariant">#3700B3</item>
        <item name="colorOnPrimary">#FFFFFF</item>
        <!-- Secondary brand color. -->
        <item name="colorSecondary">#03DAC6</item>
        <item name="colorSecondaryVariant">#018786</item>
        <item name="colorOnSecondary">#000000</item>
        <!-- Status bar color. -->
        <item name="android:statusBarColor">?attr/colorPrimaryVariant</item>
        <!-- Customize other theme attributes -->
        <item name="windowActionBar">false</item>
        <item name="windowNoTitle">true</item>
        <item name="android:windowBackground">@color/white</item> <!-- Default background -->
    </style>

    <!-- Theme for Splash Screen -->
    <style name="Theme.App.Starting" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#4CAF50</item> <!-- Background color -->
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item> <!-- App icon -->
        <item name="postSplashScreenTheme">@style/Theme.SplendorAssist</item> <!-- Theme after splash -->
        <item name="android:statusBarColor">#4CAF50</item>
    </style>

    <!-- Theme for Update Activity -->
    <style name="Theme.UpdateTheme" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="colorPrimary">#FFC107</item> <!-- Amber for updates -->
        <item name="colorPrimaryVariant">#FFA000</item>
        <item name="colorOnPrimary">#000000</item>
        <item name="android:windowBackground">@color/update_background</item> <!-- Define update_background color -->
    </style>

    <!-- Theme for Error Activity -->
    <style name="Theme.ErrorTheme" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="colorPrimary">#F44336</item> <!-- Red for errors -->
        <item name="colorPrimaryVariant">#D32F2F</item>
        <item name="colorOnPrimary">#FFFFFF</item>
        <item name="android:windowBackground">@color/error_background</item> <!-- Define error_background color -->
    </style>

    <!-- Add other themes as needed -->

</resources>
EOF

# Add colors for themes
cat >> app/src/main/res/values/colors.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="white">#FFFFFF</color>
    <color name="black">#000000</color>
    <color name="update_background">#FFF8E1</color> <!-- Light orange for update theme -->
    <color name="error_background">#FFEBEE</color> <!-- Light red for error theme -->
    <!-- Add other colors -->
</resources>
EOF

# Create xml directory for provider_paths and accessibility_service_config if they don't exist
mkdir -p app/src/main/res/xml

# Update provider_paths.xml (ensure it's correctly configured for FileProvider)
cat > app/src/main/res/xml/provider_paths.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Provides access to files in the application's external files directory -->
    <external-path name="external_files" path="."/>
    <!-- For downloads -->
    <external-path name="download_files" path="Download/"/>
</paths>
EOF

# Create accessibility_service_config.xml (for Smart Assist)
cat > app/src/main/res/xml/accessibility_service_config.xml << EOF
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformAccessibilityActions="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="50"
    android:packageNames="com.example.targetapp" // Replace with actual target package name(s) if known
    android:settingsActivity="com.assistant.MainActivity" /> <!-- Link to settings -->
EOF

# Add accessibility service label string
cat >> app/src/main/res/values/strings.xml << EOF
    <string name="app_name">Splendor Assist</string>
    <string name="accessibility_service_label">Splendor Assist - Smart Assist</string>
    <string name="accessibility_service_description">Intercepts and assists with input events for enhanced functionality.</string>
    <string name="vpn_service_label">Splendor Assist - Ping Eliminator</string>
    <string name="vpn_service_description">Manages network traffic to eliminate ping spikes.</string>
EOF

# Create SmartAssistAccessibilityService.kt
cat > app/src/main/java/com/assistant/SmartAssistAccessibilityService.kt << EOF
package com.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.assistant.overlay.R

class SmartAssistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmartAssistService"
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility Service Connected.")
        val info = AccessibilityServiceInfo()
        // Configure service info based on @xml/accessibility_service_config
        // Example:
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100 // milliseconds

        // Set the service info
        this.serviceInfo = info

        // Notify MainActivity or a controller that the service is ready
        // sendBroadcast(Intent("com.assistant.ACCESSIBILITY_SERVICE_READY")) // Example broadcast
        Toast.makeText(this, "Smart Assist Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        Log.d(TAG, "Accessibility Event: Type=${getEventTypeString(event.eventType)}, Package=${event.packageName}, Text=${event.text}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Handle click events - potentially trigger actions based on context
                val sourceNode = event.source
                if (sourceNode != null) {
                    // Example: If a specific button is clicked, perform an action
                    // if (sourceNode.text.toString().contains("Start Game")) {
                    //     Log.d(TAG, "Start Game button clicked.")
                    //     // Perform game start logic
                    // }
                    sourceNode.recycle() // Recycle the node
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Handle text changes - potentially for input interception or modification
                val sourceNode = event.source
                if (sourceNode != null) {
                    // Example: Intercept text input
                    // val enteredText = sourceNode.text.toString()
                    // Log.d(TAG, "Text changed in ${event.packageName}: $enteredText")
                    // Modify text or perform actions based on input
                    sourceNode.recycle()
                }
            }
            // Add other event types as needed
        }
    }

    override fun onInterrupt(service: CharSequence?) {
        Log.w(TAG, "Accessibility Service Interrupted: $service")
        Toast.makeText(this, "Smart Assist Interrupted", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility Service Destroyed.")
        super.onDestroy()
    }

    // Helper to get event type string
    private fun getEventTypeString(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
            else -> "Other ($eventType)"
        }
    }
}
EOF

# Create PingEliminatorVpnService.kt
cat > app/src/main/java/com/assistant/PingEliminatorVpnService.kt << EOF
package com.assistant

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

class PingEliminatorVpnService : VpnService() {

    companion object {
        private const val TAG = "PingEliminatorVpn"
        private const val MTU = 1500 // Maximum Transmission Unit
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var thread: Thread? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VpnService onStartCommand")
        // Start the VPN service in a background thread
        thread = Thread({
            try {
                runVpn()
            } catch (e: Exception) {
                Log.e(TAG, "Error running VPN service", e)
            }
        })
        thread?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VpnService onDestroy")
        running = false
        // Close the VPN interface and resources
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        super.onDestroy()
    }

    private fun runVpn() {
        Log.d(TAG, "Starting VPN interface...")
        // Build the VPN service configuration
        val builder = Builder()
        builder.setMtu(MTU)
        // Add allowed routes (e.g., to capture all traffic)
        // builder.addRoute("0.0.0.0", 0) // Captures all IPv4 traffic
        // builder.addRoute("::", 0) // Captures all IPv6 traffic

        // Add specific routes if needed, e.g., for game servers
        // builder.addRoute("192.168.1.0", 24)

        try {
            vpnInterface = builder.establish()
            input = FileInputStream(vpnInterface?.fileDescriptor)
            output = FileOutputStream(vpnInterface?.fileDescriptor)
            running = true
            Log.d(TAG, "VPN interface established successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            running = false
            return
        }

        // Main VPN loop: read packets from the interface, process, and write back
        while (running) {
            try {
                // Read a packet from the VPN interface
                val packet = readPacket()
                if (packet == null) {
                    Log.w(TAG, "Received null packet, stopping VPN.")
                    running = false
                    break
                }

                // Process the packet (e.g., filter ping packets)
                val processedPacket = processPacket(packet)

                // Write the packet back to the VPN interface (or a modified version)
                if (processedPacket != null) {
                    writePacket(processedPacket)
                }

            } catch (e: Exception) {
                if (running) { // Log only if the service is supposed to be running
                    Log.e(TAG, "Error in VPN packet processing loop", e)
                }
                running = false // Stop on error
            }
        }

        Log.d(TAG, "VPN service loop finished.")
        stopSelf() // Stop the service when the loop ends
    }

    // Reads a VPN packet from the input stream
    private fun readPacket(): ByteBuffer? {
        val buffer = ByteBuffer.allocate(MTU)
        val lenBuffer = ByteBuffer.allocate(4) // For packet length
        try {
            // Read the 4-byte length prefix
            if (input?.read(lenBuffer.array()) ?: -1 == -1) return null
            val length = lenBuffer.getInt()

            // Read the packet data
            if (input?.read(buffer.array(), 0, length) ?: -1 == -1) return null
            buffer.limit(length) // Set the limit to the actual packet length
            return buffer
        } catch (e: Exception) {
            Log.e(TAG, "Error reading packet", e)
            return null
        }
    }

    // Writes a VPN packet to the output stream
    private fun writePacket(packet: ByteBuffer) {
        val lenBuffer = ByteBuffer.allocate(4)
        lenBuffer.putInt(packet.limit()) // Write packet length
        try {
            output?.write(lenBuffer.array())
            output?.write(packet.array(), 0, packet.limit())
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing packet", e)
        }
    }

    // Processes the packet: This is where ping elimination logic would go.
    // For now, it simply passes the packet through.
    private fun processPacket(packet: ByteBuffer): ByteBuffer? {
        // TODO: Implement ping packet filtering logic here.
        // This might involve inspecting IP headers and potentially dropping ICMP echo requests.
        // For now, return the original packet.
        Log.v(TAG, "Processing packet of size: ${packet.limit()}")

        // Example: Basic IP packet inspection (simplified)
        // val ipHeaderLength = (packet.get(0).toInt() and 0x0F) * 4 // Offset of the IP header
        // val protocol = packet.get(9).toInt() // Protocol field in IP header

        // If protocol is ICMP (1) and it's an echo request (type 8), potentially drop it.
        // if (protocol == 1 && packet.get(ipHeaderLength) == 8.toByte()) {
        //    Log.d(TAG, "Dropping ICMP echo request.")
        //    return null // Drop the packet
        // }

        // Return the packet to be written back to the interface
        return packet
    }
}
EOF

# Create PingEliminatorVpnService.kt (ensure it's correctly placed and has basic structure)
cat > app/src/main/java/com/assistant/PingEliminatorVpnService.kt << EOF
package com.assistant

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import java.nio.ByteBuffer

class PingEliminatorVpnService : VpnService() {

    companion object {
        private const val TAG = "PingEliminatorVpn"
        private const val MTU = 1500 // Maximum Transmission Unit
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var thread: Thread? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VpnService onStartCommand")
        // Start the VPN service in a background thread
        thread = Thread({
            try {
                runVpn()
            } catch (e: Exception) {
                Log.e(TAG, "Error running VPN service", e)
            }
        })
        thread?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VpnService onDestroy")
        running = false
        // Close the VPN interface and resources
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        super.onDestroy()
    }

    private fun runVpn() {
        Log.d(TAG, "Starting VPN interface...")
        // Build the VPN service configuration
        val builder = Builder()
        builder.setMtu(MTU)
        // Configure routes to capture traffic - this setup captures all traffic
        try {
            builder.addAddress("10.0.0.2", 32) // Assign an IP address to the VPN interface
            builder.addRoute("0.0.0.0", 0)    // Route all IPv4 traffic through the VPN
            builder.addRoute("::", 0)       // Route all IPv6 traffic through the VPN
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure VPN routes", e)
            running = false
            return
        }


        try {
            vpnInterface = builder.establish()
            input = FileInputStream(vpnInterface?.fileDescriptor)
            output = FileOutputStream(vpnInterface?.fileDescriptor)
            running = true
            Log.d(TAG, "VPN interface established successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            running = false
            return
        }

        // Main VPN loop: read packets from the interface, process, and write back
        while (running) {
            try {
                // Read a packet from the VPN interface
                val packet = readPacket()
                if (packet == null) {
                    Log.w(TAG, "Received null packet, stopping VPN.")
                    running = false
                    break
                }

                // Process the packet (e.g., filter ping packets)
                val processedPacket = processPacket(packet)

                // Write the packet back to the VPN interface (or a modified version)
                if (processedPacket != null) {
                    writePacket(processedPacket)
                }

            } catch (e: Exception) {
                if (running) { // Log only if the service is supposed to be running
                    Log.e(TAG, "Error in VPN packet processing loop", e)
                }
                running = false // Stop on error
            }
        }

        Log.d(TAG, "VPN service loop finished.")
        stopSelf() // Stop the service when the loop ends
    }

    // Reads a VPN packet from the input stream
    private fun readPacket(): ByteBuffer? {
        val buffer = ByteBuffer.allocate(MTU)
        val lenBuffer = ByteBuffer.allocate(4) // For packet length
        try {
            // Read the 4-byte length prefix
            if (input?.read(lenBuffer.array()) ?: -1 < 4) return null
            val length = lenBuffer.getInt()

            // Read the packet data
            if (input?.read(buffer.array(), 0, length) ?: -1 < length) return null
            buffer.limit(length) // Set the limit to the actual packet length
            return buffer
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Error reading packet", e)
            return null
        }
    }

    // Writes a VPN packet to the output stream
    private fun writePacket(packet: ByteBuffer) {
        val lenBuffer = ByteBuffer.allocate(4)
        lenBuffer.putInt(packet.limit()) // Write packet length
        try {
            output?.write(lenBuffer.array())
            output?.write(packet.array(), 0, packet.limit())
            output?.flush()
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Error writing packet", e)
        }
    }

    // Processes the packet: This is where ping elimination logic would go.
    // For now, it simply passes the packet through.
    private fun processPacket(packet: ByteBuffer): ByteBuffer? {
        // TODO: Implement ping packet filtering logic here.
        // This might involve inspecting IP headers and potentially dropping ICMP echo requests.
        // For now, return the original packet.
        Log.v(TAG, "Processing packet of size: ${packet.limit()}")

        // Example: Basic IP packet inspection (simplified)
        // val ipHeaderLength = (packet.get(0).toInt() and 0x0F) * 4 // Offset of the IP header
        // val protocol = packet.get(9).toInt() // Protocol field in IP header

        // If protocol is ICMP (1) and it's an echo request (type 8), potentially drop it.
        // if (protocol == 1 && packet.get(ipHeaderLength) == 8.toByte()) {
        //    Log.d(TAG, "Dropping ICMP echo request.")
        //    return null // Drop the packet
        // }

        // Return the packet to be written back to the interface
        return packet
    }
}
EOF

# Create the XML configuration file for the Accessibility Service
cat > app/src/main/res/xml/accessibility_service_config.xml << EOF
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformAccessibilityActions="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:settingsActivity="com.assistant.MainActivity" />
EOF

# Update app/src/main/java/com/assistant/OverlayService.kt with video recording and service management logic
cat > app/src/main/java/com/assistant/OverlayService.kt << EOF
package com.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.assistant.overlay.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "efootball_assistant_channel"
        private const val NOTIFICATION_ID = 101
        private const val OCR_PROCESSING_INTERVAL_MS = 1000L // Scan screen once per second
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    // OCR Throttle State
    private val isProcessingOcr = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Video Recording placeholders
    private var isRecording = false
    private var videoFileOutputStream: FileOutputStream? = null // Placeholder for video output
    private var recordingStartTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.extras

        if (code == Activity.RESULT_CANCELED || data == null) {
            Log.e(TAG, "MediaProjection data is missing or invalid. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize components only once or if MediaProjection needs re-initialization
        if (mediaProjection == null) {
            initializeComponents()
            initializeMediaProjection(code, data)
            startOcrProcessing()
            startVideoRecording() // Start recording when service starts
        } else {
            // Service is already running, potentially update data or re-initialize MediaProjection if needed
             Log.d(TAG, "Service already running, re-initializing MediaProjection.")
             mediaProjection?.unregisterCallback(projectionCallback!!)
             initializeMediaProjection(code, data)
        }

        return START_STICKY
    }

    private fun initializeComponents() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutInflater = LayoutInflater.from(this)
        overlayView = layoutInflater.inflate(R.layout.overlay_layout, null) // Assuming overlay_layout.xml exists

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        try {
            windowManager.addView(overlayView, params)
            overlayView.visibility = View.VISIBLE
            Log.d(TAG, "Overlay initialized and added.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            Toast.makeText(this, "Failed to display overlay. Check permissions.", Toast.LENGTH_LONG).show()
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundService(notification: Notification, foregroundServiceType: Int) {
        ContextCompat.startForegroundService(this, notification, foregroundServiceType)
        Log.d(TAG, "Foreground service started.")
    }


    private fun initializeMediaProjection(code: Int, data: Intent?) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(code, data ?: Intent())

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection instance.")
            stopSelf()
            return
        }

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped.")
                handler.removeCallbacks(ocrRunnable)
                virtualDisplay?.release()
                imageReader?.close()
                mediaProjection?.unregisterCallback(this)
                stopSelf()
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, handler)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, ImageFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (isProcessingOcr.compareAndSet(false, true)) {
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    processImageForOCR(image)
                    image.close()
                }
                isProcessingOcr.set(false)
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay.")
            stopSelf()
            return
        }
        Log.d(TAG, "MediaProjection and VirtualDisplay initialized.")
    }

    private fun processImageForOCR(image: Image) {
        val planes = image.planes
        val yuvImage = planes[0].buffer
        val width = image.width
        val height = image.height
        val rowStride = planes[0].rowStride
        val pixelStride = planes[0].pixelStride

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(yuvImage)

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val recognizedText = result.text
                Log.d(TAG, "OCR Result: $recognizedText")

                // TODO: Update overlay UI with recognized text
                // runOnUiThread {
                //     overlayView.findViewById<TextView>(R.id.overlay_text_view).text = recognizedText
                // }

                // Video Recording: Capture frame data if recording
                if (isRecording) {
                    captureFrameForVideo(bitmap)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR processing failed", e)
            }
            .addOnCompleteListener {
                bitmap.recycle() // Release bitmap memory
            }
    }

    private fun startOcrProcessing() {
        Log.d(TAG, "Starting OCR processing loop.")
        handler.post(ocrRunnable)
    }

    private val ocrRunnable = object : Runnable {
        override fun run() {
            if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
                Log.w(TAG, "Cannot start OCR: MediaProjection not ready.")
                handler.postDelayed(this, OCR_PROCESSING_INTERVAL_MS)
                return
            }
            // The ImageReader listener handles the actual processing and throttling.
            // This runnable ensures the listener is implicitly triggered by screen updates.
            handler.postDelayed(this, OCR_PROCESSING_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        handler.removeCallbacks(ocrRunnable)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback!!)
        mediaProjection?.stop()

        stopVideoRecording() // Stop video recording

        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
                Log.d(TAG, "Overlay view removed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Splendor Assist Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service channel for Splendor Assist"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    // Video Recording Implementation
    private fun startVideoRecording() {
        if (!isRecording) {
            Log.d(TAG, "Starting video recording...")
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            val fileName = "splendor_assist_recording_${recordingStartTime}.mp4"
            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (directory == null) {
                Log.e(TAG, "Failed to get external storage directory for video.")
                Toast.makeText(this, "Cannot start recording: Storage unavailable.", Toast.LENGTH_SHORT).show()
                isRecording = false
                return
            }

            val outputFile = File(directory, fileName)
            try {
                videoFileOutputStream = FileOutputStream(outputFile)
                // TODO: Initialize MediaRecorder or equivalent here, using videoFileOutputStream and virtualDisplay surface
                // Example using MediaRecorder (requires more setup):
                // mediaRecorder = MediaRecorder().apply {
                //     setVideoSource(MediaRecorder.VideoSource.SURFACE)
                //     setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                //     setOutputFile(outputFile.absolutePath)
                //     setVideoEncodingBitRate(10 * 1024 * 1024)
                //     setVideoFrameRate(30)
                //     setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                //     prepare()
                //     start()
                // }
                Log.d(TAG, "Video recording started to: ${outputFile.absolutePath}")
                Toast.makeText(this, "Video recording started.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video recording", e)
                Toast.makeText(this, "Failed to start video recording.", Toast.LENGTH_SHORT).show()
                isRecording = false
                videoFileOutputStream?.close()
                videoFileOutputStream = null
            }
        }
    }

    private fun stopVideoRecording() {
        if (isRecording) {
            Log.d(TAG, "Stopping video recording...")
            isRecording = false
            try {
                // TODO: Release MediaRecorder if used
                // mediaRecorder?.stop()
                // mediaRecorder?.release()
                // mediaRecorder = null

                videoFileOutputStream?.close()
                videoFileOutputStream = null
                val recordingDuration = System.currentTimeMillis() - recordingStartTime
                Log.d(TAG, "Video recording stopped. Duration: ${recordingDuration}ms")
                Toast.makeText(this, "Video recording stopped.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video recording", e)
            }
        }
    }

    // Capture a frame from the bitmap for video recording
    private fun captureFrameForVideo(bitmap: Bitmap) {
        if (!isRecording || videoFileOutputStream == null) return

        // This is a placeholder. In a real implementation, you would encode the bitmap
        // into a video frame and write it to the output stream using MediaCodec or similar.
        Log.v(TAG, "Capturing frame for video recording (bitmap size: ${bitmap.width}x${bitmap.height}).")

        // Example: Compress bitmap to JPEG and write to FileOutputStream (very basic, not true video)
        try {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, videoFileOutputStream)
            // A real video recorder would handle frame encoding and buffering.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture frame for video", e)
        }
    }

     // Called from MainActivity to check if service is running
     fun isRunning(): Boolean = !isProcessingOcr.get() && mediaProjection != null
}
EOF

# Add missing imports and necessary methods to MainActivity.kt for UI overhaul and new features
cat > app/src/main/java/com/assistant/MainActivity.kt << EOF
package com.assistant

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.assistant.overlay.R
import com.google.android.material.textview.MaterialTextView // Use Material Design TextView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen // For splash screen integration

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_NOTIFICATION_ID = "notification_permission"
        private const val PERMISSION_OVERLAY_ID = "overlay_permission"
        private const val PERMISSION_INSTALL_PACKAGES_ID = "install_packages_permission"
    }

    private lateinit var projectionManager: MediaProjectionManager

    // Activity Result Launchers
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            EngineData.code = result.resultCode
            EngineData.intent = result.data
            startOverlayService()
            Toast.makeText(this, "Hybrid Coach Engine Online", Toast.LENGTH_LONG).show()
            // Optionally, navigate to a dashboard or main screen after successful start
            // startActivity(Intent(this, DashboardActivity::class.java))
            // finish()
        } else {
            Toast.makeText(this, "Engine Authorization Denied.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "MediaProjection denied or failed. ResultCode: ${result.resultCode}")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted.")
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "Notifications required for background engine.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Notification permission denied.")
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission granted.")
            requestNotificationPermission()
        } else {
            Toast.makeText(this, "Overlay Permission Required.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Overlay permission denied.")
        }
    }

    private val installPackagesPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Install packages permission granted.")
        } else {
            Log.w(TAG, "Install packages permission denied.")
            Toast.makeText(this, "Install Packages permission denied. Updates may not be installable.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Handle the splash screen integration
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenOn(true) // Keep the splash screen visible until drawing is done

        super.onCreate(savedInstanceState)
        // Use layout binding for better UI interaction
        // val binding = ActivityMainBinding.inflate(layoutInflater)
        // setContentView(binding.root)
        setContentView(R.layout.activity_main) // Assuming activity_main.xml exists for the dashboard

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Setup UI elements (replace with view binding if preferred)
        val btnStartEngine = findViewById<Button>(R.id.btnStartEngine)
        val btnViewHistory = findViewById<Button>(R.id.btnViewHistory)
        val txtUpdateStatus = findViewById<MaterialTextView>(R.id.txtUpdateStatus) // For update status messages

        btnStartEngine.setOnClickListener { checkPermissionsAndStartEngine() }
        btnViewHistory.setOnClickListener { openMatchHistory() }

        // Check for updates on startup
        checkUpdateStatus(txtUpdateStatus)

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))

        // Check and request INSTALL_PACKAGES permission if on Android O+ and not granted
        checkAndRequestInstallPackagesPermission()
    }

    private fun checkUpdateStatus(updateStatusTextView: MaterialTextView) {
        // Simulate update check based on EngineData flag
        if (EngineData.isUpdateAvailable && EngineData.updateUrl != null) {
            updateStatusTextView.visibility = View.VISIBLE
            updateStatusTextView.text = "Update available! Tap here to install."
            updateStatusTextView.setOnClickListener {
                val updateIntent = Intent(this, UpdateActivity::class.java).apply {
                    putExtra("updateUrl", EngineData.updateUrl ?: "")
                }
                startActivity(updateIntent)
                // finish() // Optionally finish MainActivity if UpdateActivity is meant to replace it
            }
        } else {
            updateStatusTextView.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndStartEngine() {
        // Request Notification permission first if not granted (for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return // Wait for permission result
            }
        }
        // Then check Overlay permission
        checkOverlayPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting notification permission.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Notification permission already granted.")
                checkOverlayPermission() // Proceed if already granted
            }
        } else {
             checkOverlayPermission() // No notification permission needed below Tiramisu
        }
    }


    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Requesting overlay permission.")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                Log.d(TAG, "Overlay permission already granted.")
                requestScreenCapture() // Proceed to request screen capture
            }
        } else {
            requestScreenCapture() // Overlays generally allowed pre-M
        }
    }

    private fun requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission.")
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startOverlayService() {
        if (!isServiceRunning(OverlayService::class.java)) {
            Log.d(TAG, "Starting OverlayService.")
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("code", EngineData.code)
                // Pass the data Intent extras if needed, be mindful of size limitations
                // Using putExtras on a new Intent to avoid modifying the original EngineData.intent
                putExtras(Intent().apply { data = EngineData.intent?.data })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Splendor Assist Engine Initializing...", Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "OverlayService is already running.")
            // Optionally send a new intent to the running service to re-trigger actions
             val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("code", EngineData.code)
                putExtras(Intent().apply { data = EngineData.intent?.data })
            }
             startService(serviceIntent) // Restart service logic if needed
        }
    }

    private fun isServiceRunning(serviceClass: Class<out Service>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION") // ServiceInfo.SERVICE_BACKGROUND is deprecated but needed for older SDKs
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name && (it.serviceInfo.flags and ServiceInfo.FLAG_FOREGROUND_SERVICE) != 0
        }
    }

    private fun openMatchHistory() {
        Toast.makeText(this, "Opening Match History...", Toast.LENGTH_SHORT).show()
        // Placeholder for navigating to Match History UI
        // Example: startActivity(Intent(this, MatchHistoryActivity::class.java))
    }

    // Check and request INSTALL_PACKAGES permission if on Android O+ and not granted
    private fun checkAndRequestInstallPackagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.REQUEST_INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting INSTALL_PACKAGES permission.")
                installPackagesPermissionLauncher.launch(Manifest.permission.REQUEST_INSTALL_PACKAGES)
            } else {
                Log.d(TAG, "INSTALL_PACKAGES permission already granted.")
            }
        }
    }
}

// Define styles in res/values/styles.xml
// Define colors in res/values/colors.xml
// Define layouts in res/layout/activity_main.xml, activity_update.xml, activity_error.xml, overlay_layout.xml, activity_splash.xml
// Define drawables in res/drawable/
// Define strings in res/values/strings.xml
// Define accessibility service config in res/xml/accessibility_service_config.xml
EOF

# Create the UI overhaul for MainActivity (activity_main.xml)
cat > app/src/main/res/layout/activity_main.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/gradient_background_main"
    tools:context=".MainActivity">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="24dp">

        <ImageView
            android:layout_width="120dp"
            android:layout_height="120dp"
            android:layout_marginBottom="32dp"
            android:src="@drawable/ic_splendor_logo_colorful"
            tools:ignore="ContentDescription" />

        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/txtUpdateStatus"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="32dp"
            android:textAlignment="center"
            android:textAppearance="@style/TextAppearance.MaterialComponents.Body1"
            android:textColor="@color/white"
            android:visibility="gone"
            tools:text="Update available! Tap here to install." />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnStartEngine"
            android:layout_width="match_parent"
            android:layout_height="60dp"
            android:text="Start Full Engine"
            android:textSize="18sp"
            android:textStyle="bold"
            app:cornerRadius="24dp"
            app:backgroundTint="@color/button_primary_gradient" /> <!-- Use gradient drawable -->

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnViewHistory"
            android:layout_width="match_parent"
            android:layout_height="60dp"
            android:layout_marginTop="24dp"
            android:text="Match History"
            android:textSize="18sp"
            android:textStyle="bold"
            app:cornerRadius="24dp"
            app:backgroundTint="@color/button_secondary_gradient" /> <!-- Use gradient drawable -->

        <!-- Add other dashboard elements here -->

    </LinearLayout>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
EOF

# Create gradient background for MainActivity
cat > app/src/main/res/drawable/gradient_background_main.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="135"
        android:startColor="#8BC34A"
        android:endColor="#00BCD4"
        android:type="linear" />
</shape>
EOF

# Create gradient drawables for buttons
cat > app/src/main/res/color/button_primary_gradient.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape>
            <gradient
                android:angle="135"
                android:startColor="#689F38"
                android:endColor="#0097A7"
                android:type="linear" />
            <corners android:radius="24dp" />
        </shape>
    </item>
    <item>
        <shape>
            <gradient
                android:angle="135"
                android:startColor="#8BC34A"
                android:endColor="#00BCD4"
                android:type="linear" />
            <corners android:radius="24dp" />
        </shape>
    </item>
</selector>
EOF

cat > app/src/main/res/color/button_secondary_gradient.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape>
            <gradient
                android:angle="135"
                android:startColor="#00838F"
                android:endColor="#00796B"
                android:type="linear" />
            <corners android:radius="24dp" />
        </shape>
    </item>
    <item>
        <shape>
            <gradient
                android:angle="135"
                android:startColor="#4DD0E1"
                android:endColor="#80CBC4"
                android:type="linear" />
            <corners android:radius="24dp" />
        </shape>
    </item>
</selector>
EOF

# Create a placeholder colorful logo
cat > app/src/main/res/drawable/ic_splendor_logo_colorful.xml << EOF
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,17.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,7.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,2.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,12.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M19,7.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M19,12.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M19,17.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M5,7.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M5,12.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
  <path
      android:fillColor="@android:color/white"
      android:pathData="M5,17.5a1.5,1.5 0,1 1,-3 0a1.5,1.5 0,0 1,3 0z"/>
</vector>
EOF

# Create UI overhaul for UpdateActivity (activity_update.xml)
cat > app/src/main/res/layout/activity_update.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/update_background"
    tools:context=".UpdateActivity">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="24dp">

        <ImageView
            android:layout_width="100dp"
            android:layout_height="100dp"
            android:layout_marginBottom="32dp"
            android:src="@drawable/ic_update_icon_colorful"
            tools:ignore="ContentDescription" />

        <TextView
            android:id="@+id/progressText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:layout_marginTop="16dp"
            android:text="Checking for updates..."
            android:textAlignment="center"
            android:textAppearance="@style/TextAppearance.AppCompat.Body1"
            android:textColor="@color/black" />

        <ProgressBar
            android:id="@+id/updateProgressBar"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:indeterminate="true"
            android:progressTint="@color/colorPrimary" />

        <Button
            android:id="@+id/btnDownloadUpdate"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:text="Download Update" />

        <Button
            android:id="@+id/btnSkipUpdate"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:backgroundTint="@android:color/transparent"
            android:text="Skip Update"
            android:textColor="@color/black" />

    </LinearLayout>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
EOF

# Create ic_update_icon_colorful.xml
cat > app/src/main/res/drawable/ic_update_icon_colorful.xml << EOF
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#D32F2F"> <!-- Red tint for update icon -->
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10s10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,10h-2V7h2v3z"/>
</vector>
EOF

# Create UI overhaul for ErrorActivity (activity_error.xml)
cat > app/src/main/res/layout/activity_error.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/error_background"
    tools:context=".ErrorActivity">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:gravity="center"
        android:padding="24dp">

        <ImageView
            android:layout_width="100dp"
            android:layout_height="100dp"
            android:layout_marginBottom="32dp"
            android:src="@drawable/ic_error_icon_colorful"
            tools:ignore="ContentDescription" />

        <TextView
            android:id="@+id/txtCrashLog"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:layout_gravity="center"
            android:layout_marginTop="16dp"
            android:text="Error details will appear here."
            android:textAppearance="@style/TextAppearance.AppCompat.Body1"
            android:textColor="@color/black"
            android:scrollbars="vertical" />

        <Button
            android:id="@+id/btnCopyLog"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="32dp"
            android:text="Copy Log" />

    </LinearLayout>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
EOF

# Create ic_error_icon_colorful.xml
cat > app/src/main/res/drawable/ic_error_icon_colorful.xml << EOF
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#D32F2F"> <!-- Red tint for error icon -->
  <path
      android:fillColor="@android:color/white"
      android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10s10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,10h-2V7h2v3z"/>
</vector>
EOF

# Create a placeholder overlay_layout.xml
cat > app/src/main/res/layout/overlay_layout.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:padding="8dp"
    android:background="@drawable/overlay_background"> <!-- Define overlay_background -->

    <TextView
        android:id="@+id/overlay_text_view"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textColor="@android:color/white"
        android:textSize="12sp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        tools:text="OCR Text Here" />

    <!-- Add other overlay elements like buttons, indicators -->

</androidx.constraintlayout.widget.ConstraintLayout>
EOF

# Create overlay_background.xml
cat > app/src/main/res/drawable/overlay_background.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#80000000" /> <!-- Semi-transparent black -->
    <corners android:radius="16dp" />
</shape>
EOF

# Add placeholder for Match History Activity (MatchHistoryActivity.kt)
# This is a skeleton and requires further implementation for UI and logic.
cat > app/src/main/java/com/assistant/MatchHistoryActivity.kt << EOF
package com.assistant

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R
// Import necessary UI components like RecyclerView, CardView, etc.

class MatchHistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MatchHistoryActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_history) // Create this layout file

        Log.d(TAG, "Match History Activity created.")

        // TODO: Implement UI for displaying match history
        // - Load data from EngineData.matchHistoryJson
        // - Populate RecyclerView with match data (stats, video replay info)
        // - Implement click listeners for video replays
    }

    // Placeholder function to play video replay
    private fun playVideoReplay(replayData: ByteArray?) {
        if (replayData == null) {
            Toast.makeText(this, "No replay data available.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "Playing video replay...")
        // TODO: Implement video playback using the replayData
        // This might involve saving the byte array to a temporary file and using a VideoView or ExoPlayer.
    }
}
EOF

# Create activity_match_history.xml (placeholder)
cat > app/src/main/res/layout/activity_match_history.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/gradient_background_history"
    tools:context=".MatchHistoryActivity">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Match History"
            android:textAppearance="@style/TextAppearance.AppCompat.Large"
            android:textStyle="bold"
            android:gravity="center"
            android:layout_marginBottom="16dp"/>

        <!-- RecyclerView or CardView for displaying match history items -->
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/matchHistoryRecyclerView"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            tools:listitem="@layout/item_match_history" /> <!-- Define item_match_history layout -->

    </LinearLayout>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
EOF

# Create gradient background for Match History
cat > app/src/main/res/drawable/gradient_background_history.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="135"
        android:startColor="#FF9800"
        android:endColor="#FF5722"
        android:type="linear" />
</shape>
EOF

# Create item_match_history.xml (placeholder)
cat > app/src/main/res/layout/item_match_history.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="8dp"
    app:cardCornerRadius="8dp"
    app:cardElevation="4dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/txtMatchTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Match Title"
            android:textAppearance="@style/TextAppearance.AppCompat.Medium"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/txtMatchStats"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Advanced Stats..."
            android:textAppearance="@style/TextAppearance.AppCompat.Small"
            android:layout_marginTop="4dp"/>

        <Button
            android:id="@+id/btnReplayVideo"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Replay Video"
            android:layout_gravity="end"
            android:layout_marginTop="8dp"/>

    </LinearLayout>

</androidx.cardview.widget.CardView>
EOF

echo "Project architecture upgrade initiated."
echo "Generated/Updated files:"
echo "- app/build.gradle.kts"
echo "- app/src/main/java/com/assistant/EngineData.kt"
echo "- app/src/main/java/com/assistant/ErrorActivity.kt"
echo "- app/src/main/java/com/assistant/MainActivity.kt"
echo "- app/src/main/java/com/assistant/OverlayService.kt"
echo "- app/src/main/java/com/assistant/UpdateActivity.kt"
echo "- app/src/main/java/com/assistant/GlobalCrashHandler.kt"
echo "- app/src/main/java/com/assistant/SmartAssistAccessibilityService.kt"
echo "- app/src/main/java/com/assistant/PingEliminatorVpnService.kt"
echo "- app/src/main/java/com/assistant/SplashActivity.kt"
echo "- app/src/main/java/com/assistant/MatchHistoryActivity.kt"
echo "- app/src/main/AndroidManifest.xml"
echo "- app/src/main/res/layout/activity_main.xml"
echo "- app/src/main/res/drawable/gradient_background_main.xml"
echo "- app/src/main/res/color/button_primary_gradient.xml"
echo "- app/src/main/res/color/button_secondary_gradient.xml"
echo "- app/src/main/res/drawable/ic_splendor_logo_colorful.xml"
echo "- app/src/main/res/layout/activity_update.xml"
echo "- app/src/main/res/drawable/ic_update_icon_colorful.xml"
echo "- app/src/main/res/layout/activity_error.xml"
echo "- app/src/main/res/drawable/ic_error_icon_colorful.xml"
echo "- app/src/main/res/layout/overlay_layout.xml"
echo "- app/src/main/res/drawable/overlay_background.xml"
echo "- app/src/main/res/layout/activity_match_history.xml"
echo "- app/src/main/res/drawable/gradient_background_history.xml"
echo "- app/src/main/res/layout/item_match_history.xml"
echo "- app/src/main/res/xml/accessibility_service_config.xml"
echo "- app/src/main/res/values/styles.xml"
echo "- app/src/main/res/values/colors.xml"
echo "- app/src/main/res/values/strings.xml"
echo "- app/src/main/res/layout/activity_splash.xml"
echo "- app/src/main/res/drawable/splash_background.xml"

echo "Please sync your project with Gradle files and rebuild."

# Sync Gradle - replace with actual command if needed, e.g., ./gradlew sync
# Example: ./gradlew build --scan

# Run a clean build (optional, but recommended after major changes)
# ./gradlew clean build --scan
package com.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R

class ErrorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)

        val log = intent.getStringExtra("CRASH_LOG") ?: "No log provided."
        val txtLog = findViewById<TextView>(R.id.txtCrashLog)
        val btnCopy = findViewById<Button>(R.id.btnCopyLog)

        txtLog.text = log

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", log)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log Copied. Send to Lead Engineer.", Toast.LENGTH_LONG).show()
        }
    }
}
