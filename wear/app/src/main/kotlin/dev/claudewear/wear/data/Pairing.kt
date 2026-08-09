package dev.claudewear.wear.data

import dev.claudewear.wear.transport.WebSocketTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The bridge on an emulator's host. A real watch is told the address at pairing time. */
const val DEFAULT_BRIDGE_URL: String = "http://10.0.2.2:8787"

@Serializable
data class PairResult(val deviceId: String, val token: String)

class PairingFailed(message: String) : Exception(message)

/**
 * Exchanges the 8-digit code the bridge printed for a long-lived, device-scoped token.
 * Single use, five minute TTL — adequate for an endpoint that is not reachable from
 * outside your network, which is the assumption the whole security posture rests on.
 */
object Pairing {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        client: OkHttpClient = WebSocketTransport.defaultClient(),
    ): PairResult = withContext(Dispatchers.IO) {
        val body = """{"code":"$code","deviceName":"$deviceName"}""".toRequestBody(jsonMediaType)
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/pair").post(body).build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PairingFailed(
                    when (response.code) {
                        401 -> "That code is wrong, used, or expired. Restart the bridge for a fresh one."
                        else -> "The bridge said ${response.code}: $text"
                    },
                )
            }
            json.decodeFromString<PairResult>(text)
        }
    }
}
