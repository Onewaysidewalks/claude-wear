package dev.claudewear.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Hermes Gateway v1 event contract, as the phone and watch see it. Mirrors
 * gateway/API.md; the test in this module decodes every file in gateway/fixtures/v1 to keep the
 * two in step. Unknown event types decode to [GatewayEvent.Unknown] rather than failing: the
 * contract says a client ignores what it does not know.
 */
@Serializable(with = GatewayEventSerializer::class)
sealed class GatewayEvent {
    abstract val turnId: String
    abstract val seq: Int
    abstract val at: String

    @Serializable
    data class TurnStarted(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        @SerialName("session_key") val sessionKey: String,
        @SerialName("input_type") val inputType: String,
    ) : GatewayEvent()

    @Serializable
    data class InputTranscript(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val text: String,
        val final: Boolean = true,
    ) : GatewayEvent()

    @Serializable
    data class OutputDelta(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val text: String,
    ) : GatewayEvent()

    @Serializable
    data class ToolStarted(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        @SerialName("tool_id") val toolId: String,
        val name: String,
        val summary: String = "",
    ) : GatewayEvent()

    @Serializable
    data class ToolCompleted(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        @SerialName("tool_id") val toolId: String,
        val name: String,
        val ok: Boolean = true,
        val summary: String = "",
    ) : GatewayEvent()

    @Serializable
    data class ApprovalRequired(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        @SerialName("approval_id") val approvalId: String,
        val action: String,
        val detail: String = "",
        val risk: String = "normal",
        @SerialName("expires_at") val expiresAt: String,
    ) : GatewayEvent() {
        val highRisk: Boolean get() = risk == "high"
    }

    @Serializable
    data class ApprovalResolved(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        @SerialName("approval_id") val approvalId: String,
        val decision: String,
        val by: String = "client",
    ) : GatewayEvent()

    @Serializable
    data class OutputDone(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val text: String,
    ) : GatewayEvent()

    @Serializable
    data class SpeechChunk(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val format: String,
        val data: String,
        val last: Boolean = false,
    ) : GatewayEvent()

    @Serializable
    data class TurnCompleted(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val status: String,
        val usage: Map<String, Int>? = null,
    ) : GatewayEvent()

    @Serializable
    data class Error(
        @SerialName("turn_id") override val turnId: String,
        override val seq: Int = 0,
        override val at: String = "",
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : GatewayEvent()

    /** An event type this client does not know. Carried, never acted on. */
    @Serializable
    data class Unknown(
        @SerialName("turn_id") override val turnId: String = "",
        override val seq: Int = 0,
        override val at: String = "",
        val type: String = "",
    ) : GatewayEvent()
}

object GatewayEventSerializer : JsonContentPolymorphicSerializer<GatewayEvent>(GatewayEvent::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out GatewayEvent> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "turn.started" -> GatewayEvent.TurnStarted.serializer()
            "input.transcript" -> GatewayEvent.InputTranscript.serializer()
            "output.delta" -> GatewayEvent.OutputDelta.serializer()
            "tool.started" -> GatewayEvent.ToolStarted.serializer()
            "tool.completed" -> GatewayEvent.ToolCompleted.serializer()
            "approval.required" -> GatewayEvent.ApprovalRequired.serializer()
            "approval.resolved" -> GatewayEvent.ApprovalResolved.serializer()
            "output.done" -> GatewayEvent.OutputDone.serializer()
            "speech.chunk" -> GatewayEvent.SpeechChunk.serializer()
            "turn.completed" -> GatewayEvent.TurnCompleted.serializer()
            "error" -> GatewayEvent.Error.serializer()
            else -> GatewayEvent.Unknown.serializer()
        }
}

/** The wire name of an event, for re-encoding and logging. */
val GatewayEvent.typeName: String
    get() = when (this) {
        is GatewayEvent.TurnStarted -> "turn.started"
        is GatewayEvent.InputTranscript -> "input.transcript"
        is GatewayEvent.OutputDelta -> "output.delta"
        is GatewayEvent.ToolStarted -> "tool.started"
        is GatewayEvent.ToolCompleted -> "tool.completed"
        is GatewayEvent.ApprovalRequired -> "approval.required"
        is GatewayEvent.ApprovalResolved -> "approval.resolved"
        is GatewayEvent.OutputDone -> "output.done"
        is GatewayEvent.SpeechChunk -> "speech.chunk"
        is GatewayEvent.TurnCompleted -> "turn.completed"
        is GatewayEvent.Error -> "error"
        is GatewayEvent.Unknown -> type
    }

/** The JSON configuration every side of the system uses. Lenient about what it does not know. */
val GatewayJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

object GatewayEvents {
    fun decode(json: String): GatewayEvent = GatewayJson.decodeFromString(GatewayEventSerializer, json)

    /** Encode with the `type` field the gateway uses, so a relayed event is byte-for-byte the contract. */
    fun encode(event: GatewayEvent): String {
        val body = when (event) {
            is GatewayEvent.TurnStarted -> GatewayJson.encodeToJsonElement(GatewayEvent.TurnStarted.serializer(), event)
            is GatewayEvent.InputTranscript -> GatewayJson.encodeToJsonElement(GatewayEvent.InputTranscript.serializer(), event)
            is GatewayEvent.OutputDelta -> GatewayJson.encodeToJsonElement(GatewayEvent.OutputDelta.serializer(), event)
            is GatewayEvent.ToolStarted -> GatewayJson.encodeToJsonElement(GatewayEvent.ToolStarted.serializer(), event)
            is GatewayEvent.ToolCompleted -> GatewayJson.encodeToJsonElement(GatewayEvent.ToolCompleted.serializer(), event)
            is GatewayEvent.ApprovalRequired -> GatewayJson.encodeToJsonElement(GatewayEvent.ApprovalRequired.serializer(), event)
            is GatewayEvent.ApprovalResolved -> GatewayJson.encodeToJsonElement(GatewayEvent.ApprovalResolved.serializer(), event)
            is GatewayEvent.OutputDone -> GatewayJson.encodeToJsonElement(GatewayEvent.OutputDone.serializer(), event)
            is GatewayEvent.SpeechChunk -> GatewayJson.encodeToJsonElement(GatewayEvent.SpeechChunk.serializer(), event)
            is GatewayEvent.TurnCompleted -> GatewayJson.encodeToJsonElement(GatewayEvent.TurnCompleted.serializer(), event)
            is GatewayEvent.Error -> GatewayJson.encodeToJsonElement(GatewayEvent.Error.serializer(), event)
            is GatewayEvent.Unknown -> GatewayJson.encodeToJsonElement(GatewayEvent.Unknown.serializer(), event)
        }.jsonObject
        val withType = buildMap<String, JsonElement> {
            put("type", kotlinx.serialization.json.JsonPrimitive(event.typeName))
            body.forEach { (k, v) -> if (k != "type") put(k, v) }
        }
        return GatewayJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(withType))
    }
}
