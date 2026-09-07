package dev.claudewear.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun `relayed events carry the exact gateway payload`() {
        val payload = """{"type":"output.done","turn_id":"turn_1","seq":8,"at":"2026-09-07T12:00:00.000Z","text":"Done."}"""
        val relayed = RelayedEvent(requestId = "r1", eventJson = payload)
        val wire = WatchProtocol.json.encodeToString(RelayedEvent.serializer(), relayed)
        val back = WatchProtocol.json.decodeFromString(RelayedEvent.serializer(), wire)
        assertEquals(payload, back.eventJson)
        assertEquals("Done.", (back.event() as GatewayEvent.OutputDone).text)
    }

    @Test
    fun `audio header is one line of json`() {
        val header = AudioChannel(requestId = "r2", locale = "en-GB").headerBytes()
        val text = header.decodeToString()
        assertEquals('\n', text.last())
        assertEquals(1, text.count { it == '\n' })
        val parsed = AudioChannel.parseHeader(text.trimEnd())
        assertEquals(16000, parsed.sampleRate)
        assertEquals("en-GB", parsed.locale)
        assertEquals("pcm_s16le", parsed.format)
    }

    @Test
    fun `text turn defaults`() {
        val t = WatchProtocol.json.decodeFromString(TextTurn.serializer(), """{"request_id":"r3","text":"hello"}""")
        assertEquals("watch", t.session)
        assertEquals("en-US", t.locale)
    }
}
