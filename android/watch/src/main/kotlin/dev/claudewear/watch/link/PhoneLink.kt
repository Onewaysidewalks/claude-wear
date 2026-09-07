package dev.claudewear.watch.link

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.claudewear.shared.ApprovalAnswer
import dev.claudewear.shared.AudioChannel
import dev.claudewear.shared.Cancel
import dev.claudewear.shared.TextTurn
import dev.claudewear.shared.WatchProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * The watch's only outward door: the phone, via the Data Layer. Finds the node that advertises
 * the relay capability, sends messages to it, and streams audio to it over a channel.
 */
class PhoneLink(context: Context) {
    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)
    private val channels = Wearable.getChannelClient(app)
    private val capabilities = Wearable.getCapabilityClient(app)
    private val nodes = Wearable.getNodeClient(app)

    class NoPhone(message: String) : IOException(message)

    /**
     * The relay node. Prefers a nearby (Bluetooth) node; a remote one means the Data Layer is
     * routing through the cloud, which the testing matrix says to measure, not assume.
     */
    suspend fun relayNode(): Node {
        val capable = runCatching {
            capabilities.getCapability(WatchProtocol.CAPABILITY_RELAY, CapabilityClient.FILTER_REACHABLE).await().nodes
        }.getOrDefault(emptySet())
        capable.firstOrNull { it.isNearby }?.let { return it }
        capable.firstOrNull()?.let { return it }
        val connected = runCatching { nodes.connectedNodes.await() }.getOrDefault(emptyList())
        throw NoPhone(
            if (connected.isEmpty()) "no phone connected"
            else "phone connected but the Hermes app is not installed on it",
        )
    }

    suspend fun sendText(turn: TextTurn) {
        val node = relayNode()
        messages.sendMessage(node.id, WatchProtocol.PATH_TURN_TEXT, WatchProtocol.json.encodeToString(TextTurn.serializer(), turn).encodeToByteArray()).await()
    }

    suspend fun sendApproval(answer: ApprovalAnswer) {
        val node = relayNode()
        messages.sendMessage(node.id, WatchProtocol.PATH_APPROVAL, WatchProtocol.json.encodeToString(ApprovalAnswer.serializer(), answer).encodeToByteArray()).await()
    }

    suspend fun sendCancel(requestId: String) {
        val node = relayNode()
        messages.sendMessage(node.id, WatchProtocol.PATH_CANCEL, WatchProtocol.json.encodeToString(Cancel.serializer(), Cancel(requestId)).encodeToByteArray()).await()
    }

    suspend fun ping() {
        val node = relayNode()
        messages.sendMessage(node.id, WatchProtocol.PATH_PING, ByteArray(0)).await()
    }

    /**
     * Streams one utterance: header line, then every PCM chunk from [pcm] as it arrives, then
     * closes the channel, which is how the phone knows the utterance ended.
     */
    suspend fun streamAudio(header: AudioChannel, pcm: Flow<ByteArray>) = withContext(Dispatchers.IO) {
        val node = relayNode()
        val channel = channels.openChannel(node.id, WatchProtocol.PATH_TURN_AUDIO).await()
        val out: OutputStream = channels.getOutputStream(channel).await()
        try {
            out.write(header.headerBytes())
            pcm.collect { chunk ->
                out.write(chunk)
            }
            out.flush()
        } finally {
            runCatching { out.close() }
            runCatching { channels.close(channel).await() }
        }
    }
}
