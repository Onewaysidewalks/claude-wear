package dev.claudewear.phone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayConfigTest {
    @Test
    fun `plain http is only for the tailnet or loopback`() {
        assertNull(GatewayConfig("http://100.101.102.103:8731", "hg1_x").problem())
        assertNull(GatewayConfig("http://127.0.0.1:8731", "hg1_x").problem())
        assertNull(GatewayConfig("http://localhost:8731", "hg1_x").problem())
        assertNull(GatewayConfig("https://mac.example.ts.net", "hg1_x").problem())
        assertNotNull(GatewayConfig("http://192.168.1.20:8731", "hg1_x").problem())
        assertNotNull(GatewayConfig("http://100.200.1.1:8731", "hg1_x").problem()) // outside 100.64/10
        assertNotNull(GatewayConfig("http://example.com", "hg1_x").problem())
    }

    @Test
    fun `missing pieces are named`() {
        assertEquals("enter the gateway URL", GatewayConfig("", "hg1_x").problem())
        assertEquals("enter the device token", GatewayConfig("http://127.0.0.1", "").problem())
        assertNotNull(GatewayConfig("http://127.0.0.1", "abc").problem())
        assertNotNull(GatewayConfig("ftp://127.0.0.1", "hg1_x").problem())
    }

    @Test
    fun `normalize adds a scheme and trims`() {
        assertEquals("http://100.1.2.3:8731", GatewaySettings.normalize(" 100.1.2.3:8731/ "))
        assertEquals("https://x.ts.net", GatewaySettings.normalize("https://x.ts.net/"))
    }
}
