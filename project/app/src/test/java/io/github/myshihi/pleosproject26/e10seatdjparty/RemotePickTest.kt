package io.github.myshihi.pleosproject26.e10seatdjparty

import org.junit.Assert.*
import org.junit.Test

class RemotePickTest {
    private val four = SeatId.primary
    private fun song(id: String) = Catalog.find(id)!!.song
    private val genie = Song.external("com.example.music/.Service", "track-42", "밤양갱", "비비")

    @Test fun phonePicksFollowTheSameOnePickPerSeatRules() {
        val engine = PartyEngine()
        assertEquals(PickResult.NOT_PARTICIPANT, engine.pick(SeatId.FR, genie))
        engine.confirmParticipants(four)
        assertEquals(PickResult.OK, engine.pick(SeatId.FR, genie))
        assertEquals(PickResult.ALREADY_PICKED, engine.pick(SeatId.FR, song("moon")))
        assertEquals(genie, engine.picks[SeatId.FR])
        engine.markForReview()
        assertEquals(PickResult.REVIEW_PENDING, engine.pick(SeatId.RL, song("moon")))
    }

    @Test fun whileDrivingOnlyTheDriverSeatIsRefused() {
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        assertEquals(PickResult.DRIVER_WHILE_DRIVING, engine.pick(SeatId.FL, song("neon"), driving = true))
        assertEquals(PickResult.DRIVER_WHILE_DRIVING, engine.pickBlock(SeatId.FL, driving = true))
        assertEquals(PickResult.OK, engine.pick(SeatId.RR, song("neon"), driving = true))
        assertNull(engine.pickBlock(SeatId.FL))
        assertEquals(PickResult.OK, engine.pick(SeatId.FL, song("neon")))
    }

    @Test fun whileDrivingTheRoundCompletesWithoutTheDriverAndKeepsRotating() {
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        listOf(SeatId.FR, SeatId.RL, SeatId.RR).forEach { assertEquals(PickResult.OK, engine.pick(it, song("wave"), driving = true)) }
        assertFalse(engine.ready)          // at a stop the driver still has a turn
        assertTrue(engine.ready(driving = true))
        assertTrue(engine.buildRound(driving = true))
        assertEquals(listOf(SeatId.FR, SeatId.RL, SeatId.RR), engine.queue.map { it.seat })
        assertEquals(SeatId.RL, engine.nextLead)
        assertEquals(four, engine.participants) // the driver only sits the round out, they are not removed
        // Back at a stop the driver picks again and the next round starts from the rotated lead.
        four.forEach { assertTrue(engine.submit(it, "moon")) }
        assertTrue(engine.buildRound())
        assertEquals(listOf(SeatId.RL, SeatId.RR, SeatId.FL, SeatId.FR), engine.queue.takeLast(4).map { it.seat })
    }

    @Test fun aDriverPickMadeAtTheStopStillCountsAfterDeparture() {
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        assertEquals(PickResult.OK, engine.pick(SeatId.FL, song("neon")))
        listOf(SeatId.FR, SeatId.RL).forEach { engine.pick(it, song("wave"), driving = true) }
        assertFalse(engine.ready(driving = true))
        engine.pick(SeatId.RR, song("moon"), driving = true)
        assertTrue(engine.buildRound(driving = true))
        assertEquals(four, engine.queue.map { it.seat })
    }

    @Test fun externalSongsNeedAppMediaIdAndMatchingKey() {
        assertNotNull(genie.normalized())
        assertNull(genie.copy(key = "other|track-42").normalized())
        assertNull(genie.copy(mediaId = "").normalized())
        assertNull(genie.copy(title = " ").normalized())
        assertNull(Song("copyright-song", "x", "").normalized())
        // Bundled songs are rebuilt from the catalog, so a tampered title is not kept.
        assertEquals("네온 드라이브", Song("neon", "바뀐 제목", "").normalized()!!.title)
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        assertEquals(PickResult.INVALID_SONG, engine.pick(SeatId.FL, genie.copy(mediaId = null)))
        assertTrue(engine.picks.isEmpty())
    }

