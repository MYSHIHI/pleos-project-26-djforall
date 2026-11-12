package io.github.myshihi.pleosproject26.e10seatdjparty

enum class SeatId(val label: String, val sdkArea: String) {
    FL("운전석", "ROW_1_LEFT"), FC("앞 중앙", "ROW_1_CENTER"), FR("동승석", "ROW_1_RIGHT"),
    RL("뒷좌석 왼쪽", "ROW_2_LEFT"), RC("뒷좌석 중앙", "ROW_2_CENTER"), RR("뒷좌석 오른쪽", "ROW_2_RIGHT"),
    TL("3열 왼쪽", "ROW_3_LEFT"), TC("3열 중앙", "ROW_3_CENTER"), TR("3열 오른쪽", "ROW_3_RIGHT");
    companion object { val primary = listOf(FL, FR, RL, RR) }
}

enum class Origin { VEHICLE, SAMPLE }
data class SeatSignal(
    val occupied: Boolean? = null, val supported: Boolean? = null,
    val origin: Origin = Origin.VEHICLE, val receivedAt: Long = 0,
    val detail: String = "연결 대기",
) {
    fun value(now: Long): Boolean? = occupied.takeIf { receivedAt > 0 && now >= receivedAt && now - receivedAt <= FRESH_MS }
    companion object { const val FRESH_MS = 60_000L }
}

/** True when any seat's usable reference value differs from the one captured at human confirmation. */
fun signalsChanged(baseline: Map<SeatId, Boolean?>, signals: Map<SeatId, SeatSignal>, now: Long) =
    signals.any { (id, value) -> baseline.containsKey(id) && baseline[id] != value.value(now) }

enum class SampleKind { NORMAL, CHANGED, MISSING, STALE, UNSUPPORTED }

/** Explicit sample scenarios. Never written to the vehicle. */
object SampleSignals {
    /** Mirrors the real source's periodic read so a steady sample state does not expire by itself. */
    const val REFRESH_MS = 30_000L

    fun refreshes(kind: SampleKind) = kind != SampleKind.STALE

    fun build(kind: SampleKind, now: Long): Map<SeatId, SeatSignal> = SeatId.entries.associateWith { id ->
        when {
            id !in SeatId.primary -> SeatSignal(supported = false, origin = Origin.SAMPLE, detail = "샘플 미지원 영역 · 수동 확인")
            kind == SampleKind.MISSING -> SeatSignal(supported = true, origin = Origin.SAMPLE, detail = "샘플 · 신호 없음")
            kind == SampleKind.UNSUPPORTED -> SeatSignal(supported = false, origin = Origin.SAMPLE, detail = "샘플 · 점유 미지원, 수동 확인")
            else -> SeatSignal(id != SeatId.RR || kind == SampleKind.CHANGED, true, Origin.SAMPLE,
                if (kind == SampleKind.STALE) now - SeatSignal.FRESH_MS - 1 else now, "샘플 · 차량에 쓰지 않음")
        }
    }
}

data class Track(val id: String, val title: String, val genre: String, val bpm: Int, val color: String) {
    val durationMs get() = ((32 * 60.0 / bpm + 0.8) * 1000).toInt()
    val song get() = Song(id, title, "$genre  ·  $bpm BPM  ·  오리지널 미니트랙", color = color)
}
object Catalog {
    val tracks = listOf(
        Track("neon", "네온 드라이브", "신스 팝", 96, "#B6A1F5"),
        Track("cloud", "구름 위 산책", "로파이", 80, "#A4D8EB"),
        Track("picnic", "별빛 피크닉", "플럭 팝", 108, "#F4B7CC"),
        Track("wave", "파도와 우리", "칠 아웃", 88, "#86D1BD"),
        Track("sunset", "노을의 리듬", "펑크", 104, "#F4BA82"),
        Track("moon", "달빛 캠프", "앰비언트", 72, "#CEDB9B"),
    )
    fun find(id: String?) = tracks.find { it.id == id }
    /** Bundled search: blank query lists everything. */
    fun search(query: String): List<Song> {
        val q = query.trim()
        return tracks.filter { q.isEmpty() || it.title.contains(q, true) || it.genre.contains(q, true) }.map { it.song }
    }
}

/**
 * A pickable song. [app] == null means a bundled track whose [key] is the catalog id. Otherwise the song lives in
 * an installed car media app (e.g. a streaming app signed in with the vehicle account) and is played by [mediaId].
 */
