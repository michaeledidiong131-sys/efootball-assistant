package com.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

        btnDownload.setOnClickListener {
            Toast.makeText(this@UpdateActivity, "Splendor Assist is up to date.", Toast.LENGTH_SHORT).show()
        }

        btnSkip.setOnClickListener {
            startActivity(Intent(this@UpdateActivity, MainActivity::class.java))
            finish()
        }
    }
}
