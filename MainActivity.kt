package com.krunit.screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            ensureOverlayPermission()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Altyazı göstermek için üstte gösterme izni gerekli.", Toast.LENGTH_LONG).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenTranslateService::class.java).apply {
                    action = ScreenTranslateService.ACTION_START
                    putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                statusText.text = "Durum: Aktif — Uygulamayı alta alıp kullanabilirsiniz."
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Ekran yakalama izni verilmedi.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(56, 64, 56, 64)
        }

        val title = TextView(this).apply {
            text = "ANLIK EKRAN ÇEVİRİ"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Ekranda beliren İngilizce metinleri anında yakalar ve Google Translate ile alt kısımda canlı Türkçe altyazı olarak gösterir."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14.5f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        }

        statusText = TextView(this).apply {
            text = if (ScreenTranslateService.isRunning) "Durum: Aktif" else "Durum: Hazır"
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 36)
        }

        val startBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#0284C7"))
            cornerRadius = 24f
        }
        val startBtn = Button(this).apply {
            text = "CANLI ÇEVİRİYİ BAŞLAT"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = startBtnBg
            setPadding(48, 32, 48, 32)
            setOnClickListener { startFlow() }
        }

        val stopBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = 24f
            setStroke(2, Color.parseColor("#475569"))
        }
        val stopBtn = Button(this).apply {
            text = "DURDUR"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = stopBtnBg
            setPadding(48, 24, 48, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
            layoutParams = lp
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, ScreenTranslateService::class.java).apply {
                    action = ScreenTranslateService.ACTION_STOP
                }
                startService(stopIntent)
                statusText.text = "Durum: Durduruldu"
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)
    }

    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureOverlayPermission()
        }
    }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Açılan ekrandan 'Üstte göster' iznini açın.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
