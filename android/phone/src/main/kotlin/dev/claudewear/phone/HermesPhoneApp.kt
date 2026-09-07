package dev.claudewear.phone

import android.app.Application
import com.google.android.gms.wearable.Wearable
import dev.claudewear.phone.data.GatewayClient
import dev.claudewear.phone.data.GatewaySettings
import dev.claudewear.phone.relay.Relay
import dev.claudewear.shared.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class HermesPhoneApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: GatewaySettings
        private set
    lateinit var client: GatewayClient
        private set
    lateinit var relay: Relay
        private set

    override fun onCreate() {
        super.onCreate()
        settings = GatewaySettings.encrypted(this)
        client = GatewayClient({ settings.config.value })
        val messages = Wearable.getMessageClient(this)
        relay = Relay(
            gateway = { if (settings.config.value.configured) client else null },
            send = { nodeId, path, payload -> messages.sendMessage(nodeId, path, payload).await() },
            scope = scope,
            refresh = {
                if (!settings.config.value.configured) RelayStatus("unconfigured")
                else withContext(Dispatchers.IO) {
                    runCatching {
                        val h = client.health()
                        val who = client.whoami()
                        RelayStatus("ready", gatewayVersion = h.version, deviceName = who)
                    }.getOrElse { e ->
                        if (e is GatewayClient.GatewayException && e.status == 401) RelayStatus("unauthorized", e.message ?: "")
                        else RelayStatus("offline", e.message ?: e.javaClass.simpleName)
                    }
                }
            },
        )
        scope.launch {
            settings.config.collect { cfg ->
                relay.setStatus(if (cfg.configured) RelayStatus("connecting") else RelayStatus("unconfigured"))
            }
        }
    }
}