    @Test fun songsRequestedByNameAreValidAndStable() {
        val melon = "com.iloen.melon"
        val req = Song.request(melon, "  밤양갱   -  비비 ")!!
        assertEquals("밤양갱", req.title)
        assertEquals("비비 · 이름으로 요청", req.subtitle)
        assertEquals("밤양갱 비비", req.query)
        assertTrue(req.requested)
        assertEquals(req, req.normalized())
        assertEquals(req.key, Song.request(melon, "밤양갱 - 비비")!!.key)
        assertEquals("이름으로 요청", Song.request(melon, "Hype Boy")!!.subtitle)
        assertNull(Song.request(melon, "   "))
        assertNull(Song.request(melon, " - 비비"))
        // A client cannot point a request key at different text, or mix a media id into a request.
        assertNull(req.copy(query = "다른 곡").normalized())
        assertNull(req.copy(mediaId = "x").normalized())
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        assertEquals(PickResult.OK, engine.pick(SeatId.RL, req))
    }

    @Test fun musicAppIdsFromTheFirstVersionStillResolve() {
        val apps = listOf(MediaApp("com.ktmusic.geniemusic", "com.ktmusic.Service", "지니뮤직", true))
        assertEquals("지니뮤직", MediaApp.parse("com.ktmusic.geniemusic/com.ktmusic.Service", apps)?.label)
        assertEquals("지니뮤직", MediaApp.parse("com.ktmusic.geniemusic", apps)?.label)
        assertNull(MediaApp.parse("com.iloen.melon", apps))
        assertNull(MediaApp.parse(null, apps))
    }

    @Test fun mixedQueueSurvivesRestore() {
        val engine = PartyEngine()
        engine.confirmParticipants(listOf(SeatId.FL, SeatId.FR))
        engine.pick(SeatId.FL, song("wave")); engine.pick(SeatId.FR, genie)
        assertTrue(engine.buildRound())
        val restored = PartyEngine()
        restored.restore(engine.participants, emptyMap(), engine.queue, 0, engine.round, engine.nextLead)
        assertEquals(engine.queue, restored.queue)
        assertEquals("com.example.music/.Service", restored.queue.last().song.app)
    }

    @Test fun seatTokensAreStablePerSeatAndRevokedWhenASeatLeaves() {
        var n = 0
        val tokens = SeatTokens { "token-%016d".format(n++) }
        tokens.issue(four)
        val fr = tokens.tokenFor(SeatId.FR)!!
        assertEquals(SeatId.FR, tokens.seatFor(fr))
        assertEquals(4, tokens.all.values.toSet().size)
        tokens.issue(listOf(SeatId.FL, SeatId.FR, SeatId.RL))
        assertEquals(fr, tokens.tokenFor(SeatId.FR))
        assertNull(tokens.tokenFor(SeatId.RR))
        assertNull(tokens.seatFor("unknown")); assertNull(tokens.seatFor("")); assertNull(tokens.seatFor(null))
        val restored = SeatTokens { "fresh-%016d".format(n++) }
        restored.restore(tokens.all + (SeatId.RR to "bad token!"), listOf(SeatId.FR, SeatId.RR))
        assertEquals(fr, restored.tokenFor(SeatId.FR))
        assertTrue(restored.tokenFor(SeatId.RR)!!.startsWith("fresh-"))
        assertNull(restored.tokenFor(SeatId.FL))
    }

    @Test fun httpRequestsAreParsedWithinLimits() {
        val get = "GET /api/search?t=abc&q=%EB%B0%A4%EC%96%91%EA%B0%B1+x HTTP/1.1\r\nHost: car\r\n\r\n"
        val req = Http.read(get.byteInputStream())!!
        assertEquals("/api/search", req.path)
        assertEquals("밤양갱 x", req.query["q"])
        assertEquals("abc", req.query["t"])
        val body = "key=com.example%7Ctrack-42"
        val post = "POST /api/pick?t=abc HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body"
        assertEquals("com.example|track-42", Http.read(post.byteInputStream())!!.form["key"])
        assertNull(Http.read("DELETE / HTTP/1.1\r\n\r\n".byteInputStream()))
        assertNull(Http.read("GET http://evil/ HTTP/1.1\r\n\r\n".byteInputStream()))
        assertNull(Http.read("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".byteInputStream()))
        assertNull(Http.read(("GET /" + "a".repeat(Http.MAX_HEADER)).byteInputStream()))
        assertEquals(emptyMap<String, String>(), Http.parseQuery("%zz=1"))
    }