data class Song(
    val key: String, val title: String, val subtitle: String,
    val app: String? = null, val mediaId: String? = null, val color: String = "#B6A1F5",
    /** Set for a song requested by name ("제목 - 가수") when the music app offers no browsable search. */
    val query: String? = null,
) {
    val bundled get() = app == null
    val track get() = if (bundled) Catalog.find(key) else null
    val requested get() = !bundled && mediaId.isNullOrBlank()

    /**
     * Bundled songs are normalized to the catalog. External songs need an app, a title and either a media id from
     * the app's own search or a name query; the key must match so a client cannot smuggle in a different item.
     */
    fun normalized(): Song? = if (bundled) track?.song else takeIf {
        !app.isNullOrBlank() && title.isNotBlank() && title.length <= MAX_TEXT && subtitle.length <= MAX_TEXT && when {
            !mediaId.isNullOrBlank() -> query == null && mediaId.length <= MAX_ID && key == keyOf(app, mediaId)
            else -> !query.isNullOrBlank() && query.length <= MAX_QUERY && key == keyOf(app, "q:" + query.lowercase())
        }
    }

    companion object {
        const val MAX_TEXT = 200
        const val MAX_ID = 512
        const val MAX_QUERY = 100
        fun keyOf(app: String, mediaId: String) = "$app|$mediaId"
        fun external(app: String, mediaId: String, title: String, subtitle: String) =
            Song(keyOf(app, mediaId), title.take(MAX_TEXT), subtitle.take(MAX_TEXT), app, mediaId)

        /** "밤양갱 - 비비" → title 밤양갱, artist 비비. Returns null for blank input. */
        fun request(app: String, raw: String): Song? {
            val text = raw.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY)
            if (text.isEmpty()) return null
            // A dash with a space on at least one side separates title and artist; "Hype-Boy" stays one title.
            val parts = text.split(Regex("\\s*-\\s+|\\s+-\\s*"), limit = 2)
            val title = parts[0].trim().ifEmpty { return null }
            val artist = parts.getOrNull(1)?.trim().orEmpty()
            val query = listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" ")
            return Song(keyOf(app, "q:" + query.lowercase()), title, if (artist.isEmpty()) "이름으로 요청" else "$artist · 이름으로 요청", app, null, query = query)
        }
    }
}

data class QueueEntry(val round: Int, val seat: SeatId, val song: Song) {
    val key get() = "$round:${seat.name}"
}

enum class PickResult(val message: String) {
    OK("선곡을 확정했어요."),
    NOT_PARTICIPANT("참여 좌석으로 확정되지 않았어요. 차량 화면에서 참여 좌석을 확인해 주세요."),
    REVIEW_PENDING("차량 화면에서 참여 좌석을 다시 확인하는 중이에요. 잠시 후 다시 시도해 주세요."),
    ALREADY_PICKED("이번 라운드 선곡을 이미 확정했어요. 다음 라운드를 기다려 주세요."),
    INVALID_SONG("선택할 수 없는 곡이에요. 다시 검색해 주세요."),
    DRIVER_WHILE_DRIVING("운전석 선곡은 정차 중에만 할 수 있어요."),
}

/** One immutable pick per seat per round. Sensor callbacks never modify this queue. */
class PartyEngine {
    var participants: List<SeatId> = emptyList()
        private set
    val picks: Map<SeatId, Song> get() = selected.toMap()
    private val selected = linkedMapOf<SeatId, Song>()
    val queue: List<QueueEntry> get() = entries.toList()
    private val entries = mutableListOf<QueueEntry>()
    var index = 0
        private set
    var round = 1
        private set
    var nextLead: SeatId? = null
        private set
    var needsReview = false
        private set
    val current get() = entries.getOrNull(index)
    val waiting get() = entries.drop(index + if (current == null) 0 else 1)
    val ready get() = ready(false)

    /**
     * Seats that make up this round. While driving the driver sits the round out (unless they already picked at
     * the last stop); everybody else keeps choosing on their phones.
     */
    fun roundSeats(driving: Boolean) = participants.filter { !(driving && it == SeatId.FL && it !in selected) }
    fun ready(driving: Boolean) = !needsReview && roundSeats(driving).let { seats -> seats.isNotEmpty() && seats.all { selected.containsKey(it) } }

    /**
     * [keepCurrent] protects the track people were actually listening to. A track that merely became current
     * after the previous one ended (not started yet) follows the new roster like any other waiting track.
     */
    fun confirmParticipants(values: Collection<SeatId>, keepCurrent: Boolean = true) {
        val roster = SeatId.entries.filter { it in values }
        val keep = entries.take(index.coerceAtMost(entries.size)) +
            entries.drop(index).filterIndexed { offset, entry -> (offset == 0 && current != null && keepCurrent) || entry.seat in roster }
        entries.clear(); entries.addAll(keep)
        participants = roster
        selected.keys.retainAll(roster.toSet())
        needsReview = false
    }

