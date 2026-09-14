package com.awxds.countdowntimer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

class TimerNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(ACTIVE, "倒计时进度", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) },
            NotificationChannel(ALERT, "结束提醒", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false); description = "横幅显示结束提醒；铃声由计时器单独播放" },
            NotificationChannel(QUIET, "响铃控制与结束记录", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) }
        ))
    }
    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun channelsEnabled(): Boolean = listOf(ACTIVE, ALERT, QUIET).all { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
    private fun open(): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun action(label: String, command: String, token: Long): Notification.Action = Notification.Action.Builder(
        null, label, PendingIntent.getBroadcast(context, command.hashCode(),
            Intent(context, TimerReceiver::class.java).setAction(command).putExtra("generation", token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    private fun base(channel: String) = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_timer)
        .setContentIntent(open()).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_ALARM)
        .setShowWhen(false).setOnlyAlertOnce(true)
    fun active(state: TimerState): Notification {
        val running = state.phase == Phase.RUNNING
        val builder = base(ACTIVE).setContentTitle(if (running) "倒计时进行中" else "倒计时已暂停")
            .setContentText(if (running) "到时${if (state.mode.sound) "响铃" else "提醒"} · ${state.mode.label}" else "剩余 ${formatTime(state.remainingMs)}")
            .setOngoing(true)
            .addAction(action(if (running) "暂停" else "继续", if (running) "PAUSE" else "RESUME", state.generation))
            .addAction(action("取消", "CANCEL", state.generation))
        if (running) builder.setWhen(System.currentTimeMillis() + state.remaining(SystemClock.elapsedRealtime()))
            .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        return builder.build()
    }
    fun showActive(state: TimerState) {
        manager.cancel(FINISHED_ID)
        if (allowed()) manager.notify(ACTIVE_ID, active(state))
    }
    fun finished(state: TimerState, alert: Boolean): Notification = base(if (state.mode.visual) ALERT else QUIET)
        .setContentTitle("倒计时结束")
        .setContentText("${formatTime(state.durationMs)} 已结束${if (state.ringing) " · 响铃最多 1 分钟" else ""}")
        .setOnlyAlertOnce(!alert).setOngoing(state.ringing).setAutoCancel(!state.ringing)
        .addAction(action(if (state.ringing) "停止响铃" else "完成", if (state.ringing) "STOP" else "CANCEL", state.generation))
        .build()
    fun showFinished(state: TimerState, alert: Boolean) {
        manager.cancel(ACTIVE_ID)
        if (allowed()) manager.notify(FINISHED_ID, finished(state, alert))
    }
    fun clearActive() { manager.cancel(ACTIVE_ID) }
    fun clear() { manager.cancel(ACTIVE_ID); manager.cancel(FINISHED_ID) }
    companion object {
        const val ACTIVE_ID = 10
        const val FINISHED_ID = 11
        const val ACTIVE = "timer_progress"
        const val ALERT = "timer_finished_visual"
        const val QUIET = "timer_finished_quiet"
    }
}
