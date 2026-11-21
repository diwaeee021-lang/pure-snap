package com.example.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.BitSet
import kotlin.math.ceil

object PeerWireCodec {
    const val PROTOCOL_STRING = "BitTorrent protocol"
    const val BLOCK_SIZE = 16384 // 16 KB standard BEP-3 request block

    const val MSG_CHOKE = 0
    const val MSG_UNCHOKE = 1
    const val MSG_INTERESTED = 2
    const val MSG_NOT_INTERESTED = 3
    const val MSG_HAVE = 4
    const val MSG_BITFIELD = 5
    const val MSG_REQUEST = 6
    const val MSG_PIECE = 7
    const val MSG_CANCEL = 8
    const val MSG_EXTENDED = 20

    const val LOCAL_UT_METADATA_ID = 1

    fun buildHandshake(infoHash: ByteArray, peerId: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(68)
        buf.put(19.toByte())
        buf.put(PROTOCOL_STRING.toByteArray(StandardCharsets.US_ASCII))
        val reserved = ByteArray(8)
        reserved[5] = 0x10 // BEP-10 Extension Protocol bit
        buf.put(reserved)
        buf.put(infoHash)
        buf.put(peerId)
        return buf.array()
    }

    fun buildSimpleMessage(messageId: Int): ByteArray {
        return ByteBuffer.allocate(5)
            .putInt(1)
            .put(messageId.toByte())
            .array()
    }

    fun buildRequestMessage(pieceIndex: Int, offset: Int, length: Int): ByteArray {
        return ByteBuffer.allocate(17)
            .putInt(13)
            .put(MSG_REQUEST.toByte())
            .putInt(pieceIndex)
            .putInt(offset)
            .putInt(length)
            .array()
    }

    fun buildExtendedHandshake(): ByteArray {
        val mDict = BencodeValue.BDict(
            linkedMapOf("ut_metadata" to BencodeValue.BInt(LOCAL_UT_METADATA_ID.toLong()))
        )
        val root = BencodeValue.BDict(
            linkedMapOf(
                "m" to mDict,
                "v" to BencodeValue.BBytes("PureSnap 1.0".toByteArray(StandardCharsets.UTF_8))
            )
        )
        val payload = Bencode.encode(root)
        return ByteBuffer.allocate(6 + payload.size)
            .putInt(2 + payload.size)
            .put(MSG_EXTENDED.toByte())
            .put(0.toByte())
            .put(payload)
            .array()
    }

    fun buildUtMetadataRequest(peerUtMetadataId: Int, pieceIndex: Int): ByteArray {
        val dict = BencodeValue.BDict(
            linkedMapOf(
                "msg_type" to BencodeValue.BInt(0L),
                "piece" to BencodeValue.BInt(pieceIndex.toLong())
            )
        )
        val payload = Bencode.encode(dict)
        return ByteBuffer.allocate(6 + payload.size)
            .putInt(2 + payload.size)
            .put(MSG_EXTENDED.toByte())
            .put(peerUtMetadataId.toByte())
            .put(payload)
            .array()
    }
}

