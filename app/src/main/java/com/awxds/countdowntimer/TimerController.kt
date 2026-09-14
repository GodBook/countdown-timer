package com.awxds.countdowntimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TimerController(private val context: Context) {
    private val prefs = context.getSharedPreferences("timer", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    val notifications = TimerNotifications(context)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    init {
        notifications.createChannels()
        if (prefs.getInt("boot", -2) != boot) {
            save(state.value.reset())
            notifications.clear()
        }
    }
    fun canSchedule(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    private fun load(): TimerState = runCatching {
        TimerState(Phase.valueOf(prefs.getString("phase", "IDLE")!!), prefs.getLong("duration", 300_000),
            prefs.getLong("remaining", 300_000), prefs.getLong("deadline", 0), prefs.getLong("generation", 0),
            AlertMode.valueOf(prefs.getString("mode", "BOTH")!!), prefs.getBoolean("ringing", false),
            prefs.getLong("ringDeadline", 0), prefs.getBoolean("dismissed", false))
    }.getOrDefault(TimerState())
    private fun save(value: TimerState) {
        prefs.edit().putString("phase", value.phase.name).putLong("duration", value.durationMs)
            .putLong("remaining", value.remainingMs).putLong("deadline", value.deadlineMs)
            .putLong("generation", value.generation).putString("mode", value.mode.name)
            .putBoolean("ringing", value.ringing).putLong("ringDeadline", value.ringDeadlineMs)
            .putBoolean("dismissed", value.dismissed).putInt("boot", boot).commit()
        mutable.value = value
    }
    private fun alarmIntent(token: Long): PendingIntent = PendingIntent.getBroadcast(context, 100,
        Intent(context, TimerReceiver::class.java).setAction("FINISH").putExtra("generation", token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm() { alarms.cancel(alarmIntent(state.value.generation)) }
    private fun schedule(value: TimerState) {
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Alarm-clock delivery avoids while-idle quotas for repeated short user timers.
        // Only this scheduling boundary uses wall time; persisted remaining time stays monotonic.
        val trigger = System.currentTimeMillis() + value.remaining(SystemClock.elapsedRealtime())
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), alarmIntent(value.generation))
    }
    @Synchronized fun start(duration: Long, mode: AlertMode): Boolean {
        if (!canSchedule()) return false
        val next = state.value.start(duration, mode, SystemClock.elapsedRealtime())
        cancelAlarm()
        RingService.stopIfStarted()
        save(next)
        return try { schedule(next); notifications.showActive(next); PopupService.ensure(context); true }
        catch (_: SecurityException) { save(next.reset()); notifications.clear(); false }
    }
    @Synchronized fun pause(token: Long = state.value.generation) {
        val current = state.value
        if (current.generation != token || current.phase != Phase.RUNNING) return
        if (current.remaining(SystemClock.elapsedRealtime()) == 0L) { finish(token); return }
        cancelAlarm()
        save(current.pause(SystemClock.elapsedRealtime()))
        notifications.showActive(state.value)
    }
    @Synchronized fun resume(token: Long = state.value.generation): Boolean {
        val current = state.value
        if (current.generation != token || current.phase != Phase.PAUSED || !canSchedule()) return false
        val next = current.resume(SystemClock.elapsedRealtime())
        save(next)
        return try { schedule(next); notifications.showActive(next); PopupService.ensure(context); true }
        catch (_: SecurityException) { save(current); false }
    }
    @Synchronized fun reset(token: Long = state.value.generation) {
        if (state.value.generation != token) return
        cancelAlarm()
        save(state.value.reset())
        PopupService.closeIfShowing()
        RingService.stopIfStarted()
        notifications.clear()
    }
    @Synchronized fun finish(token: Long) {
        val previous = state.value
        if (previous.phase == Phase.RUNNING && previous.generation == token && previous.remaining(SystemClock.elapsedRealtime()) > 0) {
            if (canSchedule()) schedule(previous)
            return
        }
        val next = previous.finish(token, SystemClock.elapsedRealtime())
        if (next == previous) return
        save(next)
        if (next.ringing) {
            try {
                context.startForegroundService(Intent(context, RingService::class.java).putExtra("generation", token))
            } catch (_: IllegalStateException) { stopRinging(token); notifications.showFinished(state.value, true) }
              catch (_: SecurityException) { stopRinging(token); notifications.showFinished(state.value, true) }
        } else notifications.showFinished(next, true)
        PopupService.ensure(context)
    }
    @Synchronized fun stopRinging(token: Long = state.value.generation, dismiss: Boolean = false) {
        val current = state.value
        if (current.generation != token || current.phase != Phase.FINISHED) return
        save(current.copy(ringing = false, ringDeadlineMs = 0, dismissed = current.dismissed || dismiss))
        if (dismiss) PopupService.closeIfShowing()
        RingService.stopIfStarted()
        notifications.showFinished(state.value, false)
    }
    @Synchronized fun reconcile() {
        val current = state.value
        when (current.phase) {
            Phase.RUNNING -> {
                if (current.remaining(SystemClock.elapsedRealtime()) == 0L) finish(current.generation)
                else if (canSchedule()) { schedule(current); notifications.showActive(current) }
                else { cancelAlarm(); save(current.pause(SystemClock.elapsedRealtime())); notifications.showActive(state.value) }
            }
            Phase.PAUSED -> notifications.showActive(current)
            Phase.FINISHED -> if (current.ringing && current.ringDeadlineMs <= SystemClock.elapsedRealtime()) stopRinging()
            Phase.IDLE -> Unit
        }
        PopupService.ensure(context)
    }
}
