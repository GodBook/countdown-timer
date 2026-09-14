package com.awxds.countdowntimer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local, bounded evidence. Never uploaded; the user can copy it from the app. */
object ReminderDiagnostics {
    @Synchronized fun record(context: Context, event: String) {
        val prefs = context.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val rows = prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        prefs.edit().putString("events", (rows + "$timestamp $event").takeLast(40).joinToString("\n")).commit()
    }

    fun batteryExempt(context: Context) = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun report(context: Context): String {
        val state = context.timer.state.value
        return "计时器 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "设备：${Build.MANUFACTURER} / ${Build.MODEL}，Android ${Build.VERSION.RELEASE}\n" +
            "通知=${context.timer.notifications.allowed()}，精确闹钟=${context.timer.canSchedule()}，" +
            "悬浮窗=${Settings.canDrawOverlays(context)}，电池优化豁免=${batteryExempt(context)}\n" +
            "状态=${state.phase}，模式=${state.mode}，剩余=${state.remaining(SystemClock.elapsedRealtime())}ms\n" +
            "服务=${PopupService.isPrepared}，计时唤醒锁=${PopupService.isWakeHeld}\n\n" +
            context.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE).getString("events", "尚无记录")
    }
}
