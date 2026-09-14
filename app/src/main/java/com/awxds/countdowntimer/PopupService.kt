package com.awxds.countdowntimer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** A real, user-authorized overlay. It never launches a background Activity. */
class PopupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var popup: View? = null
    private var confirm: Button? = null
    private var token = -1L
    private var foreground = false
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("generation", -1) ?: -1
        val stop = PendingIntent.getBroadcast(this, 112,
            Intent(this, TimerReceiver::class.java).setAction("STOP").putExtra("generation", requested),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, TimerNotifications.QUIET).setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("计时器 · 结束弹窗正在显示").setContentText("点击弹窗中的按钮或下方完成按钮关闭提醒")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "完成", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(POPUP_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(POPUP_ID, notification)
        foreground = true
        if (token == requested && popup != null) return START_NOT_STICKY
        token = requested
        val app = application as TimerApp
        if (!eligible(timer.state.value, app.mainVisible.value)) { close(); return START_NOT_STICKY }
        try { showPopup(timer.state.value) }
        catch (_: RuntimeException) { close(); return START_NOT_STICKY }
        scope.launch {
            combine(timer.state, app.mainVisible) { state, visible -> state to visible }.collect { (state, visible) ->
                if (!eligible(state, visible)) close()
                else confirm?.text = if (state.ringing) "停止响铃并关闭" else "知道了"
            }
        }
        return START_NOT_STICKY
    }
    private fun eligible(state: TimerState, visible: Boolean) = state.generation == token && state.phase == Phase.FINISHED &&
        state.mode.visual && !state.dismissed && !visible && Settings.canDrawOverlays(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showPopup(state: TimerState) {
        removePopup()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.rgb(240, 241, 245) else Color.rgb(27, 29, 34)
        val surface = if (dark) Color.rgb(33, 36, 43) else Color.rgb(250, 249, 255)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(ink)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        column.addView(label("计时器 · 倒计时结束", 14f))
        column.addView(label("时间到", 28f, true))
        column.addView(label("${formatTime(state.durationMs)} 的倒计时已结束。", 18f))
        confirm = Button(this).apply {
            text = if (state.ringing) "停止响铃并关闭" else "知道了"
            textSize = 17f
            minHeight = dp(52)
            setOnClickListener { timer.stopRinging(token, dismiss = true); close() }
        }
        column.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val panel = ScrollView(this).apply {
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
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        scope.cancel(); removePopup()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object {
        const val POPUP_ID = 12
        private var instance: PopupService? = null
        internal val isShowing get() = instance?.popup != null
        // Never stop a pending foreground-service start before it promotes itself.
        fun closeIfShowing() { instance?.takeIf { it.foreground }?.close() }
    }
}
