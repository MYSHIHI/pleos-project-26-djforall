package io.github.myshihi.pleosproject26.e10seatdjparty

import org.junit.Assert.*
import org.junit.Test

class PartyEngineTest {
    private val four = SeatId.primary
    private fun song(id: String) = Catalog.find(id)!!.song
    private fun fill(engine: PartyEngine) { engine.participants.forEach { assertTrue(engine.submit(it, "neon")) } }

    @Test fun nobodyIsAutomaticallyAPlayer() {
        val engine = PartyEngine()
        assertTrue(engine.participants.isEmpty())
        assertFalse(engine.submit(SeatId.FL, "neon"))
        assertFalse(engine.buildRound())
    }
    @Test fun onePickPerSeatPreventsDuplicateVotesAndInvalidSongs() {
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        assertTrue(engine.submit(SeatId.FR, "wave"))
        assertFalse(engine.submit(SeatId.FR, "moon"))
        assertFalse(engine.submit(SeatId.FL, "copyright-song"))
        assertFalse(engine.submit(SeatId.TL, "neon"))
        assertEquals(1, engine.picks.size)
        assertFalse(engine.buildRound())
    }
    @Test fun firstTurnRotatesAcrossRounds() {
        val engine = PartyEngine()
        engine.confirmParticipants(four)
        fill(engine); assertTrue(engine.buildRound())
        assertEquals(four, engine.queue.map { it.seat })
        fill(engine); assertTrue(engine.buildRound())
        assertEquals(listOf(SeatId.FR, SeatId.RL, SeatId.RR, SeatId.FL), engine.queue.takeLast(4).map { it.seat })
        assertEquals(8, engine.queue.size)
        assertFalse(engine.buildRound())
    }
    @Test fun occupancyChangesOnlyRequireHumanReview() {
        val engine = PartyEngine()
        engine.confirmParticipants(four); fill(engine); engine.buildRound()
        val before = engine.queue
        repeat(20) { engine.markForReview() }
        assertEquals(before, engine.queue)
        assertEquals(four, engine.participants)
        assertFalse(engine.submit(SeatId.FL, "moon"))
        engine.confirmParticipants(four)
        assertFalse(engine.needsReview)
    }
    @Test fun removingASeatKeepsTheCurrentTrackAndRemovesOnlyItsFutureEntries() {
        val engine = PartyEngine()
        engine.confirmParticipants(four); fill(engine); engine.buildRound()
        engine.confirmParticipants(listOf(SeatId.FR, SeatId.RR))
        assertEquals(SeatId.FL, engine.current!!.seat)
        assertEquals(listOf(SeatId.FL, SeatId.FR, SeatId.RR), engine.queue.map { it.seat })
    }
    @Test fun reviewAfterTheSongEndedDropsALeftSeatsUnstartedNextTrack() {
        val engine = PartyEngine()
        engine.confirmParticipants(four); fill(engine); engine.buildRound()
        engine.markForReview()
        assertTrue(engine.next()) // FL's song finished naturally while review was pending; FR's is next but unstarted.
        assertEquals(SeatId.FR, engine.current!!.seat)
        engine.confirmParticipants(listOf(SeatId.FL, SeatId.RL, SeatId.RR), keepCurrent = false)
        assertEquals(SeatId.RL, engine.current!!.seat)
        assertEquals(listOf(SeatId.FL, SeatId.RL, SeatId.RR), engine.queue.map { it.seat })
        assertFalse(engine.needsReview)
    }
    @Test fun changedRosterDoesNotResetNextRoundLeadToDriver() {
        val engine = PartyEngine()
        engine.confirmParticipants(four); fill(engine); engine.buildRound()
        engine.confirmParticipants(listOf(SeatId.FL, SeatId.RL, SeatId.RR))
        fill(engine); engine.buildRound()
        assertEquals(SeatId.RL, engine.queue.takeLast(3).first().seat)
    }
    @Test fun completedQueueStopsAndNewRoundIsStillFair() {
        val engine = PartyEngine()
        engine.confirmParticipants(four); fill(engine); engine.buildRound()
        repeat(4) { assertTrue(engine.next()) }
        assertNull(engine.current)
        assertFalse(engine.next())
        fill(engine); engine.buildRound()
        assertEquals(4, engine.queue.size)
        assertEquals(SeatId.FR, engine.current!!.seat)
    }
    @Test fun restartRestoresQueueOrderButRequiresReview() {
        val engine = PartyEngine()
        val queue = listOf(QueueEntry(1, SeatId.FR, song("wave")), QueueEntry(1, SeatId.FL, song("neon")))
        engine.restore(four, mapOf(SeatId.RR to song("moon")), queue, 1, 2, SeatId.FR)
        assertEquals(queue, engine.queue)
        assertEquals("neon", engine.current!!.song.key)
        assertTrue(engine.needsReview)
        assertEquals(SeatId.FR, engine.nextLead)
    }
    @Test fun corruptOrDuplicateRestoredEntriesAreSanitized() {
        val engine = PartyEngine()
        val missing = Song("missing", "가짜 곡", "")
        engine.restore(four, mapOf(SeatId.FL to missing), listOf(QueueEntry(1, SeatId.FL, song("neon")),
            QueueEntry(1, SeatId.FL, song("neon")), QueueEntry(1, SeatId.FR, missing)), 900, -1, null)
        assertEquals(1, engine.queue.size)
        assertNull(engine.current)
        assertTrue(engine.picks.isEmpty())
        assertEquals(2, engine.round)
    }
    @Test fun queueLimitRejectsAtomicAppendWithoutDroppingPicks() {
        val engine = PartyEngine()
        engine.confirmParticipants(SeatId.entries)
        repeat(4) { fill(engine); assertTrue(engine.buildRound()) }
        fill(engine)
        val before = engine.queue
        assertFalse(engine.buildRound())
        assertEquals(before, engine.queue)
        assertEquals(9, engine.picks.size)
    }
    @Test fun missingExpiredAndFutureSignalsNeverMeanEmpty() {
        assertNull(SeatSignal().value(100_000))
        assertNull(SeatSignal(false, true, receivedAt = 1).value(100_000))
        assertNull(SeatSignal(true, true, receivedAt = 100_001).value(100_000))
        assertEquals(false, SeatSignal(false, true, receivedAt = 100_000).value(100_000))
        assertEquals(true, SeatSignal(true, true, receivedAt = 100_000).value(100_000))
    }
    private fun baselineOf(signals: Map<SeatId, SeatSignal>, now: Long) = signals.mapValues { it.value.value(now) }

