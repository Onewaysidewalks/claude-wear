package dev.claudewear.watch

import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.GatewayEvents
import dev.claudewear.shared.RelayedEvent
import dev.claudewear.watch.ui.Phase
import dev.claudewear.watch.ui.TurnStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStoreTest {
    private val store = TurnStore()

    private fun relayed(requestId: String, e: GatewayEvent) = RelayedEvent(requestId, GatewayEvents.encode(e))

    @Test
    fun `a voice turn walks listening, thinking, speaking, idle`() {
        store.begin("r1", Phase.Listening)
        store.listeningDone()
        assertEquals(Phase.Thinking, store.state.value.phase)
        store.onRelayed(relayed("r1", GatewayEvent.TurnStarted("t1", 1, "", "watch", "audio")))
        store.onRelayed(relayed("r1", GatewayEvent.InputTranscript("t1", 2, "", "lights off")))
        store.onRelayed(relayed("r1", GatewayEvent.ToolStarted("t1", 3, "", "c1", "ha_call_service")))
        assertEquals("ha_call_service", store.state.value.activity)
        store.onRelayed(relayed("r1", GatewayEvent.ToolCompleted("t1", 4, "", "c1", "ha_call_service")))
        store.onRelayed(relayed("r1", GatewayEvent.OutputDone("t1", 5, "", "Lights are off.")))
        assertEquals(Phase.Speaking, store.state.value.phase)
        assertEquals("lights off", store.state.value.heard)
        assertEquals("Lights are off.", store.state.value.answer)
        store.onRelayed(relayed("r1", GatewayEvent.TurnCompleted("t1", 6, "", "ok")))
        assertEquals(Phase.Speaking, store.state.value.phase) // still speaking until TTS finishes
        store.spoken()
        assertEquals(Phase.Idle, store.state.value.phase)
    }

    @Test
    fun `the answer is handed to the speaker once`() = runBlocking {
        store.begin("r1", Phase.Thinking)
        var spoken: String? = null
        val job = launch { spoken = store.speak.first() }
        yield()
        store.onRelayed(relayed("r1", GatewayEvent.OutputDone("t1", 5, "", "Hello there.")))
        job.join()
        assertEquals("Hello there.", spoken)
    }

    @Test
    fun `events for an old request are ignored`() {
        store.begin("r1", Phase.Thinking)
        store.begin("r2", Phase.Thinking)
        store.onRelayed(relayed("r1", GatewayEvent.OutputDone("t1", 5, "", "stale")))
        assertEquals("", store.state.value.answer)
        assertEquals(Phase.Thinking, store.state.value.phase)
    }

    @Test
    fun `approval interrupts and resumes`() {
        store.begin("r1", Phase.Thinking)
        store.onRelayed(relayed("r1", GatewayEvent.ApprovalRequired("t1", 2, "", "a1", "rm -rf build", "cleanup", "high", "later")))
        val s = store.state.value
        assertEquals(Phase.Approval, s.phase)
        assertTrue(s.approval!!.highRisk)
        store.onRelayed(relayed("r1", GatewayEvent.ApprovalResolved("t1", 3, "", "a1", "deny")))
        assertEquals(Phase.Thinking, store.state.value.phase)
        assertNull(store.state.value.approval)
    }

    @Test
    fun `errors and cancellations land where the screen expects`() {
        store.begin("r1", Phase.Thinking)
        store.onRelayed(relayed("r1", GatewayEvent.Error("", 0, "", "phone_offline", "no gateway", true)))
        assertEquals(Phase.Error, store.state.value.phase)
        assertEquals("no gateway", store.state.value.error)
        store.reset()
        store.begin("r2", Phase.Thinking)
        store.onRelayed(relayed("r2", GatewayEvent.TurnCompleted("t2", 1, "", "cancelled")))
        assertEquals(Phase.Idle, store.state.value.phase)
        store.begin("r3", Phase.Thinking)
        store.onRelayed(relayed("r3", GatewayEvent.TurnCompleted("t3", 1, "", "error")))
        assertEquals(Phase.Error, store.state.value.phase)
    }
}
