package com.awxds.countdowntimer

import android.app.Application
import android.content.Context

class TimerApp : Application() {
    val controller by lazy { TimerController(this) }
    val updates by lazy { UpdateManager(this) }
    override fun onCreate() { super.onCreate(); controller }
}
val Context.timer: TimerController get() = (applicationContext as TimerApp).controller
