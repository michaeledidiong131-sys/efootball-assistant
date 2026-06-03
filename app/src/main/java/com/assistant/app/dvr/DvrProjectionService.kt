package com.assistant.app.dvr

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import androidx.core.app.ServiceCompat
import java.nio.ByteBuffer

class DvrProjectionService : Service() {
    private var dvrThread: HandlerThread? = null
    private var dvrHandler: Handler? = null
    private var videoBuffer: ByteBuffer? = null
    private val BUFFER_CAPACITY = 16 * 1024 * 1024

    override fun onCreate() {
        super.onCreate()
        dvrThread = HandlerThread("DvrIOLoop", Process.THREAD_PRIORITY_VIDEO).apply {
            start()
            dvrHandler = Handler(looper)
        }
        videoBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            1001,
            DvrNotificationBuilder.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        dvrHandler?.post { executeRollingRecord() }
        return START_STICKY
    }

    private fun executeRollingRecord() {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        dvrThread?.quitSafely()
        videoBuffer?.clear()
        super.onDestroy()
    }
}