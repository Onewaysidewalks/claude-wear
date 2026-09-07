package dev.claudewear.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun `events are delivered on the blank line, comments ignored`() {
        val p = SseParser()
        assertNull(p.feed(": keepalive"))
        assertNull(p.feed(""))
        assertNull(p.feed("event: output.delta"))
        assertNull(p.feed("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", p.feed(""))
        assertEquals("output.delta", p.lastEventName)
        assertNull(p.feed("data: line1"))
        assertNull(p.feed("data: line2"))
        assertEquals("line1\nline2", p.feed(""))
    }
}
