package com.awxds.countdowntimer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var token = -1L
    private var foreground = false
    private val timeout = Runnable { timer.stopRinging(token) }
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = timer.state.value
        val requested = intent?.getLongExtra("generation", -1) ?: state.generation
        // A stale service start must never play sound or leave a foreground service behind.
        if (state.generation != requested || !state.ringing || state.phase != Phase.FINISHED) {
            startForeground(TimerNotifications.FINISHED_ID, timer.notifications.finished(state, false))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val notification = timer.notifications.finished(state, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(TimerNotifications.FINISHED_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(TimerNotifications.FINISHED_ID, notification)
        foreground = true
        timer.notifications.clearActive()
        if (token == requested && player != null) return START_NOT_STICKY
        token = requested
        release()
        val remaining = (state.ringDeadlineMs - SystemClock.elapsedRealtime()).coerceIn(0, 60_000)
        if (remaining == 0L) { timer.stopRinging(token); return START_NOT_STICKY }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "countdowntimer:ring").apply { acquire(remaining + 2000) }
        handler.postDelayed(timeout, remaining)
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, uri)
                isLooping = true
                setOnErrorListener { _, _, _ -> timer.stopRinging(token); true }
                prepare(); start()
            }
        } catch (_: Exception) { timer.stopRinging(token) }
        return START_REDELIVER_INTENT
    }
    private fun release() {
        handler.removeCallbacks(timeout)
        player?.release(); player = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        release()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }
    companion object {
        private var instance: RingService? = null
        // stopService before onStartCommand can crash Android's pending FGS startup.
        // Pending starts must first promote themselves, then observe the canceled state.
        fun stopIfStarted() {
            instance?.let { service ->
                if (service.foreground) { service.release(); service.stopSelf() }
            }
        }
    }
}
