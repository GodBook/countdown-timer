package com.awxds.countdowntimer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val controller get() = context.timer
    private val notifications get() = context.getSystemService(NotificationManager::class.java).activeNotifications
    @Before fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        instrumentation.runOnMainSync { controller.reset() }
    }
    @After fun cleanup() { instrumentation.runOnMainSync { controller.reset() } }
    private fun main(action: () -> Unit) { instrumentation.runOnMainSync(action) }
    private fun waitFor(timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        assertTrue("Condition did not become true within $timeout ms", condition())
    }
    @Test fun notificationButtonsControlSameTimer() {
        main { assertTrue(controller.start(30_000, AlertMode.VISUAL)) }
        waitFor { notifications.any { it.id == TimerNotifications.ACTIVE_ID } }
        val running = notifications.first { it.id == TimerNotifications.ACTIVE_ID }.notification
        assertTrue(running.extras.getBoolean("android.showChronometer"))
        assertTrue(running.extras.getBoolean("android.chronometerCountDown"))
        running.actions[0].actionIntent.send()
        waitFor { controller.state.value.phase == Phase.PAUSED }
        val remaining = controller.state.value.remainingMs
        Thread.sleep(300)
        assertEquals(remaining, controller.state.value.remaining(SystemClock.elapsedRealtime()))
        notifications.first { it.id == TimerNotifications.ACTIVE_ID }.notification.actions[0].actionIntent.send()
        waitFor { controller.state.value.phase == Phase.RUNNING }
        notifications.first { it.id == TimerNotifications.ACTIVE_ID }.notification.actions[1].actionIntent.send()
        waitFor { controller.state.value.phase == Phase.IDLE && notifications.isEmpty() }
    }
    @Test fun visualAlarmCompletesWithoutActivityOrSound() {
        main { assertTrue(controller.start(1500, AlertMode.VISUAL)) }
        waitFor { controller.state.value.phase == Phase.FINISHED }
        assertFalse(controller.state.value.ringing)
        waitFor { notifications.any { it.id == TimerNotifications.FINISHED_ID } }
        assertEquals(TimerNotifications.ALERT, notifications.first { it.id == TimerNotifications.FINISHED_ID }.notification.channelId)
    }
    @Test fun soundAlarmUsesQuietNotificationAndStopsFromNotification() {
        main { assertTrue(controller.start(1500, AlertMode.SOUND)) }
        waitFor { controller.state.value.phase == Phase.FINISHED && notifications.any { it.id == TimerNotifications.FINISHED_ID } }
        assertTrue(controller.state.value.ringing)
        val notification = notifications.first { it.id == TimerNotifications.FINISHED_ID }.notification
        assertEquals(TimerNotifications.QUIET, notification.channelId)
        notification.actions[0].actionIntent.send()
        waitFor { !controller.state.value.ringing }
    }
    @Test fun ringingAutomaticallyStopsAfterOneMinute() {
        main { assertTrue(controller.start(1000, AlertMode.BOTH)) }
        waitFor { controller.state.value.phase == Phase.FINISHED && controller.state.value.ringing }
        waitFor(63_000) { !controller.state.value.ringing }
        assertEquals(Phase.FINISHED, controller.state.value.phase)
        assertTrue(notifications.any { it.id == TimerNotifications.FINISHED_ID })
    }
    @Test fun resetInvalidatesPendingAlarm() {
        main {
            controller.start(1500, AlertMode.BOTH)
            controller.reset()
        }
        Thread.sleep(2200)
        assertEquals(Phase.IDLE, controller.state.value.phase)
        assertFalse(controller.state.value.ringing)
        assertTrue(notifications.isEmpty())
    }
    @Test fun cancelDuringForegroundServiceStartupDoesNotCrash() {
        main {
            controller.start(1000, AlertMode.SOUND)
            Thread.sleep(1100)
            controller.finish(controller.state.value.generation)
            controller.reset()
        }
        Thread.sleep(2000)
        assertEquals(Phase.IDLE, controller.state.value.phase)
        assertFalse(controller.state.value.ringing)
    }
    @Test fun recreationLoadsPersistedPauseAndRestartClearsIt() {
        main { controller.start(60_000, AlertMode.SOUND); controller.pause() }
        val restored = TimerController(context)
        assertEquals(controller.state.value, restored.state.value)
        context.getSharedPreferences("timer", 0).edit().putInt("boot", -50).commit()
        val rebooted = TimerController(context)
        assertEquals(Phase.IDLE, rebooted.state.value.phase)
        assertEquals(AlertMode.SOUND, rebooted.state.value.mode)
    }
}
