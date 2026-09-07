package dev.claudewear.phone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the phone keeps the two things it must not lose or leak: the gateway URL and the device token. */
data class GatewayConfig(val baseUrl: String, val token: String) {
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    /**
     * Why this config must not be saved, or null. Mirrors the gateway's own bind rule: plain
     * HTTP is only ever spoken to a Tailscale (100.64.0.0/10) or loopback address, because
     * Tailscale is the encryption. Anything else has to be HTTPS.
     */
    fun problem(): String? {
        if (baseUrl.isBlank()) return "enter the gateway URL"
        if (token.isBlank()) return "enter the device token"
        if (!token.startsWith("hg1_")) return "that is not a gateway device token (they start with hg1_)"
        val uri = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return "that URL does not parse"
        val host = uri.host ?: return "that URL has no host"
        return when (uri.scheme) {
            "https" -> null
            "http" -> if (isTailnetOrLoopback(host)) null else "plain http is only allowed to a Tailscale (100.x.y.z) or loopback address"
            else -> "the URL must start with http:// or https://"
        }
    }

    companion object {
        fun isTailnetOrLoopback(host: String): Boolean {
            if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1" || host == "[::1]") return true
            val parts = host.split('.')
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                val a = parts[0].toInt()
                val b = parts[1].toInt()
                return a == 100 && b in 64..127
            }
            return host.startsWith("[fd7a:115c:a1e0", ignoreCase = true)
        }
    }
}

class GatewaySettings(private val prefs: SharedPreferences) {
    private val _config = MutableStateFlow(read())
    val config: StateFlow<GatewayConfig> = _config

    private fun read() = GatewayConfig(
        baseUrl = prefs.getString(KEY_URL, "") ?: "",
        token = prefs.getString(KEY_TOKEN, "") ?: "",
    )

    fun save(baseUrl: String, token: String) {
        prefs.edit().putString(KEY_URL, normalize(baseUrl)).putString(KEY_TOKEN, token.trim()).apply()
        _config.value = read()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _config.value = read()
    }

    companion object {
        private const val KEY_URL = "gateway_url"
        private const val KEY_TOKEN = "device_token"

        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }

        fun encrypted(context: Context): GatewaySettings {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "gateway_secrets",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return GatewaySettings(prefs)
        }
    }
}
