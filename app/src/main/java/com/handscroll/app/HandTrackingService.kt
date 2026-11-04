package com.handscroll.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class HandTrackingService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.handscroll.app.STOP"
        private const val CHANNEL = "handscroll"
        private const val TAG = "HandScroll"
        private const val WINDOW_MS = 500L      // swipe must happen within this time
        private const val FIST_MS = 600L        // hold a fist this long to pause/play
        private const val COOLDOWN_MS = 1200L   // pause between actions
    }

    private class Sample(val t: Long, val x: Float, val y: Float)

    private var landmarker: HandLandmarker? = null
    private lateinit var executor: ExecutorService

    // gesture state (only touched on the analysis thread)
    private val history = ArrayDeque<Sample>()
    private var fistSince = 0L
    private var fistArmed = true
    private var lastFire = 0L

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        if (!HandState.running) {
            try {
                createLandmarker()
                startCamera()
                HandState.running = true
                HandState.error = null
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                HandState.error = "Could not start: ${e.message}"
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hand tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, HandTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("HandScroll is watching for hand gestures")
            .setContentText("Tap to open, or press Stop")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun createLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .build()
        landmarker = HandLandmarker.createFromOptions(this, opts)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera failed", e)
                HandState.error = "Camera failed: ${e.message}"
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val bmp = proxy.toBitmap()
            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val result = lm.detectForVideo(
                BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis()
            )
            handle(result)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            proxy.close()
        }
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    private fun handle(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty() || hands[0].size < 21) {
            HandState.handFound = false
            HandState.fist = false
            history.clear()
            fistSince = 0
            fistArmed = true
            return
        }
        val lm = hands[0]
        HandState.handFound = true

        val wrist = lm[0]
        // a finger is folded when its tip is closer to the wrist than its middle joint
        fun folded(tip: Int, pip: Int) = dist(lm[tip], wrist) < dist(lm[pip], wrist)
        val fist = folded(8, 6) && folded(12, 10) && folded(16, 14) && folded(20, 18)

        // palm centre (wrist + knuckles), 0..1 in the upright camera frame
        val ids = intArrayOf(0, 5, 9, 13, 17)
        var cx = 0f
        var cy = 0f
        for (i in ids) { cx += lm[i].x(); cy += lm[i].y() }
        cx /= ids.size
        cy /= ids.size

        process(fist, cx, cy, SystemClock.uptimeMillis())
    }

    private fun process(fist: Boolean, cx: Float, cy: Float, now: Long) {
        HandState.fist = fist

        if (fist) {
            history.clear()
            if (fistSince == 0L) fistSince = now
            if (fistArmed && now - fistSince >= FIST_MS && now - lastFire >= COOLDOWN_MS) {
                fistArmed = false
                lastFire = now
                val svc = ScrollAccessibilityService.instance
                HandState.lastAction = svc?.tapCenter() ?: "Accessibility service is OFF"
            }
            return
        }
        fistSince = 0
        fistArmed = true

        history.addLast(Sample(now, cx, cy))
        while (history.isNotEmpty() && now - history.first().t > WINDOW_MS) history.removeFirst()
        if (history.size < 4 || now - lastFire < COOLDOWN_MS) return

        val first = history.first()
        val last = history.last()
        val dx = last.x - first.x
        val dy = last.y - first.y
        val thr = Prefs.threshold(this)
        if (abs(dy) > thr && abs(dy) > 1.5f * abs(dx)) {
            lastFire = now
            history.clear()
            val next = dy < 0   // hand moved UP -> next reel
            val svc = ScrollAccessibilityService.instance
            HandState.lastAction = svc?.scroll(next) ?: "Accessibility service is OFF"
        }
    }

    override fun onDestroy() {
        HandState.running = false
        HandState.handFound = false
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
        executor.shutdown()
        super.onDestroy()
    }
}
