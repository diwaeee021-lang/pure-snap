package com.example

import com.example.torrent.Bencode
import com.example.torrent.BencodeValue
import com.example.torrent.PeerWireCodec
import com.example.torrent.TorrentParser
import com.example.torrent.TorrentStorageWriter
import com.example.torrent.TrackerClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.BitSet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TorrentEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesMagnetUriHexAndBase32WithTrackersAndWebSeeds() {
        val hexMagnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" +
            "&dn=Open+Documentary+1080p" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce" +
            "&ws=https%3A%2F%2Farchive.org%2Fdownload%2Fdoc%2Fvideo.mp4"

        val parsed = TorrentParser.parseMagnetUri(hexMagnet)
        assertEquals("0123456789abcdef0123456789abcdef01234567", parsed.infoHashHex)
        assertEquals("Open Documentary 1080p", parsed.displayName)
        assertEquals(listOf("udp://tracker.opentrackr.org:1337/announce"), parsed.trackers)
        assertEquals(listOf("https://archive.org/download/doc/video.mp4"), parsed.webSeeds)

        // Base32 32-char info-hash ("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" -> 20 zero bytes)
        val base32Magnet = "magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val parsed32 = TorrentParser.parseMagnetUri(base32Magnet)
        assertEquals("0000000000000000000000000000000000000000", parsed32.infoHashHex)
    }

    @Test
    fun parsesMultiFileTorrentAndWritesPiecesAcrossFileBoundaries() {
        val file1Bytes = "HelloWorld".toByteArray(StandardCharsets.US_ASCII) // 10 bytes
        val file2Bytes = "PureSnapTorrent!".toByteArray(StandardCharsets.US_ASCII) // 16 bytes
        val fullPayload = file1Bytes + file2Bytes // 26 bytes total
        val pieceLength = 16L
        val piece0 = fullPayload.copyOfRange(0, 16)
        val piece1 = fullPayload.copyOfRange(16, 26)
        val piecesHashes = TorrentParser.sha1(piece0) + TorrentParser.sha1(piece1)

        val infoDict = BencodeValue.BDict(
            linkedMapOf(
                "name" to BencodeValue.BBytes("SamplePack".toByteArray(StandardCharsets.UTF_8)),
                "piece length" to BencodeValue.BInt(pieceLength),
                "pieces" to BencodeValue.BBytes(piecesHashes),
                "files" to BencodeValue.BList(
                    listOf(
                        BencodeValue.BDict(
                            linkedMapOf(
                                "length" to BencodeValue.BInt(file1Bytes.size.toLong()),
                                "path" to BencodeValue.BList(listOf(BencodeValue.BBytes("part1.txt".toByteArray())))
                            )
                        ),
                        BencodeValue.BDict(
                            linkedMapOf(
                                "length" to BencodeValue.BInt(file2Bytes.size.toLong()),
                                "path" to BencodeValue.BList(listOf(BencodeValue.BBytes("sub/clip.mp4".toByteArray())))
                            )
                        )
                    )
                )
            )
        )
        val rootDict = BencodeValue.BDict(
            linkedMapOf(
                "announce" to BencodeValue.BBytes("https://tracker.example.org/announce".toByteArray()),
                "info" to infoDict
            )
        )
        val torrentBytes = Bencode.encode(rootDict)
        val metadata = TorrentParser.parseTorrentBytes(torrentBytes)

        assertEquals("SamplePack", metadata.name)
        assertEquals(26L, metadata.totalLength)
        assertEquals(2, metadata.totalPieces)
        assertEquals(2, metadata.files.size)
        assertTrue(metadata.files[1].isMediaFile)

        // Write pieces 0 and 1 (piece 0 spans file 0 and first 6 bytes of file 1)
        val saveDir = tempFolder.newFolder("torrent_out")
        val allSelected = setOf(0, 1)
        TorrentStorageWriter.writePieceToFiles(saveDir, metadata, 0, piece0, allSelected)
        TorrentStorageWriter.writePieceToFiles(saveDir, metadata, 1, piece1, allSelected)

        assertArrayEquals(file1Bytes, File(saveDir, "part1.txt").readBytes())
        assertArrayEquals(file2Bytes, File(saveDir, "sub/clip.mp4").readBytes())
    }

    @Test
    fun encodesAndDecodesPieceBitfieldCheckpoints() {
        val bitSet = BitSet()
        bitSet.set(0)
        bitSet.set(5)
        bitSet.set(17)
        val encoded = TorrentStorageWriter.encodeBitSet(bitSet, 20)
        val decoded = TorrentStorageWriter.decodeBitSet(encoded)
        assertTrue(decoded.get(0))
        assertTrue(decoded.get(5))
        assertTrue(decoded.get(17))
        assertEquals(3, decoded.cardinality())
    }

    @Test
    fun parsesCompactPeersAndBuildsValidHandshake() {
        val compact = byteArrayOf(
            192.toByte(), 168.toByte(), 1, 42, 0x1A, 0xE1.toByte() // 192.168.1.42:6881
        )
        val peers = TrackerClient.parseCompactPeers(compact)
        assertEquals(1, peers.size)
        assertEquals("192.168.1.42", peers[0].ip)
        assertEquals(6881, peers[0].port)

        val infoHash = ByteArray(20) { it.toByte() }
        val peerId = TrackerClient.generatePeerId()
        val handshake = PeerWireCodec.buildHandshake(infoHash, peerId)
        assertEquals(68, handshake.size)
        assertEquals(19, handshake[0].toInt())
        assertEquals(0x10, handshake[25].toInt() and 0x10)
    }
}
