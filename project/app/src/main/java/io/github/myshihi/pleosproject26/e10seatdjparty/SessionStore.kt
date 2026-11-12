package io.github.myshihi.pleosproject26.e10seatdjparty

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local bookmark of the party. Version 2 stores full songs (bundled or media-app), seat join tokens and the chosen
 * music app. Version 1 data (bundled track ids only) is migrated on load.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dj_party_v1", Context.MODE_PRIVATE)
    var position = 0
        private set
    var origins: Map<SeatId, String> = emptyMap()
        private set
    var tokens: Map<SeatId, String> = emptyMap()
        private set
    var musicApp: String? = null
        private set

    private fun seat(value: String) = SeatId.entries.find { it.name == value }

    private fun songFrom(value: Any?): Song? = when (value) {
        is String -> Catalog.find(value)?.song // version 1: bundled track id
        is JSONObject -> Song(
            value.optString("key"), value.optString("title"), value.optString("subtitle"),
            value.optString("app").ifEmpty { null }, value.optString("mediaId").ifEmpty { null },
            value.optString("color").ifEmpty { "#B6A1F5" }, value.optString("query").ifEmpty { null },
        ).normalized()
        else -> null
    }

    private fun songJson(song: Song) = JSONObject().put("key", song.key).put("title", song.title).put("subtitle", song.subtitle)
        .put("app", song.app ?: "").put("mediaId", song.mediaId ?: "").put("color", song.color).put("query", song.query ?: "")

    fun load(engine: PartyEngine) {
        try {
            val data = JSONObject(prefs.getString("session", "{}") ?: "{}")
            val version = data.optInt("version")
            if (version != 1 && version != 2) return
            val roster = data.optJSONArray("participants") ?: JSONArray()
            val picks = data.optJSONObject("picks") ?: JSONObject()
            val source = data.optJSONObject("origins") ?: JSONObject()
            origins = source.keys().asSequence().mapNotNull { key -> seat(key)?.let { it to source.optString(key) } }.toMap()
            val savedTokens = data.optJSONObject("tokens") ?: JSONObject()
            tokens = savedTokens.keys().asSequence().mapNotNull { key -> seat(key)?.let { it to savedTokens.optString(key) } }.toMap()
            musicApp = data.optString("musicApp").ifEmpty { null }
            val queue = data.optJSONArray("queue") ?: JSONArray()
            engine.restore(
                (0 until roster.length()).mapNotNull { seat(roster.optString(it)) },
                picks.keys().asSequence().mapNotNull { key -> seat(key)?.let { s -> songFrom(picks.opt(key))?.let { s to it } } }.toMap(),
                (0 until queue.length()).mapNotNull { i -> queue.optJSONObject(i)?.let { item ->
                    val song = songFrom(if (version == 1) item.optString("track") else item.opt("song"))
                    val s = seat(item.optString("seat"))
                    if (song != null && s != null) QueueEntry(item.optInt("round"), s, song) else null
                } },
                data.optInt("index"), data.optInt("round", 1), seat(data.optString("nextLead")),
            )
            position = data.optInt("position").coerceIn(0, 3_600_000)
        } catch (_: Exception) { engine.reset(); position = 0; origins = emptyMap(); tokens = emptyMap(); musicApp = null }
    }

    fun save(engine: PartyEngine, at: Int, confirmedOrigins: Map<SeatId, String>, seatTokens: Map<SeatId, String>, app: String?) {
        position = at.coerceAtLeast(0)
        origins = confirmedOrigins.toMap()
        tokens = seatTokens.toMap()
        musicApp = app
        val data = JSONObject().put("version", 2)
            .put("participants", JSONArray(engine.participants.map { it.name }))
            .put("picks", JSONObject().apply { engine.picks.forEach { (seat, song) -> put(seat.name, songJson(song)) } })
            .put("origins", JSONObject(origins.mapKeys { it.key.name }))
            .put("tokens", JSONObject(tokens.mapKeys { it.key.name }))
            .put("musicApp", app ?: "")
            .put("queue", JSONArray(engine.queue.map { JSONObject().put("round", it.round).put("seat", it.seat.name).put("song", songJson(it.song)) }))
            .put("index", engine.index).put("round", engine.round).put("nextLead", engine.nextLead?.name)
            .put("position", position)
        // Small bounded document; commit preserves bookmarks on explicit process termination.
        prefs.edit().putString("session", data.toString()).commit()
    }
}
