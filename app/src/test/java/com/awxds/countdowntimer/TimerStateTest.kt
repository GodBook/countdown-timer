package com.awxds.countdowntimer

import org.junit.Assert.*
import org.junit.Test

class TimerStateTest {
    @Test fun pauseAndResumePreserveRemaining() {
        val running = TimerState().start(10_000, AlertMode.BOTH, 100)
        val paused = running.pause(3100)
        assertEquals(7000, paused.remaining(90_000))
        val resumed = paused.resume(100_000)
        assertEquals(6000, resumed.remaining(101_000))
        assertTrue(resumed.generation > running.generation)
    }
    @Test fun staleOrEarlyAlarmsCannotFinish() {
        val first = TimerState().start(1000, AlertMode.SOUND, 100)
        val second = first.reset().start(5000, AlertMode.VISUAL, 200)
        assertEquals(second, second.finish(first.generation, 10_000))
        assertEquals(second, second.finish(second.generation, 5199))
        assertEquals(Phase.FINISHED, second.finish(second.generation, 5200).phase)
    }
    @Test fun finishIsIdempotentAndRingIsBounded() {
        val running = TimerState().start(1000, AlertMode.BOTH, 0)
        val finished = running.finish(running.generation, 1000)
        assertTrue(finished.ringing)
        assertEquals(61_000, finished.ringDeadlineMs)
        assertEquals(finished, finished.finish(finished.generation, 90_000))
    }
    @Test fun visualModeNeverRings() {
        val running = TimerState().start(1000, AlertMode.VISUAL, 0)
        assertFalse(running.finish(running.generation, 1000).ringing)
    }
    @Test fun resetPreservesSettingsAndInvalidatesAlarm() {
        val running = TimerState().start(25 * 60_000, AlertMode.SOUND, 0)
        val idle = running.reset()
        assertEquals(Phase.IDLE, idle.phase)
        assertEquals(running.durationMs, idle.remainingMs)
        assertEquals(AlertMode.SOUND, idle.mode)
        assertNotEquals(running.generation, idle.generation)
    }
    @Test fun durationBoundaries() {
        for (invalid in listOf(0L, 999L, TimerState.MAX_DURATION + 1)) {
            assertThrows(IllegalArgumentException::class.java) { TimerState().start(invalid, AlertMode.BOTH, 0) }
        }
        assertEquals(TimerState.MAX_DURATION, TimerState().start(TimerState.MAX_DURATION, AlertMode.BOTH, 0).remaining(0))
    }
    @Test fun displayRoundsUpAndSupports99Hours() {
        assertEquals("00:00:01", formatTime(1))
        assertEquals("00:00:00", formatTime(-1))
        assertEquals("99:59:59", formatTime(TimerState.MAX_DURATION))
    }
}
