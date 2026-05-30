package com.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 1001
        private const val MEDIA_PROJECTION_REQ_CODE = 1002
    }

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        checkOverlayPermissionAndProceed()
    }

    private fun checkOverlayPermissionAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(), 
            MEDIA_PROJECTION_REQ_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission()
            } else {
                Toast.makeText(this, "Overlay permission is mandatory.", Toast.LENGTH_LONG).show()
                finish()
            }
        } else if (requestCode == MEDIA_PROJECTION_REQ_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startAssistantService(resultCode, data)
            } else {
                Toast.makeText(this, "Screen Capture permission is mandatory.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startAssistantService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Terminate UI immediately
        finish()
    }
}