    fun markForReview() { if (participants.isNotEmpty()) needsReview = true }

    /**
     * Same rule for the car screen and for phones/seat screens. While the car is moving only the driver's seat is
     * refused; passengers may keep choosing on their own devices.
     */
    fun pick(seat: SeatId, song: Song, driving: Boolean = false): PickResult {
        pickBlock(seat, driving)?.let { return it }
        selected[seat] = song.normalized() ?: return PickResult.INVALID_SONG
        return PickResult.OK
    }

    /** Why [seat] cannot pick right now, or null when it can. */
    fun pickBlock(seat: SeatId, driving: Boolean = false): PickResult? = when {
        seat !in participants -> PickResult.NOT_PARTICIPANT
        needsReview -> PickResult.REVIEW_PENDING
        seat in selected -> PickResult.ALREADY_PICKED
        driving && seat == SeatId.FL -> PickResult.DRIVER_WHILE_DRIVING
        else -> null
    }

    fun submit(seat: SeatId, song: Song) = pick(seat, song) == PickResult.OK
    fun submit(seat: SeatId, trackId: String) = Catalog.find(trackId)?.let { submit(seat, it.song) } ?: false

    fun buildRound(driving: Boolean = false): Boolean {
        if (!ready(driving)) return false
        val seats = roundSeats(driving)
        // Drop already played history before bounding the pending queue.
        val pending = entries.drop(index)
        if (pending.size + seats.size > MAX_QUEUE) return false
        val lead = nextLead
        val offset = if (lead == null) 0 else seats.indexOfFirst { it.ordinal >= lead.ordinal }.let { if (it < 0) 0 else it }
        val order = seats.drop(offset) + seats.take(offset)
        entries.clear(); entries.addAll(pending)
        entries.addAll(order.map { QueueEntry(round, it, selected.getValue(it)) })
        index = 0
        nextLead = seats[(offset + 1) % seats.size]
        round++
        selected.clear()
        return true
    }

    fun next(): Boolean {
        if (current == null) return false
        index++
        return true
    }
    fun previous(): Boolean {
        if (entries.isEmpty() || index <= 0) return false
        index--
        return true
    }
    fun restore(roster: List<SeatId>, savedPicks: Map<SeatId, Song>, savedQueue: List<QueueEntry>,
                savedIndex: Int, savedRound: Int, savedLead: SeatId?) {
        participants = SeatId.entries.filter { it in roster }
        selected.clear()
        savedPicks.forEach { (seat, song) -> if (seat in participants) song.normalized()?.let { selected[seat] = it } }
        entries.clear()
        entries.addAll(savedQueue.filter { it.round > 0 }.mapNotNull { e -> e.song.normalized()?.let { e.copy(song = it) } }
            .distinctBy { it.key }.take(MAX_QUEUE))
        index = savedIndex.coerceIn(0, entries.size)
        round = maxOf(1, savedRound, (entries.maxOfOrNull { it.round } ?: 0) + 1)
        nextLead = savedLead
        needsReview = participants.isNotEmpty()
    }
    fun reset() {
        participants = emptyList(); selected.clear(); entries.clear()
        index = 0; round = 1; nextLead = null; needsReview = false
    }

    companion object { const val MAX_QUEUE = 36 }
}

/**
 * Per-seat join tokens for the QR links. Only confirmed participants get one; a seat keeps its token while it
 * stays in the party so phones do not need to rescan, and loses it when it leaves.
 */
class SeatTokens(private val newToken: () -> String) {
    private val bySeat = linkedMapOf<SeatId, String>()
    val all: Map<SeatId, String> get() = bySeat.toMap()

    fun issue(participants: Collection<SeatId>) {
        bySeat.keys.retainAll(participants.toSet())
        participants.forEach { seat -> if (seat !in bySeat) bySeat[seat] = generateSequence { newToken() }.first { it !in bySeat.values } }
    }
    fun seatFor(token: String?): SeatId? = if (token.isNullOrEmpty()) null else bySeat.entries.firstOrNull { it.value == token }?.key
    fun tokenFor(seat: SeatId) = bySeat[seat]
    fun restore(saved: Map<SeatId, String>, participants: Collection<SeatId>) {
        bySeat.clear()
        saved.filter { (seat, token) -> seat in participants && TOKEN.matches(token) }.forEach { (seat, token) ->
            if (token !in bySeat.values) bySeat[seat] = token
        }
        issue(participants)
    }
    fun clear() = bySeat.clear()

    companion object { val TOKEN = Regex("[A-Za-z0-9_-]{16,64}") }
}
