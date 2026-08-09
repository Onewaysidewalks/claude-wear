package dev.claudewear.protocol

import kotlinx.serialization.json.Json

/**
 * The one [Json] both the watch and the contract tests use.
 *
 * `ignoreUnknownKeys = false` is the point of the exercise: a field the bridge started
 * sending that this side does not know about fails the Android build, rather than being
 * silently discarded until someone notices the watch is missing information.
 */
public val ProtocolJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    classDiscriminator = "type"
}
