package dev.claudewear.watch

import android.app.Application
import dev.claudewear.watch.link.PhoneLink
import dev.claudewear.watch.ui.TurnStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HermesWatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = TurnStore()
    lateinit var link: PhoneLink
        private set

    override fun onCreate() {
        super.onCreate()
        link = PhoneLink(this)
    }
}
