package com.awxds.countdowntimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getLongExtra("generation", -1)
        when (intent.action) {
            "FINISH" -> context.timer.finish(token)
            "PAUSE" -> context.timer.pause(token)
            "RESUME" -> context.timer.resume(token)
            "CANCEL" -> context.timer.reset(token)
            "STOP" -> context.timer.stopRinging(token, dismiss = true)
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) context.timer.reset()
        else if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED)
            context.timer.reconcile()
    }
}
