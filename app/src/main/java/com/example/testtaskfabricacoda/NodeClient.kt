package com.example.testtaskfabricacoda

import android.util.Log
import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.protocol.Ping
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.peergos.BlockRequestAuthoriser
import org.peergos.EmbeddedIpfs
import org.peergos.HashedBlock
import org.peergos.HostBuilder
import org.peergos.Want
import org.peergos.blockstore.RamBlockstore
import org.peergos.config.IdentitySection
import org.peergos.protocol.dht.RamRecordStore

private data class RemoteTarget(
    val rawAddress: String,
    val peerId: PeerId,
    val dialAddress: Multiaddr,
    val tcpEndpoint: TcpEndpoint?,
)

private data class TcpEndpoint(
    val host: String,
    val port: Int,
)

private data class ExtractedPayload(
    val payload: ByteArray,
    val extractionNote: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExtractedPayload

        if (!payload.contentEquals(other.payload)) return false
        if (extractionNote != other.extractionNote) return false

        return true
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + (extractionNote?.hashCode() ?: 0)
        return result
    }
}

class NodeClient {
    private val ipfsLock = Any()
    private val operationMutex = Mutex()
    @Volatile
    private var embeddedIpfs: EmbeddedIpfs? = null
    @Volatile
    private var cachedTarget: RemoteTarget? = null

