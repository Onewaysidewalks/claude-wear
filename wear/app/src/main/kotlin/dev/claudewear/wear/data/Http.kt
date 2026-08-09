package dev.claudewear.wear.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** The one OkHttp client the watch uses, for pairing and for the socket. */
object Http {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // A canUseTool request may sit unanswered for hours. The ping is what keeps the
        // socket alive across that without anything being sent on it, and no read timeout
        // is what stops the wait from being mistaken for a dead connection.
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
