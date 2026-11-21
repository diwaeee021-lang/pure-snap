package com.example.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class PeerAddress(
    val ip: String,
    val port: Int
)

data class TrackerAnnounceResult(
    val peers: List<PeerAddress>,
    val seeders: Int,
    val leechers: Int,
    val intervalSeconds: Int
)

class TrackerClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun announceAll(
        trackers: List<String>,
        infoHashBytes: ByteArray,
        peerId: ByteArray,
        uploaded: Long = 0L,
        downloaded: Long = 0L,
        left: Long = 1_000_000L,
        listenPort: Int = 6881
    ): TrackerAnnounceResult = coroutineScope {
        val uniqueTrackers = trackers.distinct().take(10)
        val results = uniqueTrackers.map { trackerUrl ->
            async(Dispatchers.IO) {
                runCatching {
                    when {
                        trackerUrl.startsWith("udp://", ignoreCase = true) -> {
                            announceUdp(
                                trackerUrl = trackerUrl,
                                infoHash = infoHashBytes,
                                peerId = peerId,
                                uploaded = uploaded,
                                downloaded = downloaded,
                                left = left,
                                port = listenPort
                            )
                        }
                        trackerUrl.startsWith("http://", ignoreCase = true) ||
                            trackerUrl.startsWith("https://", ignoreCase = true) -> {
                            announceHttp(
                                trackerUrl = trackerUrl,
                                infoHash = infoHashBytes,
                                peerId = peerId,
                                uploaded = uploaded,
                                downloaded = downloaded,
                                left = left,
                                port = listenPort
                            )
                        }
                        else -> null
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()

        val mergedPeers = linkedSetOf<PeerAddress>()
        var maxSeeders = 0
        var maxLeechers = 0
        var minInterval = 1800

        for (res in results) {
            mergedPeers.addAll(res.peers)
            if (res.seeders > maxSeeders) maxSeeders = res.seeders
            if (res.leechers > maxLeechers) maxLeechers = res.leechers
            if (res.intervalSeconds in 30 until minInterval) minInterval = res.intervalSeconds
        }

        TrackerAnnounceResult(
            peers = mergedPeers.toList(),
            seeders = maxSeeders,
            leechers = maxLeechers,
            intervalSeconds = minInterval
        )
    }

    private fun announceHttp(
        trackerUrl: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        port: Int
    ): TrackerAnnounceResult {
        val separator = if (trackerUrl.contains('?')) "&" else "?"
        val url = buildString {
            append(trackerUrl)
            append(separator)
            append("info_hash=").append(percentEncodeBytes(infoHash))
            append("&peer_id=").append(percentEncodeBytes(peerId))
            append("&port=").append(port)
            append("&uploaded=").append(uploaded)
            append("&downloaded=").append(downloaded)
            append("&left=").append(left.coerceAtLeast(0L))
            append("&compact=1")
            append("&numwant=50")
            append("&event=started")
        }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PureSnap/1.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Tracker HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty tracker body")
            val dict = Bencode.decode(bytes) as? BencodeValue.BDict
                ?: throw IllegalArgumentException("Invalid tracker response")

            dict.getString("failure reason")?.let { reason ->
                throw IllegalStateException("Tracker failure: $reason")
            }

            val interval = dict.getLong("interval")?.toInt() ?: 1800
            val seeders = dict.getLong("complete")?.toInt() ?: 0
            val leechers = dict.getLong("incomplete")?.toInt() ?: 0

            val peers = mutableListOf<PeerAddress>()
            when (val peersField = dict["peers"]) {
                is BencodeValue.BBytes -> peers.addAll(parseCompactPeers(peersField.bytes))
                is BencodeValue.BList -> {
                    for (entry in peersField.items) {
                        val pDict = entry as? BencodeValue.BDict ?: continue
                        val ip = pDict.getString("ip") ?: continue
                        val pPort = pDict.getLong("port")?.toInt() ?: continue
                        if (pPort in 1..65535) peers.add(PeerAddress(ip, pPort))
                    }
                }
                else -> Unit
            }

            return TrackerAnnounceResult(
                peers = peers,
                seeders = seeders,
                leechers = leechers,
                intervalSeconds = interval
            )
        }
    }

    private fun announceUdp(
        trackerUrl: String,
        infoHash: ByteArray,
        peerId: ByteArray,
        uploaded: Long,
        downloaded: Long,
        left: Long,
        port: Int
    ): TrackerAnnounceResult {
        val uri = URI(trackerUrl)
        val host = uri.host ?: throw IllegalArgumentException("Invalid UDP tracker host")
        val trackerPort = if (uri.port > 0) uri.port else 80
        val address = InetAddress.getByName(host)

        DatagramSocket().use { socket ->
            socket.soTimeout = 4500

            // Step 1: BEP-15 Connect Request (16 bytes)
            val txId1 = Random.nextInt()
            val connectBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(0x41727101980L) // magic constant
                putInt(0) // action = connect
                putInt(txId1)
            }.array()

            socket.send(DatagramPacket(connectBuf, connectBuf.size, address, trackerPort))

            val connectRespBytes = ByteArray(16)
            val connectRespPacket = DatagramPacket(connectRespBytes, connectRespBytes.size)
            socket.receive(connectRespPacket)

            val resp1 = ByteBuffer.wrap(connectRespBytes).order(ByteOrder.BIG_ENDIAN)
            val action1 = resp1.int
            val respTx1 = resp1.int
            if (action1 != 0 || respTx1 != txId1) {
                throw IllegalStateException("UDP tracker connect mismatch")
            }
            val connectionId = resp1.long

            // Step 2: BEP-15 Announce Request (98 bytes)
            val txId2 = Random.nextInt()
            val announceBuf = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1) // action = announce
                putInt(txId2)
                put(infoHash)
                put(peerId)
                putLong(downloaded)
                putLong(left.coerceAtLeast(0L))
                putLong(uploaded)
                putInt(2) // event = started
                putInt(0) // ip = default
                putInt(Random.nextInt()) // key
                putInt(50) // num_want
                putShort(port.toShort())
            }.array()

            socket.send(DatagramPacket(announceBuf, announceBuf.size, address, trackerPort))

            val respBytes = ByteArray(2048)
            val respPacket = DatagramPacket(respBytes, respBytes.size)
            socket.receive(respPacket)
            if (respPacket.length < 20) throw IllegalStateException("Truncated UDP announce response")

            val resp2 = ByteBuffer.wrap(respBytes, 0, respPacket.length).order(ByteOrder.BIG_ENDIAN)
            val action2 = resp2.int
            val respTx2 = resp2.int
            if (action2 != 1 || respTx2 != txId2) {
                throw IllegalStateException("UDP tracker announce mismatch")
            }

            val interval = resp2.int
            val leechers = resp2.int
            val seeders = resp2.int

            val compactLen = respPacket.length - 20
            val compactBytes = ByteArray(compactLen)
            resp2.get(compactBytes)

            return TrackerAnnounceResult(
                peers = parseCompactPeers(compactBytes),
                seeders = seeders,
                leechers = leechers,
                intervalSeconds = interval
            )
        }
    }

    companion object {
        fun parseCompactPeers(bytes: ByteArray): List<PeerAddress> {
            val peers = mutableListOf<PeerAddress>()
            var offset = 0
            while (offset + 6 <= bytes.size) {
                val b0 = bytes[offset].toInt() and 0xFF
                val b1 = bytes[offset + 1].toInt() and 0xFF
                val b2 = bytes[offset + 2].toInt() and 0xFF
                val b3 = bytes[offset + 3].toInt() and 0xFF
                val port = ((bytes[offset + 4].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 5].toInt() and 0xFF)
                if (port in 1..65535 && !(b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0)) {
                    peers.add(PeerAddress("$b0.$b1.$b2.$b3", port))
                }
                offset += 6
            }
            return peers
        }

        fun percentEncodeBytes(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 3)
            for (b in bytes) {
                val u = b.toInt() and 0xFF
                val ch = u.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '.' || ch == '-' || ch == '_' || ch == '~'
                ) {
                    sb.append(ch)
                } else {
                    sb.append(String.format(Locale.US, "%%%02X", u))
                }
            }
            return sb.toString()
        }

        fun generatePeerId(): ByteArray {
            val prefix = "-PS0100-".toByteArray(Charsets.US_ASCII)
            val randomSuffix = ByteArray(12) {
                ('0'..'9').random().code.toByte()
            }
            return prefix + randomSuffix
        }
    }
}
