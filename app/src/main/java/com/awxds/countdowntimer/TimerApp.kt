package com.awxds.countdowntimer

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

class TimerApp : Application() {
    val controller by lazy { TimerController(this) }
    val updates by lazy { UpdateManager(this) }
    val mainVisible = MutableStateFlow(false)
    override fun onCreate() { super.onCreate(); controller }
}
val Context.timer: TimerController get() = (applicationContext as TimerApp).controller
