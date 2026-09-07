package dev.claudewear.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request shapes for POST /v1/turns and the approval endpoint. See gateway/API.md. */
@Serializable
data class SessionRef(val key: String = "default", val reset: Boolean = false)

@Serializable
data class TurnOptions(
    val speak: Boolean = false,
    val locale: String = "en-US",
    @SerialName("approval_timeout_s") val approvalTimeoutS: Int? = null,
)

@Serializable
data class TextInput(val type: String = "text", val text: String)

@Serializable
data class TextTurnRequest(
    val session: SessionRef = SessionRef(),
    val input: TextInput,
    val options: TurnOptions = TurnOptions(),
)

@Serializable
data class ApprovalDecision(val decision: String)

/** Control frames for WS /v1/stream (client to server). */
@Serializable
data class AudioSpec(
    val format: String = "pcm_s16le",
    @SerialName("sample_rate") val sampleRate: Int = 16000,
    val channels: Int = 1,
)

@Serializable
data class WsStart(
    val type: String = "start",
    val session: SessionRef = SessionRef(),
    val audio: AudioSpec = AudioSpec(),
    val options: TurnOptions = TurnOptions(),
)

@Serializable
data class WsText(
    val type: String = "text",
    val text: String,
    val session: SessionRef = SessionRef(),
    val options: TurnOptions = TurnOptions(),
)

@Serializable
data class WsApproval(
    val type: String = "approval",
    @SerialName("turn_id") val turnId: String,
    @SerialName("approval_id") val approvalId: String,
    val decision: String,
)

@Serializable
data class WsSimple(val type: String)
