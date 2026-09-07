package dev.claudewear.watch.link

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.claudewear.shared.RelayStatus
import dev.claudewear.shared.RelayedEvent
import dev.claudewear.shared.WatchProtocol
import dev.claudewear.watch.HermesWatchApp

/** Everything the phone says lands here and goes straight into the store the screen observes. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val store = (application as HermesWatchApp).store
        when (event.path) {
            WatchProtocol.PATH_EVENT -> {
                val relayed = WatchProtocol.json.decodeFromString(RelayedEvent.serializer(), event.data.decodeToString())
                store.onRelayed(relayed)
            }
            WatchProtocol.PATH_STATUS -> {
                store.onStatus(WatchProtocol.json.decodeFromString(RelayStatus.serializer(), event.data.decodeToString()))
            }
        }
    }
}
