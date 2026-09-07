package dev.claudewear.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gateway's golden fixtures, decoded here. If the gateway grows a field the watch must know
 * about, this test is where the two sides meet.
 */
class ContractTest {
    private val fixtures = File(System.getProperty("gateway.fixtures") ?: "../../gateway/fixtures/v1")

    private fun eventFixtures() = fixtures.listFiles { f -> f.extension == "json" && !f.name.startsWith("request-") }!!.sorted()

    @Test
    fun `fixture directory is present`() {
        assertTrue("missing ${fixtures.absolutePath}; run gateway/scripts/gen_fixtures.py", fixtures.isDirectory)
        assertTrue(eventFixtures().size >= 11)
    }

    @Test
    fun `every event fixture decodes to a known type and re-encodes to the same JSON`() {
        for (file in eventFixtures()) {
            val text = file.readText()
            val event = GatewayEvents.decode(text)
            assertTrue("${file.name} decoded to Unknown", event !is GatewayEvent.Unknown)
            assertEquals(file.name, Json.parseToJsonElement(text).jsonObject["type"]!!.jsonPrimitive.content, event.typeName)
            val again = Json.parseToJsonElement(GatewayEvents.encode(event)).jsonObject
            assertEquals(file.name, canonical(Json.parseToJsonElement(text).jsonObject), canonical(again))
        }
    }

    @Test
    fun `request fixtures match the shapes the phone sends`() {
        val text = File(fixtures, "request-text.json").readText()
        val req = GatewayJson.decodeFromString(TextTurnRequest.serializer(), text)
        assertEquals("what's on my calendar today?", req.input.text)
        assertEquals(canonical(Json.parseToJsonElement(text).jsonObject), canonical(Json.parseToJsonElement(GatewayJson.encodeToString(TextTurnRequest.serializer(), req)).jsonObject))
        val approval = GatewayJson.decodeFromString(ApprovalDecision.serializer(), File(fixtures, "request-approval.json").readText())
        assertEquals("allow", approval.decision)
    }

    @Test
    fun `unknown event types are carried not thrown`() {
        val e = GatewayEvents.decode("""{"type":"future.thing","turn_id":"t","seq":4,"at":"x","weird":1}""")
        assertTrue(e is GatewayEvent.Unknown)
        assertEquals("future.thing", e.typeName)
        assertEquals("t", e.turnId)
    }

    @Test
    fun `unknown fields on known events are ignored`() {
        val e = GatewayEvents.decode("""{"type":"output.done","turn_id":"t","seq":9,"at":"x","text":"hi","extra":true}""")
        assertEquals("hi", (e as GatewayEvent.OutputDone).text)
    }

    @Test
    fun `high risk flag`() {
        val e = GatewayEvents.decode(File(fixtures, "approval-required-high.json").readText()) as GatewayEvent.ApprovalRequired
        assertTrue(e.highRisk)
        val n = GatewayEvents.decode(File(fixtures, "approval-required-normal.json").readText()) as GatewayEvent.ApprovalRequired
        assertTrue(!n.highRisk)
    }

    /**
     * Sort keys recursively and drop nulls: v1 says an absent field and a null field mean the same
     * thing (API.md, "Stability"), and Python writes `null` where Kotlin omits.
     */
    private fun canonical(obj: JsonObject): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            obj.filterValues { it !is JsonNull }.toSortedMap()
                .mapValues { (_, v) -> if (v is JsonObject) Json.parseToJsonElement(canonical(v)) else v },
        ),
    )
}
