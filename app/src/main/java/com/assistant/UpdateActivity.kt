package com.assistant

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import com.assistant.overlay.R

class UpdateActivity : AppCompatActivity() {

    private var downloadId: Long = -1L
    private lateinit var downloadManager: DownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val btnDownload = findViewById<Button>(R.id.btnDownloadUpdate)
        val btnSkip = findViewById<Button>(R.id.btnSkipUpdate)

        btnDownload.setOnClickListener {
            startDownload()
            btnDownload.isEnabled = false
            btnSkip.isEnabled = false
        }

        btnSkip.setOnClickListener {
            // Correctly route to MainActivity
            startActivity(Intent(this@UpdateActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun startDownload() {
        val updateUrl = "https://example.com/splendor_assist_latest.apk" 
        val request = DownloadManager.Request(Uri.parse(updateUrl))
            .setTitle("Splendor Assist")
            .setDescription("Downloading premium update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SplendorAssist_Update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        downloadId = downloadManager.enqueue(request)

        findViewById<ProgressBar>(R.id.updateProgressBar).visibility = View.VISIBLE
        findViewById<TextView>(R.id.progressText).visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        trackProgress()
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                installApk()
            }
        }
    }

    private fun trackProgress() {
        thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    if (bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                        val bytesTotal = cursor.getInt(bytesTotalIndex)
                        
                        if (bytesTotal > 0) {
                            val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()
                            runOnUiThread {
                                findViewById<ProgressBar>(R.id.updateProgressBar).progress = progress
                                findViewById<TextView>(R.id.progressText).text = "$progress%"
                            }
                        }
                    }
                    
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                        }
                    }
                }
                cursor?.close()
                Thread.sleep(500)
            }
        }
    }

    private fun installApk() {
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri != null) {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } else {
            Toast.makeText(this, "Update Error: Package parsing failed.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) {}
    }
}
