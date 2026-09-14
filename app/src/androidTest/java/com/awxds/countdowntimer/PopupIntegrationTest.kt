package com.awxds.countdowntimer

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PopupIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as TimerApp
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitFor(condition: () -> Boolean) {
        val limit = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < limit) Thread.sleep(100)
        assertTrue("Expected popup state was not reached", condition())
    }
    @Before fun setup() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(Settings.canDrawOverlays(context))
        main { app.mainVisible.value = false; context.timer.reset() }
    }
    @After fun cleanup() {
        main { context.timer.reset(); app.mainVisible.value = false }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
    }
    @Test fun backgroundVisualPopupRemainsUntilAcknowledged() {
        main { context.timer.start(1000, AlertMode.VISUAL) }
        waitFor { PopupService.isShowing }
        assertFalse(context.timer.state.value.ringing)
        Thread.sleep(1000)
        assertTrue(PopupService.isShowing)
        main { context.timer.stopRinging(dismiss = true) }
        waitFor { !PopupService.isShowing }
        assertTrue(context.timer.state.value.dismissed)
    }
    @Test fun soundOnlyDoesNotCreatePopup() {
        main { context.timer.start(1000, AlertMode.SOUND) }
        waitFor { context.timer.state.value.phase == Phase.FINISHED }
        Thread.sleep(500)
        assertFalse(PopupService.isShowing)
    }
    @Test fun withoutPermissionFallsBackToNotification() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        main { context.timer.start(1000, AlertMode.VISUAL) }
        waitFor { context.timer.state.value.phase == Phase.FINISHED }
        assertFalse(PopupService.isShowing)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        waitFor { notifications.activeNotifications.any { it.id == TimerNotifications.FINISHED_ID } }
    }
    @Test fun foregroundUsesActivityDialogAndReturningRemovesOverlay() {
        main { app.mainVisible.value = true; context.timer.start(1000, AlertMode.VISUAL) }
        waitFor { context.timer.state.value.phase == Phase.FINISHED }
        assertFalse(PopupService.isShowing)
        main { app.mainVisible.value = false; context.timer.start(1000, AlertMode.VISUAL) }
        waitFor { PopupService.isShowing }
        main { app.mainVisible.value = true }
        waitFor { !PopupService.isShowing }
        assertFalse(context.timer.state.value.dismissed)
    }
    @Test fun combinedPopupAcknowledgementStopsSoundToo() {
        main { context.timer.start(1000, AlertMode.BOTH) }
        waitFor { PopupService.isShowing && context.timer.state.value.ringing }
        main { context.timer.stopRinging(dismiss = true) }
        waitFor { !PopupService.isShowing && !context.timer.state.value.ringing }
    }
    @Test fun resetDuringPopupStartupDoesNotLeaveOverlay() {
        main {
            context.timer.start(1000, AlertMode.VISUAL)
            Thread.sleep(1100)
            context.timer.finish(context.timer.state.value.generation)
            context.timer.reset()
        }
        Thread.sleep(2000)
        assertFalse(PopupService.isShowing)
        assertEquals(Phase.IDLE, context.timer.state.value.phase)
    }
}
