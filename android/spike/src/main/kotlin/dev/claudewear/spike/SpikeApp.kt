package dev.claudewear.spike

import android.app.Application

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InvocationLog.attach(this)
        InvocationLog.record("process", "Application.onCreate")
    }
}