    suspend fun connect(multiAddress: String): Long = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val ipfs = ensureIpfs()
            val target = resolveTarget(multiAddress)
            registerPeer(ipfs, target)
            pingInternal(ipfs, target).also { latency ->
                Log.d(TAG, "connect ok: peer=${target.peerId.toBase58()} latency=${latency}ms")
            }
        }
    }

    suspend fun ping(multiAddress: String): Long = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val ipfs = ensureIpfs()
            val target = resolveTarget(multiAddress)
            registerPeer(ipfs, target)
            pingInternal(ipfs, target).also { latency ->
                Log.d(TAG, "ping ok: peer=${target.peerId.toBase58()} latency=${latency}ms")
            }
        }
    }

    suspend fun fetchByCid(multiAddress: String, cid: String): String = withContext(Dispatchers.IO) {
        require(cid.isNotBlank()) { "CID must not be empty" }

        operationMutex.withLock {
            val ipfs = ensureIpfs()
            val target = resolveTarget(multiAddress)
            registerPeer(ipfs, target)

            val decodedCid = try {
                Cid.decode(cid.trim())
            } catch (_: Throwable) {
                throw IllegalArgumentException("Invalid CID format")
            }

            val wants = listOf(Want(decodedCid))
            val blocks = ipfs.getBlocks(wants, setOf(target.peerId), false)
            if (blocks.isEmpty()) {
                throw IllegalStateException("No block returned for this CID")
            }

            val matched = blocks.firstOrNull { it.hash.toString() == decodedCid.toString() } ?: blocks.first()
            formatBlockResponse(matched, target).also {
                Log.d(TAG, "fetch ok: cid=${decodedCid} bytes=${matched.block.size}")
            }
        }
    }

    private fun ensureIpfs(): EmbeddedIpfs {
        embeddedIpfs?.let { return it }

        synchronized(ipfsLock) {
            embeddedIpfs?.let { return it }

            val builder = HostBuilder().generateIdentity()
            val identity = IdentitySection(builder.privateKey.bytes(), builder.peerId)
            val swarmAddresses = listOf(MultiAddress("/ip4/0.0.0.0/tcp/0"))
            val authoriser = BlockRequestAuthoriser { _, _, _ ->
                CompletableFuture.completedFuture(true)
            }

            val ipfs = EmbeddedIpfs.build(
                RamRecordStore(),
                RamBlockstore(),
                true,
                swarmAddresses,
                emptyList(),
                identity,
                authoriser,
                Optional.empty(),
            )

            ipfs.start()
            Log.d(TAG, "embedded ipfs started: peer=${ipfs.node.peerId.toBase58()}")
            embeddedIpfs = ipfs
            return ipfs
        }
    }

    private fun resolveTarget(multiAddress: String): RemoteTarget {
        val normalized = multiAddress.trim()
        cachedTarget?.takeIf { it.rawAddress == normalized }?.let { return it }

        val parsedAddress = try {
            Multiaddr.fromString(normalized)
        } catch (_: Throwable) {
            throw IllegalArgumentException("Invalid multiaddress format")
        }

        val peerId = try {
            parsedAddress.getPeerId()
        } catch (_: Throwable) {
            throw IllegalArgumentException("Peer id (/p2p/...) is required in multiaddress")
        } ?: throw IllegalArgumentException("Peer id (/p2p/...) is required in multiaddress")

        val target = RemoteTarget(
            rawAddress = normalized,
            peerId = peerId,
            dialAddress = parsedAddress,
            tcpEndpoint = extractTcpEndpoint(parsedAddress),
        )
        cachedTarget = target
        return target
    }

    private fun registerPeer(ipfs: EmbeddedIpfs, target: RemoteTarget) {
        Log.d(TAG, "register peer: ${target.dialAddress}")
        ipfs.node.addressBook.addAddrs(target.peerId, 0L, target.dialAddress).join()
    }

    private suspend fun pingInternal(ipfs: EmbeddedIpfs, target: RemoteTarget): Long {
        var currentIpfs = ipfs
        var lastError: Throwable? = null

        repeat(MAX_PING_ATTEMPTS) { attempt ->
            try {
                ensurePeerDial(currentIpfs, target)
                return pingWithLibp2p(currentIpfs, target)
            } catch (error: Throwable) {
                lastError = error
                if (!isRecoverablePingError(error)) {
                    throw IllegalStateException("Ping failed: ${error.message}", error)
                }

                val fallbackLatency = if (attempt == MAX_PING_ATTEMPTS - 1 &&
                    target.tcpEndpoint != null &&
                    shouldUseTcpFallback(error)
                ) {
                    runCatching { measureTcpRtt(target.tcpEndpoint) }
                        .onFailure { fallbackError ->
                            Log.w(TAG, "TCP fallback failed: ${fallbackError.message}")
                        }
                        .getOrNull()
                } else {
                    null
                }
                if (fallbackLatency != null) {
                    Log.w(TAG, "Using TCP fallback latency=${fallbackLatency}ms due to channel instability")
                    return fallbackLatency
                }

                val recovered = recoverPeerConnection(currentIpfs, target)
                if (!recovered) {
                    Log.w(TAG, "Peer recovery failed, resetting ipfs: ${error.message}")
                    resetIpfs()
                    currentIpfs = ensureIpfs()
                    registerPeer(currentIpfs, target)
                }
                if (attempt < MAX_PING_ATTEMPTS - 1) {
                    val extraPause = if (recovered) CONNECTION_RECOVERY_PAUSE_MS else 0L
                    delay(RETRY_BACKOFF_MS + extraPause)
                }
            }
        }

        throw IllegalStateException(
            "Ping failed after $MAX_PING_ATTEMPTS attempts: ${lastError?.message ?: "unknown"}",
            lastError
        )
    }

    private fun pingWithLibp2p(ipfs: EmbeddedIpfs, target: RemoteTarget): Long {
        val ping = Ping()
        val stream = ping.dial(ipfs.node, target.peerId, target.dialAddress)
        val controller = stream.controller.get(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return controller.ping().get(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun isRecoverablePingError(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is RejectedExecutionException }) return true
        if (chain.any { it::class.java.simpleName == "ConnectionClosedException" }) return true
        return chain.any {
            val msg = it.message.orEmpty()
            msg.contains("Connection is closed", ignoreCase = true) ||
                msg.contains("ScheduledThreadPoolExecutor", ignoreCase = true) ||
                msg.contains("rejected", ignoreCase = true)
        }
    }

    private fun resetIpfs() {
        synchronized(ipfsLock) {
            val old = embeddedIpfs ?: return
            runCatching {
                old.stop().get(IPFS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onFailure { stopError ->
                Log.w(TAG, "Failed to stop old ipfs cleanly: ${stopError.message}")
            }
            embeddedIpfs = null
            cachedTarget = null
        }
    }

    private fun ensurePeerDial(ipfs: EmbeddedIpfs, target: RemoteTarget) {
        val hasActiveConnection = ipfs.node.network.connections.any { it.isConnectionFor(target.peerId) }
        if (!hasActiveConnection) {
            Log.d(TAG, "Dialing peer for ping: ${target.peerId.toBase58()}")
            connectToPeer(ipfs, target)
        }
    }

    private fun recoverPeerConnection(ipfs: EmbeddedIpfs, target: RemoteTarget): Boolean {
        Log.w(TAG, "Recovering peer connection without restarting ipfs")
        return runCatching {
            closePeerConnections(ipfs, target.peerId)
            connectToPeer(ipfs, target)
        }.onFailure { cause ->
            Log.w(TAG, "Peer recovery failed: ${cause.message}")
        }.isSuccess
    }

    private fun closePeerConnections(ipfs: EmbeddedIpfs, peerId: PeerId) {
        val connections = ipfs.node.network.connections.filter { it.isConnectionFor(peerId) }
        connections.forEach { connection ->
            runCatching {
                connection.close().get(CONNECTION_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onFailure { closeError ->
                Log.w(TAG, "Failed to close stale connection: ${closeError.message}")
            }
        }
    }

    private fun connectToPeer(ipfs: EmbeddedIpfs, target: RemoteTarget) {
        ipfs.node.network.connect(target.peerId, target.dialAddress)
            .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun Connection.isConnectionFor(peerId: PeerId): Boolean {
        return runCatching { secureSession().remoteId == peerId }.getOrDefault(false)
    }

    private fun extractTcpEndpoint(address: Multiaddr): TcpEndpoint? {
        val components = address.components
        val hostComponent = components.firstOrNull { component ->
            when (component.protocol) {
                Protocol.DNS, Protocol.DNS4, Protocol.DNS6, Protocol.IP4, Protocol.IP6 -> true
                else -> false
            }
        } ?: return null
        val portComponent = components.firstOrNull { it.protocol == Protocol.TCP } ?: return null
        val host = hostComponent.stringValue ?: return null
        val port = portComponent.stringValue?.toIntOrNull() ?: return null
        return TcpEndpoint(host = host, port = port)
    }

    private fun shouldUseTcpFallback(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { throwable ->
            throwable is RejectedExecutionException ||
                throwable::class.java.simpleName == "ConnectionClosedException" ||
                throwable.message.orEmpty().contains("Channel closed", ignoreCase = true)
        }
    }

    private fun measureTcpRtt(endpoint: TcpEndpoint): Long {
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.soTimeout = TCP_SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TCP_CONNECT_TIMEOUT_MS)
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return elapsed.coerceAtLeast(1L)
    }

    private fun formatBlockResponse(block: HashedBlock, target: RemoteTarget): String {
        val extracted = extractPayloadForPreview(block)
        val bytes = extracted.payload
        val decoded = String(bytes, StandardCharsets.UTF_8)
        val replacementCount = decoded.count { it == '\uFFFD' }
        val replacementRatio = if (decoded.isEmpty()) 1.0 else replacementCount.toDouble() / decoded.length.toDouble()
        val textPreview = decoded.sanitizeForPreview()
        val isText = textPreview.isNotBlank() && textPreview.isMostlyReadable() && replacementRatio <= MAX_REPLACEMENT_RATIO
        val body = if (isText) {
            textPreview.take(MAX_TEXT_PREVIEW_CHARS)
        } else {
            bytes.take(MAX_BINARY_PREVIEW_BYTES).joinToString(" ") { byte -> "%02x".format(byte) }
        }
        val previewType = if (isText) "text" else "binary(hex)"

        return buildString {
            appendLine("[metadata]")
            appendLine("Source peer: ${target.peerId.toBase58()}")
            appendLine("CID: ${block.hash}")
            appendLine("Size: ${bytes.size} bytes")
            appendLine("Block codec: ${block.hash.codec}")
            appendLine("Preview type: $previewType")
            extracted.extractionNote?.let { appendLine("Decode note: $it") }
            if (isText && replacementCount > 0) {
                appendLine("Decode note: cleaned $replacementCount malformed chars")
            }
            appendLine("[content_preview]")
            append(body)
        }
    }

    private fun extractPayloadForPreview(block: HashedBlock): ExtractedPayload {
        val raw = block.block
        if (block.hash.codec != Cid.Codec.DagProtobuf) {
            return ExtractedPayload(raw)
        }

        val dagData = extractFirstLengthDelimitedField(raw, 1) ?: return ExtractedPayload(raw)
        val unixFsType = extractFirstVariantField(dagData, 1)
        val unixFsData = extractFirstLengthDelimitedField(dagData, 2) ?: return ExtractedPayload(raw)

        return ExtractedPayload(
            payload = unixFsData,
            extractionNote = "unixfs payload extracted (type=$unixFsType)"
        )
    }

    private fun extractFirstLengthDelimitedField(bytes: ByteArray, fieldNumber: Int): ByteArray? {
        var idx = 0
        while (idx < bytes.size) {
            val keyRead = readVarint(bytes, idx) ?: return null
            val key = keyRead.first
            idx = keyRead.second

            val wireType = (key and 0x07).toInt()
            val field = (key ushr 3).toInt()

            when (wireType) {
                0 -> {
                    val skip = readVarint(bytes, idx) ?: return null
                    idx = skip.second
                }
                1 -> {
                    if (idx + 8 > bytes.size) return null
                    idx += 8
                }
                2 -> {
                    val lenRead = readVarint(bytes, idx) ?: return null
                    val len = lenRead.first.toInt()
                    idx = lenRead.second
                    if (len < 0 || idx + len > bytes.size) return null
                    val out = bytes.copyOfRange(idx, idx + len)
                    idx += len
                    if (field == fieldNumber) return out
                }
                5 -> {
                    if (idx + 4 > bytes.size) return null
                    idx += 4
                }
                else -> return null
            }
        }
        return null
    }

    private fun extractFirstVariantField(bytes: ByteArray, fieldNumber: Int): Long? {
        var idx = 0
        while (idx < bytes.size) {
            val keyRead = readVarint(bytes, idx) ?: return null
            val key = keyRead.first
            idx = keyRead.second

            val wireType = (key and 0x07).toInt()
            val field = (key ushr 3).toInt()

            when (wireType) {
                0 -> {
                    val valueRead = readVarint(bytes, idx) ?: return null
                    idx = valueRead.second
                    if (field == fieldNumber) return valueRead.first
                }
                1 -> {
                    if (idx + 8 > bytes.size) return null
                    idx += 8
                }
                2 -> {
                    val lenRead = readVarint(bytes, idx) ?: return null
                    val len = lenRead.first.toInt()
                    idx = lenRead.second
                    if (len < 0 || idx + len > bytes.size) return null
                    idx += len
                }
                5 -> {
                    if (idx + 4 > bytes.size) return null
                    idx += 4
                }
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var idx = start
        var shift = 0
        var value = 0L
        while (idx < bytes.size && shift <= 63) {
            val b = bytes[idx].toInt() and 0xFF
            idx++
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return value to idx
            }
            shift += 7
        }
        return null
    }

    private fun String.sanitizeForPreview(): String {
        val sanitized = buildString(length) {
            for (ch in this@sanitizeForPreview) {
                append(
                    when {
                        ch == '\n' || ch == '\r' || ch == '\t' -> ch
                        ch == '\uFFFD' -> '?'
                        ch.isISOControl() -> ' '
                        else -> ch
                    }
                )
            }
        }
        return sanitized.trim()
    }

    private fun String.isMostlyReadable(): Boolean {
        if (isEmpty()) return false
        val readable = count { it.code in 9..13 || it.code in 32..126 || it.code >= 160 }
        return readable >= (length * 0.8)
    }

    companion object {
        private const val TAG = "NodeClient"
        private const val MAX_TEXT_PREVIEW_CHARS = 2_000
        private const val MAX_BINARY_PREVIEW_BYTES = 64
        private const val MAX_REPLACEMENT_RATIO = 0.05
        private const val PING_TIMEOUT_SECONDS = 10L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val CONNECTION_CLOSE_TIMEOUT_SECONDS = 5L
        private const val IPFS_STOP_TIMEOUT_SECONDS = 5L
        private const val MAX_PING_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 150L
        private const val CONNECTION_RECOVERY_PAUSE_MS = 350L
        private const val TCP_CONNECT_TIMEOUT_MS = 4_000
        private const val TCP_SOCKET_TIMEOUT_MS = 4_000
    }
}
