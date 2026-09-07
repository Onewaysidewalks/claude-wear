package dev.claudewear.phone.relay

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.claudewear.phone.HermesPhoneApp
import dev.claudewear.shared.WatchProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The Data Layer wakes this for every message and channel on /hermes/v1. It hands the work to
 * the app-wide [Relay] and starts the foreground service so the process lives long enough to
 * finish the turn; a WearableListenerService itself may be torn down as soon as this returns.
 */
class RelayListenerService : WearableListenerService() {
    private val app get() = application as HermesPhoneApp

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.PATH_PING) RelayForegroundService.ensureRunning(this)
        app.relay.handleMessage(event.sourceNodeId, event.path, event.data)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchProtocol.PATH_TURN_AUDIO) return
        RelayForegroundService.ensureRunning(this)
        val client = Wearable.getChannelClient(this)
        app.scope.launch(Dispatchers.IO) {
            val input = client.getInputStream(channel).await()
            app.relay.handleAudioChannel(channel.nodeId, input)
        }
    }
}