class PeerSession(
    val peer: PeerAddress,
    private val infoHash: ByteArray,
    private val peerId: ByteArray
) : AutoCloseable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var peerChoking: Boolean = true
        private set
    val peerBitfield = BitSet()
    var peerUtMetadataId: Int? = null
        private set
    var metadataSize: Int = -1
        private set

    fun connectAndHandshake(timeoutMs: Int = 5500): Boolean {
        return runCatching {
            val s = Socket()
            s.connect(InetSocketAddress(peer.ip, peer.port), timeoutMs)
            s.soTimeout = 9000
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())

            val handshake = PeerWireCodec.buildHandshake(infoHash, peerId)
            output!!.write(handshake)
            output!!.flush()

            val resp = ByteArray(68)
            input!!.readFully(resp)
            val protoLen = resp[0].toInt() and 0xFF
            if (protoLen != 19) return false
            val proto = String(resp, 1, 19, StandardCharsets.US_ASCII)
            if (proto != PeerWireCodec.PROTOCOL_STRING) return false
            val remoteHash = resp.copyOfRange(28, 48)
            if (!remoteHash.contentEquals(infoHash)) return false

            val supportsExtended = (resp[25].toInt() and 0x10) != 0
            if (supportsExtended) {
                output!!.write(PeerWireCodec.buildExtendedHandshake())
                output!!.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun sendInterested() {
        output?.let {
            it.write(PeerWireCodec.buildSimpleMessage(PeerWireCodec.MSG_INTERESTED))
            it.flush()
        }
    }

    fun fetchUtMetadata(onProgress: ((Int, Int) -> Unit)? = null): TorrentMetadata? {
        val deadline = System.currentTimeMillis() + 18_000L
        var piecesTotal = 0
        var requested = false
        val pieceDataMap = HashMap<Int, ByteArray>()

        while (System.currentTimeMillis() < deadline) {
            val msg = readNextMessage() ?: break
            when (msg) {
                is PeerMessage.Extended -> {
                    if (msg.extId == 0) {
                        val dict = runCatching { Bencode.decode(msg.payload) as? BencodeValue.BDict }.getOrNull()
                        val mDict = dict?.getDict("m")
                        val utMetaId = mDict?.getLong("ut_metadata")?.toInt()
                        val mSize = dict?.getLong("metadata_size")?.toInt() ?: -1
                        if (utMetaId != null && utMetaId > 0 && mSize > 0) {
                            peerUtMetadataId = utMetaId
                            metadataSize = mSize
                            piecesTotal = ceil(mSize / 16384.0).toInt()
                            if (!requested) {
                                requested = true
                                for (p in 0 until piecesTotal) {
                                    output?.write(PeerWireCodec.buildUtMetadataRequest(utMetaId, p))
                                }
                                output?.flush()
                            }
                        }
                    } else if (msg.extId == PeerWireCodec.LOCAL_UT_METADATA_ID) {
                        val splitIndex = findEndOfBencodeDict(msg.payload)
                        if (splitIndex > 0 && splitIndex <= msg.payload.size) {
                            val headerBytes = msg.payload.copyOfRange(0, splitIndex)
                            val metaSlice = msg.payload.copyOfRange(splitIndex, msg.payload.size)
                            val headerDict = runCatching {
                                Bencode.decode(headerBytes) as? BencodeValue.BDict
                            }.getOrNull()
                            val msgType = headerDict?.getLong("msg_type")?.toInt()
                            val pieceIdx = headerDict?.getLong("piece")?.toInt()
                            if (msgType == 1 && pieceIdx != null) {
                                pieceDataMap[pieceIdx] = metaSlice
                                onProgress?.invoke(pieceDataMap.size, piecesTotal)
                                if (piecesTotal > 0 && pieceDataMap.size >= piecesTotal) {
                                    val fullOut = ByteArrayOutputStream(metadataSize)
                                    for (i in 0 until piecesTotal) {
                                        val chunk = pieceDataMap[i] ?: return null
                                        fullOut.write(chunk)
                                    }
                                    val rawInfo = fullOut.toByteArray()
                                    if (!TorrentParser.sha1(rawInfo).contentEquals(infoHash)) {
                                        return null
                                    }
                                    val infoDict = Bencode.decode(rawInfo) as? BencodeValue.BDict ?: return null
                                    return TorrentParser.parseInfoDictionary(
                                        infoDict = infoDict,
                                        rawInfoBytes = rawInfo,
                                        infoHashBytes = infoHash,
                                        infoHashHex = TorrentParser.bytesToHex(infoHash),
                                        trackers = TorrentParser.DEFAULT_PUBLIC_TRACKERS,
                                        webSeeds = emptyList()
                                    )
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    fun downloadPiece(pieceIndex: Int, pieceLength: Int, isCancelled: () -> Boolean): ByteArray? {
        if (peerChoking) {
            sendInterested()
            val waitDeadline = System.currentTimeMillis() + 6000L
            while (peerChoking && System.currentTimeMillis() < waitDeadline && !isCancelled()) {
                when (val msg = readNextMessage() ?: return null) {
                    is PeerMessage.Unchoke -> peerChoking = false
                    is PeerMessage.Choke -> peerChoking = true
                    is PeerMessage.Bitfield -> applyBitfield(msg.bytes)
                    is PeerMessage.Have -> peerBitfield.set(msg.pieceIndex)
                    else -> Unit
                }
            }
            if (peerChoking) return null
        }

        if (!peerBitfield.isEmpty && !peerBitfield.get(pieceIndex)) {
            return null
        }

        val pieceBuffer = ByteArray(pieceLength)
        val blockOffsets = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        while (offset < pieceLength) {
            val len = minOf(PeerWireCodec.BLOCK_SIZE, pieceLength - offset)
            blockOffsets.add(offset to len)
            offset += len
        }

        // Pipeline up to 5 block requests at a time
        val receivedOffsets = HashSet<Int>()
        var nextToRequest = 0
        val pipelineSize = 5

        fun fillPipeline() {
            while (nextToRequest < blockOffsets.size &&
                (nextToRequest - receivedOffsets.size) < pipelineSize
            ) {
                val (off, len) = blockOffsets[nextToRequest]
                output?.write(PeerWireCodec.buildRequestMessage(pieceIndex, off, len))
                nextToRequest++
            }
            output?.flush()
        }

        fillPipeline()
        val pieceDeadline = System.currentTimeMillis() + 25_000L
        while (receivedOffsets.size < blockOffsets.size &&
            System.currentTimeMillis() < pieceDeadline &&
            !isCancelled()
        ) {
            when (val msg = readNextMessage() ?: return null) {
                is PeerMessage.Choke -> {
                    peerChoking = true
                    return null
                }
                is PeerMessage.Unchoke -> peerChoking = false
                is PeerMessage.Bitfield -> applyBitfield(msg.bytes)
                is PeerMessage.Have -> peerBitfield.set(msg.pieceIndex)
                is PeerMessage.Piece -> {
                    if (msg.pieceIndex == pieceIndex &&
                        msg.begin >= 0 &&
                        msg.begin + msg.block.size <= pieceLength
                    ) {
                        System.arraycopy(msg.block, 0, pieceBuffer, msg.begin, msg.block.size)
                        receivedOffsets.add(msg.begin)
                        fillPipeline()
                    }
                }
                else -> Unit
            }
        }

        return if (receivedOffsets.size == blockOffsets.size) pieceBuffer else null
    }

    fun readKeepAliveOrHandshakeState(timeoutMs: Int = 1500) {
        val s = socket ?: return
        val oldTimeout = s.soTimeout
        runCatching {
            s.soTimeout = timeoutMs
            while (true) {
                val msg = readNextMessage() ?: break
                when (msg) {
                    is PeerMessage.Unchoke -> peerChoking = false
                    is PeerMessage.Choke -> peerChoking = true
                    is PeerMessage.Bitfield -> applyBitfield(msg.bytes)
                    is PeerMessage.Have -> peerBitfield.set(msg.pieceIndex)
                    else -> Unit
                }
            }
        }
        runCatching { s.soTimeout = oldTimeout }
    }

    private fun applyBitfield(bytes: ByteArray) {
        for (byteIdx in bytes.indices) {
            val b = bytes[byteIdx].toInt() and 0xFF
            for (bit in 0 until 8) {
                if ((b and (1 shl (7 - bit))) != 0) {
                    peerBitfield.set(byteIdx * 8 + bit)
                }
            }
        }
    }

    private fun readNextMessage(): PeerMessage? {
        val inp = input ?: return null
        return try {
            val len = inp.readInt()
            if (len == 0) return PeerMessage.KeepAlive
            if (len < 0 || len > 4 * 1024 * 1024) return null
            val msgId = inp.readUnsignedByte()
            val payloadLen = len - 1
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) inp.readFully(payload)

            when (msgId) {
                PeerWireCodec.MSG_CHOKE -> PeerMessage.Choke
                PeerWireCodec.MSG_UNCHOKE -> PeerMessage.Unchoke
                PeerWireCodec.MSG_INTERESTED -> PeerMessage.Interested
                PeerWireCodec.MSG_NOT_INTERESTED -> PeerMessage.NotInterested
                PeerWireCodec.MSG_HAVE -> {
                    val idx = ByteBuffer.wrap(payload).int
                    PeerMessage.Have(idx)
                }
                PeerWireCodec.MSG_BITFIELD -> PeerMessage.Bitfield(payload)
                PeerWireCodec.MSG_PIECE -> {
                    val buf = ByteBuffer.wrap(payload)
                    val pieceIdx = buf.int
                    val begin = buf.int
                    val block = ByteArray(payload.size - 8)
                    buf.get(block)
                    PeerMessage.Piece(pieceIdx, begin, block)
                }
                PeerWireCodec.MSG_EXTENDED -> {
                    if (payload.isEmpty()) PeerMessage.KeepAlive
                    else PeerMessage.Extended(
                        extId = payload[0].toInt() and 0xFF,
                        payload = payload.copyOfRange(1, payload.size)
                    )
                }
                else -> PeerMessage.Other(msgId)
            }
        } catch (_: EOFException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findEndOfBencodeDict(bytes: ByteArray): Int {
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return -1
        var pos = 0
        var depth = 0
        while (pos < bytes.size) {
            when (bytes[pos].toInt().toChar()) {
                'd', 'l' -> {
                    depth++
                    pos++
                }
                'i' -> {
                    pos++
                    while (pos < bytes.size && bytes[pos] != 'e'.code.toByte()) pos++
                    pos++
                }
                'e' -> {
                    depth--
                    pos++
                    if (depth == 0) return pos
                }
                in '0'..'9' -> {
                    var colon = pos
                    while (colon < bytes.size && bytes[colon] != ':'.code.toByte()) colon++
                    if (colon >= bytes.size) return -1
                    val len = String(bytes, pos, colon - pos, StandardCharsets.US_ASCII).toIntOrNull() ?: return -1
                    pos = colon + 1 + len
                }
                else -> return -1
            }
        }
        return -1
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
    }
}

sealed interface PeerMessage {
    data object KeepAlive : PeerMessage
    data object Choke : PeerMessage
    data object Unchoke : PeerMessage
    data object Interested : PeerMessage
    data object NotInterested : PeerMessage
    data class Have(val pieceIndex: Int) : PeerMessage
    data class Bitfield(val bytes: ByteArray) : PeerMessage
    data class Piece(val pieceIndex: Int, val begin: Int, val block: ByteArray) : PeerMessage
    data class Extended(val extId: Int, val payload: ByteArray) : PeerMessage
    data class Other(val id: Int) : PeerMessage
}

suspend fun resolveMagnetMetadataFromSwarm(
    magnet: MagnetLink,
    trackerClient: TrackerClient
): TorrentMetadata? = withContext(Dispatchers.IO) {
    val peerId = TrackerClient.generatePeerId()
    val announce = trackerClient.announceAll(
        trackers = magnet.trackers,
        infoHashBytes = magnet.infoHashBytes,
        peerId = peerId,
        left = if (magnet.exactLength > 0) magnet.exactLength else 1_000_000L
    )

    for (peer in announce.peers.take(15)) {
        val session = PeerSession(peer, magnet.infoHashBytes, peerId)
        try {
            if (session.connectAndHandshake(timeoutMs = 4500)) {
                val meta = session.fetchUtMetadata()
                if (meta != null) {
                    val combinedTrackers = (magnet.trackers + meta.trackers).distinct()
                    val combinedWebSeeds = (magnet.webSeeds + meta.webSeeds).distinct()
                    return@withContext meta.copy(
                        trackers = combinedTrackers,
                        webSeeds = combinedWebSeeds
                    )
                }
            }
        } finally {
            session.close()
        }
    }
    null
}
