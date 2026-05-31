package com.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.overlay.R

class UpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        val btnDownload = findViewById<Button>(R.id.btnDownloadUpdate)
        val btnSkip = findViewById<Button>(R.id.btnSkipUpdate)

        findViewById<ProgressBar>(R.id.updateProgressBar)?.visibility = View.GONE
        findViewById<TextView>(R.id.progressText)?.visibility = View.GONE

        // HOTWIRE: Redirect to OTA Server / Repository Release Page
        btnDownload.setOnClickListener {
            val updateUrl = "https://github.com/your-repo/efootball-assistant/releases/latest"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
            startActivity(browserIntent)
        }

        btnSkip.setOnClickListener {
            startActivity(Intent(this@UpdateActivity, MainActivity::class.java))
            finish()
        }
    }
}
