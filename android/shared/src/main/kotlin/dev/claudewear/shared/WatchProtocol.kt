package dev.claudewear.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the watch and the phone say to each other over the Wear Data Layer. The phone is a relay
 * with a token; the watch never sees the gateway. Every message is JSON in a MessageClient
 * payload except audio, which is a ChannelClient stream ([AudioChannel]).
 *
 * Paths are versioned so a phone and a watch from different builds fail loudly instead of oddly.
 */
object WatchProtocol {
    /** Advertised by the phone app (CapabilityClient) so the watch can find the node that relays. */
    const val CAPABILITY_RELAY = "hermes_relay"

    /** watch -> phone: a text turn ([TextTurn]). */
    const val PATH_TURN_TEXT = "/hermes/v1/turn/text"

    /** watch -> phone: a ChannelClient stream, header line then PCM ([AudioChannel]). */
    const val PATH_TURN_AUDIO = "/hermes/v1/turn/audio"

    /** watch -> phone: [ApprovalAnswer]. */
    const val PATH_APPROVAL = "/hermes/v1/approval"

    /** watch -> phone: [Cancel]. */
    const val PATH_CANCEL = "/hermes/v1/cancel"

    /** watch -> phone: empty payload; phone answers on [PATH_STATUS] with a fresh [RelayStatus]. */
    const val PATH_PING = "/hermes/v1/ping"

    /** phone -> watch: [RelayedEvent], one per gateway event. */
    const val PATH_EVENT = "/hermes/v1/event"

    /** phone -> watch: [RelayStatus], on ping and whenever the phone's view of the gateway changes. */
    const val PATH_STATUS = "/hermes/v1/status"

    val json = GatewayJson
}

/** One turn requested by the watch. `requestId` ties every relayed event back to it. */
@Serializable
data class TextTurn(
    @SerialName("request_id") val requestId: String,
    val text: String,
    val session: String = "watch",
    val locale: String = "en-US",
    val reset: Boolean = false,
)

@Serializable
data class ApprovalAnswer(
    @SerialName("request_id") val requestId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("approval_id") val approvalId: String,
    val decision: String,
)

@Serializable
data class Cancel(@SerialName("request_id") val requestId: String)

/** A gateway event forwarded to the watch. `eventJson` is the exact v1 payload, untouched. */
@Serializable
data class RelayedEvent(
    @SerialName("request_id") val requestId: String,
    @SerialName("event") val eventJson: String,
) {
    fun event(): GatewayEvent = GatewayEvents.decode(eventJson)
}

/**
 * The phone's view of the world, so the watch can say "phone has no gateway" instead of spinning.
 * `state` is one of: unconfigured, offline, connecting, ready, unauthorized.
 */
@Serializable
data class RelayStatus(
    val state: String,
    val detail: String = "",
    @SerialName("gateway_version") val gatewayVersion: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("at") val at: Long = 0,
)

/**
 * The audio channel: the watch opens a ChannelClient channel on [WatchProtocol.PATH_TURN_AUDIO],
 * writes one JSON header line, a newline, then raw PCM until it closes the output. The phone
 * streams the PCM to the gateway as it arrives and closes the utterance when the channel closes.
 */
@Serializable
data class AudioChannel(
    @SerialName("request_id") val requestId: String,
    val session: String = "watch",
    val locale: String = "en-US",
    @SerialName("sample_rate") val sampleRate: Int = 16000,
    val channels: Int = 1,
    val format: String = "pcm_s16le",
) {
    fun headerBytes(): ByteArray = (WatchProtocol.json.encodeToString(serializer(), this) + "\n").encodeToByteArray()

    companion object {
        const val MAX_HEADER_BYTES = 4096

        fun parseHeader(line: String): AudioChannel = WatchProtocol.json.decodeFromString(serializer(), line)
    }
}
