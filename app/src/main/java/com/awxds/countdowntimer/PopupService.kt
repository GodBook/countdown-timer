package com.awxds.countdowntimer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A real, user-authorized overlay. It never launches a background Activity. */
class PopupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlayContext by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: error("系统暂未提供显示屏")
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
    }
    private val windows by lazy { overlayContext.getSystemService(WindowManager::class.java) }
    private var popup: View? = null
    private var confirm: Button? = null
    private var token = -1L
    private var foreground = false
    private var notificationId = 0
    private var observer: Job? = null
    private var retry: Job? = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read authoritative state, not a stale alarm/intent token. The service is normally
        // started while the user is still in the app, before any background restriction.
        promote(timer.state.value)
        refresh()
        if (observer == null) observer = scope.launch {
            combine(timer.state, (application as TimerApp).mainVisible) { state, visible -> state to visible }
                .collect { refresh() }
        }
        // Independent fallback if a vendor delays the receiver but keeps the foreground service.
        if (ticker == null) ticker = scope.launch {
            while (isActive) {
                val state = timer.state.value
                if (state.phase == Phase.RUNNING && state.remaining(SystemClock.elapsedRealtime()) == 0L)
                    timer.finish(state.generation)
                delay(if (state.phase == Phase.RUNNING) state.remaining(SystemClock.elapsedRealtime()).coerceIn(50, 1000) else 1000)
            }
        }
        return if (keepAlive(timer.state.value)) START_STICKY else START_NOT_STICKY
    }
    private var ticker: Job? = null
    private fun completionServiceNotification(state: TimerState): Notification {
        val stop = PendingIntent.getBroadcast(this, 112,
            Intent(this, TimerReceiver::class.java).setAction("STOP").putExtra("generation", state.generation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, TimerNotifications.QUIET).setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("计时器 · 结束提醒待确认").setContentText("点击弹窗中的按钮或下方完成按钮关闭提醒")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "完成", stop).build()).build()
    }
    private fun promote(state: TimerState) {
        val active = state.phase in setOf(Phase.RUNNING, Phase.PAUSED)
        val id = if (active) TimerNotifications.ACTIVE_ID else POPUP_ID
        val notification = if (active) timer.notifications.active(state) else completionServiceNotification(state)
        if (Build.VERSION.SDK_INT >= 34) startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(id, notification)
        val previousId = notificationId
        notificationId = id
        foreground = true
        if (previousId != 0 && previousId != id) getSystemService(android.app.NotificationManager::class.java).cancel(previousId)
    }
    private fun keepAlive(state: TimerState) = state.mode.visual && Settings.canDrawOverlays(this) &&
        (state.phase in setOf(Phase.RUNNING, Phase.PAUSED) || state.phase == Phase.FINISHED && !state.dismissed)
    private fun refresh() {
        if (!foreground) return
        val state = timer.state.value
        if (!keepAlive(state)) { close(); return }
        if (token != state.generation) { removePopup(); token = state.generation }
        promote(state)
        val shouldShow = state.phase == Phase.FINISHED && !(application as TimerApp).mainVisible.value
        if (!shouldShow) {
            retry?.cancel(); retry = null; removePopup()
            mutableStatus.value = if (state.phase == Phase.FINISHED) "结束提醒待确认" else "后台提醒服务正在运行"
        } else if (popup == null) {
            try {
                showPopup(state)
                mutableStatus.value = "结束弹窗已显示"
                getSharedPreferences("popup_diagnostics", MODE_PRIVATE).edit().clear().apply()
            } catch (e: RuntimeException) {
                reportFailure(this, "系统未能显示弹窗", e)
                if (retry == null) retry = scope.launch {
                    repeat(2) {
                        delay(500)
                        val latest = timer.state.value
                        if (latest.generation == token && keepAlive(latest) && latest.phase == Phase.FINISHED &&
                            !(application as TimerApp).mainVisible.value && popup == null) {
                            runCatching { showPopup(latest) }.onSuccess { mutableStatus.value = "结束弹窗已显示" }
                                .onFailure { reportFailure(this@PopupService, "系统未能显示弹窗", it) }
                        }
                    }
                }
            }
        } else confirm?.text = if (state.ringing) "停止响铃并关闭" else "知道了"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showPopup(state: TimerState) {
        removePopup()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.rgb(240, 241, 245) else Color.rgb(27, 29, 34)
        val surface = if (dark) Color.rgb(33, 36, 43) else Color.rgb(250, 249, 255)
        val column = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        fun label(value: String, size: Float, bold: Boolean = false) = TextView(overlayContext).apply {
            text = value; textSize = size; setTextColor(ink)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        column.addView(label("计时器 · 倒计时结束", 14f))
        column.addView(label("时间到", 28f, true))
        column.addView(label("${formatTime(state.durationMs)} 的倒计时已结束。", 18f))
        confirm = Button(overlayContext).apply {
            text = if (state.ringing) "停止响铃并关闭" else "知道了"
            textSize = 17f
            minHeight = dp(52)
            setOnClickListener { timer.stopRinging(token, dismiss = true); close() }
        }
        column.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val panel = ScrollView(overlayContext).apply {
            addView(column)
            background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(24).toFloat() }
            elevation = dp(16).toFloat()
        }
        val metrics = resources.displayMetrics
        val width = minOf(dp(360), metrics.widthPixels - dp(40))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels - dp(120), View.MeasureSpec.AT_MOST))
        val params = WindowManager.LayoutParams(width, panel.measuredHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER; dimAmount = 0.32f; title = "Countdown completion popup" }
        windows.addView(panel, params)
        popup = panel
    }
    private fun removePopup() {
        popup?.let { runCatching { windows.removeViewImmediate(it) } }
        popup = null; confirm = null
    }
    private fun close() {
        removePopup()
        if (foreground) {
            val state = timer.state.value
            val preserveProgress = notificationId == TimerNotifications.ACTIVE_ID && state.phase in setOf(Phase.RUNNING, Phase.PAUSED)
            stopForeground(if (preserveProgress) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
        mutableStatus.value = "开始视觉计时时自动准备后台提醒"
    }
    override fun onDestroy() {
        scope.cancel(); removePopup()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object {
        const val POPUP_ID = 12
        private var instance: PopupService? = null
        private val mutableStatus = MutableStateFlow("开始视觉计时时自动准备后台提醒")
        val status = mutableStatus.asStateFlow()
        internal val isShowing get() = instance?.popup != null
        internal val isPrepared get() = instance?.foreground == true
        fun ensure(context: Context) {
            val state = context.timer.state.value
            if (!state.mode.visual || state.phase == Phase.IDLE || state.dismissed || !Settings.canDrawOverlays(context)) return
            instance?.takeIf { it.foreground }?.let { it.refresh(); return }
            try {
                context.startForegroundService(Intent(context, PopupService::class.java))
            } catch (e: RuntimeException) { reportFailure(context, "后台提醒服务被系统拦截", e) }
        }
        private fun reportFailure(context: Context, message: String, error: Throwable) {
            Log.e("TimerPopup", message, error)
            mutableStatus.value = "$message，请检查系统后台权限后重试。"
            context.getSharedPreferences("popup_diagnostics", Context.MODE_PRIVATE).edit()
                .putString("lastFailure", "$message: ${error.javaClass.simpleName}: ${error.message}")
                .putLong("time", System.currentTimeMillis()).apply()
        }
        // Never stop a pending foreground-service start before it promotes itself.
        fun closeIfShowing() { instance?.takeIf { it.foreground }?.close() }
    }
}
