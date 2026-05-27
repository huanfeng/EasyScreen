package to.feng.app.easyscreen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import to.feng.app.easyscreen.MainActivity
import to.feng.app.easyscreen.webrtc.WebRTCManager

class ScreenCaptureService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "EasyScreen::Capture"

        const val ACTION_START = "to.feng.app.easyscreen.action.START_CAPTURE"
        const val ACTION_STOP = "to.feng.app.easyscreen.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "q_w"
        const val EXTRA_HEIGHT = "q_h"
        const val EXTRA_FPS = "q_fps"
        const val EXTRA_MAX_BR = "q_max_br"
        const val EXTRA_MIN_BR = "q_min_br"

        fun start(
            context: Context, resultCode: Int, data: Intent,
            width: Int, height: Int, fps: Int, maxBr: Int, minBr: Int,
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_MAX_BR, maxBr)
                putExtra(EXTRA_MIN_BR, minBr)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android 14+ 要求：必须先 startForeground 再获取 MediaProjection
                startForeground(NOTIFICATION_ID, createNotification("正在共享屏幕..."))

                // 持 PARTIAL_WAKE_LOCK，避免 MIUI / Doze 把 CPU 挂起导致 UDP 心跳丢失、ICE 断流
                if (wakeLock == null) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                        setReferenceCounted(false)
                        acquire(10 * 60 * 60 * 1000L /* 10h 上限，由 stop 主动释放 */)
                    }
                    Log.d(TAG, "WakeLock acquired")
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    Log.e(TAG, "Missing MediaProjection result data")
                    stopSelf()
                    return START_NOT_STICKY
                }

                try {
                    val w = intent.getIntExtra(EXTRA_WIDTH, 1280)
                    val h = intent.getIntExtra(EXTRA_HEIGHT, 720)
                    val fps = intent.getIntExtra(EXTRA_FPS, 20)
                    val maxBr = intent.getIntExtra(EXTRA_MAX_BR, 2_000_000)
                    val minBr = intent.getIntExtra(EXTRA_MIN_BR, 600_000)
                    val manager = WebRTCManager.getInstance()
                    manager.startScreenCapture(resultCode, resultData, w, h, fps, maxBr, minBr)
                    // captureReady StateFlow 已被 startScreenCapture 内部置 true
                    // ViewModel 自己监听并处理 pending Guest
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start screen capture", e)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
            Log.d(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕共享",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕共享正在进行中"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        // 点击通知 → 回到 MainActivity（已运行则 reorder 到前台，复用现有 task）
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // 不带 CLEAR_TOP：保留 NavController 的当前路由（用户回来后还在 HostScreen）
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EasyScreen")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
