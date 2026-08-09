package dev.claudewear.wear.service

import dev.claudewear.wear.ui.SessionsClient
import dev.claudewear.wear.ui.WatchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The one live connection, published by the [ConnectionService] that owns it and observed
 * by whatever screen happens to be up.
 *
 * A process-wide holder rather than a bound service: the connection deliberately outlives
 * the Activity — that is the entire point of holding it in a foreground service — so
 * binding its lifetime to a binder would put it back where it started.
 */
object ActiveConnection {

    private val _client = MutableStateFlow<SessionsClient?>(null)
    val client: StateFlow<SessionsClient?> = _client.asStateFlow()

    /** The live connection's state, or an offline placeholder when the service is not up. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<WatchState> = client.flatMapLatest { live ->
        live?.state ?: flowOf(WatchState(connection = WatchState.Connection.OFFLINE))
    }

    internal fun publish(client: SessionsClient?) {
        _client.value = client
    }
}
