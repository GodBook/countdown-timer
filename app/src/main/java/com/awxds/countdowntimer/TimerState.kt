package com.awxds.countdowntimer

import kotlin.math.max

enum class Phase { IDLE, RUNNING, PAUSED, FINISHED }
enum class AlertMode(val label: String, val visual: Boolean, val sound: Boolean) {
    VISUAL("仅视觉提醒", true, false), SOUND("仅响铃", false, true), BOTH("视觉提醒＋响铃", true, true)
}

data class TimerState(
    val phase: Phase = Phase.IDLE,
    val durationMs: Long = 300_000,
    val remainingMs: Long = 300_000,
    val deadlineMs: Long = 0,
    val generation: Long = 0,
    val mode: AlertMode = AlertMode.BOTH,
    val ringing: Boolean = false,
    val ringDeadlineMs: Long = 0,
    val dismissed: Boolean = false,
) {
    fun remaining(now: Long): Long = if (phase == Phase.RUNNING) max(0, deadlineMs - now) else remainingMs
    fun start(duration: Long, alert: AlertMode, now: Long): TimerState {
        require(duration in 1_000..MAX_DURATION)
        return TimerState(Phase.RUNNING, duration, duration, now + duration, generation + 1, alert)
    }
    fun pause(now: Long): TimerState = if (phase == Phase.RUNNING && remaining(now) > 0)
        copy(phase = Phase.PAUSED, remainingMs = remaining(now), deadlineMs = 0, generation = generation + 1) else this
    fun resume(now: Long): TimerState = if (phase == Phase.PAUSED)
        copy(phase = Phase.RUNNING, deadlineMs = now + remainingMs, generation = generation + 1) else this
    fun reset(): TimerState = TimerState(durationMs = durationMs, remainingMs = durationMs, generation = generation + 1, mode = mode)
    fun finish(token: Long, now: Long): TimerState = if (phase == Phase.RUNNING && generation == token && remaining(now) == 0L)
        copy(phase = Phase.FINISHED, remainingMs = 0, deadlineMs = 0, ringing = mode.sound,
            ringDeadlineMs = if (mode.sound) now + 60_000 else 0) else this
    companion object { const val MAX_DURATION = 359_999_000L }
}

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
}