    @Test fun localServerAnswersOverRealSockets() {
        val server = PartyServer(0) { r ->
            if (r.method == "POST") HttpResponse.json(200, mapOf("got" to r.form["key"], "t" to r.query["t"]))
            else HttpResponse.html(JoinPage.PAGE)
        }
        assertTrue(server.start())
        try {
            val base = "http://127.0.0.1:${server.boundPort}"
            val page = java.net.URL("$base/s/abc").openConnection() as java.net.HttpURLConnection
            assertEquals(200, page.responseCode)
            assertEquals("no-referrer", page.getHeaderField("Referrer-Policy"))
            assertTrue(page.inputStream.bufferedReader().readText().contains("모두의 DJ"))
            val post = java.net.URL("$base/api/pick?t=abc").openConnection() as java.net.HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            post.outputStream.use { it.write("key=neon".toByteArray()) }
            assertEquals("{\"got\":\"neon\",\"t\":\"abc\"}", post.inputStream.bufferedReader().readText())
        } finally { server.stop() }
        assertFalse(server.running)
    }

    @Test fun jsonEscapesTextSoSongTitlesCannotBreakThePage() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c/script\\u003e\"", Json.quote("a\"b\\c\n</script>"))
        assertEquals("{\"ok\":true,\"items\":[{\"k\":1}],\"n\":null}", Json.write(mapOf("ok" to true, "items" to listOf(mapOf("k" to 1)), "n" to null)))
    }

    // ---- QR encoder, checked against published reference values (thonky.com QR tutorial / ISO 18004 tables) ----

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun syndromesZero(codeword: ByteArray, eccLen: Int): Boolean {
        var root = 1
        repeat(eccLen) {
            var s = 0
            for (c in codeword) s = QrCode.multiply(s, root) xor (c.toInt() and 0xFF)
            if (s != 0) return false
            root = QrCode.multiply(root, 2)
        }
        return true
    }

    @Test fun reedSolomonMatchesHelloWorld1M() {
        val data = bytes(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        val ecc = QrCode.reedSolomonRemainder(data, QrCode.reedSolomonDivisor(10))
        assertArrayEquals(bytes(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ecc)
        assertTrue(syndromesZero(data + ecc, 10))
    }

    @Test fun formatVersionAndLayoutTablesMatchTheStandard() {
        val m = listOf("101010000010010", "101000100100101", "101111001111100", "101101101001011",
            "100010111111001", "100000011001110", "100111110010111", "100101010100000")
        m.forEachIndexed { mask, bits -> assertEquals("mask $mask", bits.toInt(2), QrCode.formatBits(mask)) }
        assertEquals("000111110010010100".toInt(2), QrCode.versionBits(7))
        assertEquals("001000010110111100".toInt(2), QrCode.versionBits(8))
        assertArrayEquals(intArrayOf(6, 22, 38), QrCode.alignmentPositions(7))
        assertArrayEquals(intArrayOf(6, 26, 46, 66), QrCode.alignmentPositions(14))
        assertEquals(listOf(16, 28, 44, 64, 86), (1..5).map { QrCode.dataCodewords(it) })
        assertEquals(216, QrCode.dataCodewords(10))
        assertEquals(415, QrCode.dataCodewords(15))
    }

    @Test fun encodedSymbolCarriesValidCodewordsForTypicalJoinUrls() {
        listOf("http://192.168.43.1:8765/s/AbCdEfGhIjKlMnOpQrStUv", "http://127.0.0.1:8765/", "x".repeat(200)).forEach { url ->
            val qr = QrCode.encode(url)
            assertEquals(qr.version * 4 + 17, qr.size)
            // Finder pattern corners and the always-dark module.
            assertTrue(qr[0, 0] && qr[6, 6] && !qr[7, 7] && qr[qr.size - 1, 0] && qr[0, qr.size - 1] && qr[8, qr.size - 8])
            val read = qr.readCodewords()
            assertEquals(QrCode.rawDataModules(qr.version) / 8, read.size)
            // Mode indicator (byte mode) and character count read back out of the placed, unmasked matrix.
            val data = url.toByteArray()
            assertEquals(0b0100, (read[0].toInt() and 0xF0) ushr 4)
            // With one ECC block (versions 1-3) the second placed codeword is still data, so the count is readable.
            if (qr.version <= 3) assertEquals(data.size, ((read[0].toInt() and 0x0F) shl 4) or ((read[1].toInt() and 0xF0) ushr 4))
        }
        val single = QrCode.encode("HELLO") // version 1: one block, so the placed stream is data + ECC directly
        assertEquals(1, single.version)
        assertTrue(syndromesZero(single.readCodewords(), 10))
        assertThrows(IllegalArgumentException::class.java) { QrCode.encode("x".repeat(500)) }
    }
}
