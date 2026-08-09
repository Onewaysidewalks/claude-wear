package dev.claudewear.protocol

import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of the protocol contract. The TypeScript half is
 * bridge/test/protocol-golden.test.ts, over the same fixtures.
 *
 * Decode -> re-encode -> compare parsed trees. Structural, not textual: key order is not
 * part of the wire contract. A field renamed, dropped or added on the bridge side still
 * fails here, because [ProtocolJson] refuses unknown keys and writes every declared one.
 */
class GoldenContractTest {

    private val goldenDir: File =
        File(System.getProperty("protocol.golden.dir") ?: "../../protocol/golden")

    private fun goldens(direction: String): List<Pair<String, String>> {
        val dir = File(goldenDir, direction)
        assertTrue("no golden fixtures under $dir", dir.isDirectory)
        return dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }
            .sortedBy { it.name }
            .map { "$direction/${it.name}" to it.readText() }
    }

    private fun parse(text: String): JsonElement = ProtocolJson.parseToJsonElement(text)

    @Test
    fun everyClientGoldenRoundTrips() {
        val fixtures = goldens("client")
        assertTrue("expected client fixtures", fixtures.isNotEmpty())
        for ((name, text) in fixtures) {
            val decoded = ProtocolJson.decodeFromString<ClientMessage>(text)
            val reencoded = ProtocolJson.encodeToString<ClientMessage>(decoded)
            assertEquals("$name did not survive a round trip", parse(text), parse(reencoded))
        }
    }

    @Test
    fun everyServerGoldenRoundTrips() {
        val fixtures = goldens("server")
        assertTrue("expected server fixtures", fixtures.isNotEmpty())
        for ((name, text) in fixtures) {
            val decoded = ProtocolJson.decodeFromString<ServerEvent>(text)
            val reencoded = ProtocolJson.encodeToString<ServerEvent>(decoded)
            assertEquals("$name did not survive a round trip", parse(text), parse(reencoded))
        }
    }

    @Test
    fun everyServerEventCarriesItsSessionAndSequence() {
        for ((name, text) in goldens("server")) {
            val event = ProtocolJson.decodeFromString<ServerEvent>(text)
            assertTrue("$name has an empty sessionId", event.sessionId.isNotEmpty())
            assertTrue("$name has a non-positive seq", event.seq > 0)
        }
    }

    @Test
    fun everyMessageTypeHasAFixture() {
        // The discriminator values actually present in the fixtures, so adding a message
        // without a fixture is caught here rather than in whichever language forgot it.
        val types = (goldens("client") + goldens("server"))
            .map { (_, text) -> (parse(text) as JsonObject).getValue("type").jsonPrimitive.content }
            .toSet()
        val expected = setOf(
            "hello", "subscribe", "newSession", "prompt", "answer",
            "permission", "interrupt", "setMode", "renameSession",
            "sessions", "turn", "ask", "text", "done", "resolved", "error",
        )
        assertEquals(emptySet<String>(), expected - types)
    }

    @Test
    fun anUnknownFieldIsRejectedRatherThanIgnored() {
        val drifted = """
            {"type":"text","sessionId":"s_1","seq":1,"text":"hi","somethingNew":true}
        """.trimIndent()
        val failed = runCatching { ProtocolJson.decodeFromString<ServerEvent>(drifted) }.isFailure
        assertTrue("an unknown field decoded silently; the contract test is not protecting anything", failed)
    }

    @Test
    fun aRegistryEventUsesTheReservedSessionId() {
        val text = File(goldenDir, "server/sessions.json").readText()
        val event = ProtocolJson.decodeFromString<ServerEvent>(text) as SessionsEvent
        assertEquals(REGISTRY_SESSION_ID, event.sessionId)
        assertEquals(2, event.sessions.size)
    }
}
