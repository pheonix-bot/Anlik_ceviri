package com.krunit.screentranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenTranslateService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_TRANSLATE"
        const val ACTION_STOP = "ACTION_STOP_TRANSLATE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "screen_translate_channel"
        private const val NOTIF_ID = 1042

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: SubtitleOverlay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isProcessing = AtomicBoolean(false)
    private var lastCaptureTime = 0L
    private var lastSourceText = ""
    private var translateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundNotification()
                    startScreenCapture(resultCode, data)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Anlık Ekran Çeviri Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anlık Ekran Çeviri Aktif")
            .setContentText("Ekrandaki İngilizce metinler Türkçeye çevriliyor...")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        if (isRunning) return
        isRunning = true

        overlay = SubtitleOverlay(this) { stopAll() }.apply { show() }

        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels / 2).coerceAtLeast(360)
        val height = (metrics.heightPixels / 2).coerceAtLeast(640)
        val density = metrics.densityDpi / 2

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
            }
        }, captureHandler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()

            if (now - lastCaptureTime < 400 || !isProcessing.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastCaptureTime = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val croppedHeight = (height * 0.82).toInt()
                val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, croppedHeight)
                if (croppedBitmap != fullBitmap) {
                    fullBitmap.recycle()
                }

                runOcrAndTranslate(croppedBitmap)
            } catch (e: Exception) {
                image.close()
                isProcessing.set(false)
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LiveTranslateDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun runOcrAndTranslate(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                bitmap.recycle()
                val extracted = visionText.textBlocks
                    .map { it.text.replace("\\n", " ").trim() }
                    .filter { line -> line.length >= 2 && line.any { it.isLetter() } }
                    .takeLast(3)
                    .joinToString(" | ")

                if (extracted.isNotBlank() && extracted != lastSourceText) {
                    lastSourceText = extracted
                    translateJob?.cancel()
                    translateJob = serviceScope.launch {
                        val translated = GoogleTranslateClient.translateEnToTr(extracted)
                        if (translated.isNotBlank()) {
                            overlay?.updateSubtitle(translated)
                        }
                    }
                }
                isProcessing.set(false)
            }
            .addOnFailureListener {
                bitmap.recycle()
                isProcessing.set(false)
            }
    }

    private fun stopAll() {
        isRunning = false
        translateJob?.cancel()
        overlay?.hide()
        overlay = null
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }
}