    @Test fun steadySampleStateDoesNotExpireIntoAReviewRequest() {
        val confirmed = 1_000_000L
        val baseline = baselineOf(SampleSignals.build(SampleKind.NORMAL, confirmed), confirmed)
        val later = confirmed + SeatSignal.FRESH_MS + 1
        // Without the periodic re-stamp the same sample state silently expires and looks like a change.
        assertTrue(signalsChanged(baseline, SampleSignals.build(SampleKind.NORMAL, confirmed), later))
        var stampedAt = confirmed
        var signals = SampleSignals.build(SampleKind.NORMAL, confirmed)
        for (now in confirmed..later step 2_000L) {
            if (now - stampedAt >= SampleSignals.REFRESH_MS) { stampedAt = now; signals = SampleSignals.build(SampleKind.NORMAL, now) }
            assertFalse("no change at +${now - confirmed} ms", signalsChanged(baseline, signals, now))
        }
    }
    @Test fun sampleScenariosStillRequestReviewWhenTheyShouldAndNeverClaimEmpty() {
        val now = 1_000_000L
        val baseline = baselineOf(SampleSignals.build(SampleKind.NORMAL, now), now)
        assertFalse(signalsChanged(baseline, SampleSignals.build(SampleKind.NORMAL, now), now))
        SampleKind.entries.filter { it != SampleKind.NORMAL }.forEach {
            assertTrue("$it should request review", signalsChanged(baseline, SampleSignals.build(it, now), now))
        }
        listOf(SampleKind.MISSING, SampleKind.STALE, SampleKind.UNSUPPORTED).forEach { kind ->
            assertTrue(SampleSignals.build(kind, now).values.all { it.value(now) == null })
        }
        assertFalse(SampleSignals.refreshes(SampleKind.STALE))
        assertTrue(SampleSignals.build(SampleKind.NORMAL, now).values.all { it.origin == Origin.SAMPLE })
    }
    @Test fun signalsOutsideTheBaselineAreIgnored() {
        val now = 1_000_000L
        val signals = SampleSignals.build(SampleKind.CHANGED, now)
        assertFalse(signalsChanged(emptyMap(), signals, now))
        assertFalse(signalsChanged(mapOf(SeatId.FL to true), signals, now))
    }
}
