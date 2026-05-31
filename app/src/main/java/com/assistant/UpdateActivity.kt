package com.assistant

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class UpdateActivity : AppCompatActivity() {

    private var downloadId: Long = -1L
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnDownload: Button
    
    // Placeholder URL - Must be a direct .apk link in production
    private val updateUrl = "https://github.com/your-repo/efootball-assistant/releases/latest/download/app-debug.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        btnDownload = findViewById(R.id.btnDownloadUpdate)
        val btnSkip = findViewById<Button>(R.id.btnSkipUpdate)
        progressBar = findViewById(R.id.updateProgressBar)
        progressText = findViewById(R.id.progressText)

        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE

        btnDownload.setOnClickListener {
            startDownload()
        }

        btnSkip.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun startDownload() {
        btnDownload.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE

        val request = DownloadManager.Request(Uri.parse(updateUrl))
            .setTitle("Splendor Assist Update")
            .setDescription("Downloading core engine update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update.apk")

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        // Delete old update file if exists
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) file.delete()

        downloadId = downloadManager.enqueue(request)
        trackProgress(downloadManager)
    }

    @SuppressLint("Range")
    private fun trackProgress(manager: DownloadManager) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = manager.query(query)
                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                        handler.post {
                            progressBar.progress = 100
                            progressText.text = "100%"
                            installApk()
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        handler.post {
                            Toast.makeText(this, "Download Failed.", Toast.LENGTH_SHORT).show()
                            btnDownload.isEnabled = true
                        }
                    } else {
                        val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()
                        handler.post {
                            progressBar.progress = progress
                            progressText.text = "$progress%"
                        }
                    }
                }
                cursor.close()
                try { Thread.sleep(500) } catch (e: Exception) { }
            }
        }.start()
    }

    private fun installApk() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }
}
