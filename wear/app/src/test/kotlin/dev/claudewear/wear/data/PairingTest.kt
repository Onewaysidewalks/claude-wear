package dev.claudewear.wear.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pairing against a stub bridge, on the JVM.
 *
 * This exists because the first CI run found a missing kotlinx-serialization plugin on
 * :app the slow way — a green build, a booted emulator, an installed APK, and only then a
 * "Serializer for class 'PairResult' is not found" at runtime. A generated serializer is
 * either there or it is not, and finding that out should cost seconds.
 */
class PairingTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun exchangesACodeForAToken() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""{"deviceId":"dev_7c1f0a9b","token":"tok_abc","protocolVersion":1}"""),
        )

        val result = Pairing.pair(baseUrl(), "12345678", "Galaxy Watch")

        assertEquals("dev_7c1f0a9b", result.deviceId)
        assertEquals("tok_abc", result.token)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pair", request.path)
        val body = request.body.readUtf8()
        assertTrue("the code should be in the body, not the URL", body.contains("\"code\":\"12345678\""))
        assertTrue(body.contains("\"deviceName\":\"Galaxy Watch\""))
    }

    @Test
    fun tolerantOfFieldsItDoesNotKnow() = runBlocking {
        // The bridge may grow its pairing response; that should not break an older watch.
        server.enqueue(MockResponse().setBody("""{"deviceId":"dev_1","token":"t","somethingNew":true}"""))
        assertEquals("dev_1", Pairing.pair(baseUrl(), "12345678", "watch").deviceId)
    }

    @Test
    fun saysWhatWentWrongWhenTheCodeIsRejected() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"nope"}"""))
        val failure = runCatching { runBlocking { Pairing.pair(baseUrl(), "00000000", "watch") } }.exceptionOrNull()
        assertTrue(failure is PairingFailed)
        assertTrue(
            "the message should tell the wearer what to do: ${failure?.message}",
            failure?.message?.contains("expired") == true,
        )
    }

    @Test
    fun reportsAnyOtherFailureWithItsStatus() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val failure = runCatching { runBlocking { Pairing.pair(baseUrl(), "12345678", "watch") } }.exceptionOrNull()
        assertTrue(failure is PairingFailed)
        assertTrue(failure?.message?.contains("500") == true)
    }
}
