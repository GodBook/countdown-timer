package com.awxds.countdowntimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackgroundTimerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        assertTrue("Background state did not change without reopening an Activity", condition())
    }
    @Before fun setup() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        main {
            (context.applicationContext as TimerApp).mainVisible.value = false
            context.timer.reset()
        }
        waitFor { !PopupService.isPrepared }
        context.getSharedPreferences("reminder_diagnostics", 0).edit().clear().commit()
    }
    @After fun cleanup() {
        main { context.timer.reset() }
        waitFor { !PopupService.isPrepared && !PopupService.isWakeHeld }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }

    @Test fun soundModeWithoutOverlayStillPreparesBackgroundTimer() {
        main { assertTrue(context.timer.start(30_000, AlertMode.SOUND)) }
        waitFor { PopupService.isPrepared && PopupService.isWakeHeld }
        assertFalse(PopupService.isShowing)
    }

    @Test fun pauseResumeResetReleaseAndReacquireWakeLock() {
        main { context.timer.start(30_000, AlertMode.VISUAL) }
        waitFor { PopupService.isWakeHeld }
        main { context.timer.pause() }
        waitFor { !PopupService.isWakeHeld }
        assertTrue(PopupService.isPrepared)
        main { context.timer.resume() }
        waitFor { PopupService.isWakeHeld }
        main { context.timer.reset() }
        waitFor { !PopupService.isWakeHeld && !PopupService.isPrepared }
    }

    @Test fun serviceDeadlineCompletesEvenWhenSystemAlarmIsRemoved() {
        main { context.timer.start(1800, AlertMode.VISUAL) }
        waitFor { PopupService.isWakeHeld }
        val pending = PendingIntent.getForegroundService(context, 100,
            Intent(context, PopupService::class.java).setAction("FINISH"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(pending)
        context.getSystemService(AlarmManager::class.java).cancel(pending!!)
        waitFor { context.timer.state.value.phase == Phase.FINISHED }
        waitFor { !PopupService.isWakeHeld }
        assertTrue(ReminderDiagnostics.report(context).contains("finish source=service_deadline"))
    }

    @Test fun exactAlarmRestartsStoppedServiceWithoutActivity() {
        main { context.timer.start(4000, AlertMode.VISUAL) }
        waitFor { PopupService.isWakeHeld }
        context.stopService(Intent(context, PopupService::class.java))
        waitFor { !PopupService.isWakeHeld }
        waitFor { context.timer.state.value.phase == Phase.FINISHED }
        assertTrue(ReminderDiagnostics.report(context).contains("alarm_service_delivered"))
        assertTrue(ReminderDiagnostics.report(context).contains("finish source=alarm_service"))
    }

    @Test fun staleServiceAlarmCannotFinishNewCountdown() {
        main { context.timer.start(30_000, AlertMode.BOTH) }
        waitFor { PopupService.isWakeHeld }
        val stale = context.timer.state.value.generation
        main { context.timer.reset(); context.timer.start(30_000, AlertMode.BOTH) }
        context.startForegroundService(Intent(context, PopupService::class.java).setAction("FINISH").putExtra("generation", stale))
        Thread.sleep(800)
        assertEquals(Phase.RUNNING, context.timer.state.value.phase)
        assertFalse(context.timer.state.value.ringing)
        assertFalse(PopupService.isShowing)
        assertTrue(PopupService.isWakeHeld)
    }
}
