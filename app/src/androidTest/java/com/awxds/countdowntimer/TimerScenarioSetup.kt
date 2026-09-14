package com.awxds.countdowntimer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Deliberately leaves a timer running for external process-death, lock and reboot checks. */
class TimerScenarioSetup {
    @Test fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val duration = args.getString("duration", "20000")!!.toLong()
        val mode = AlertMode.valueOf(args.getString("mode", "VISUAL")!!)
        instrumentation.runOnMainSync { instrumentation.targetContext.timer.start(duration, mode) }
    }
}
