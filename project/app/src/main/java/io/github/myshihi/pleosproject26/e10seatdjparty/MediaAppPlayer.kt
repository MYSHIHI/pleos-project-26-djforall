package io.github.myshihi.pleosproject26.e10seatdjparty

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.service.notification.NotificationListenerService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * An installed music app the party can play through (e.g. Melon or Genie signed in with the vehicle account).
 * [service] is its MediaBrowserService, if it exposes one; [searchIntent] means it handles the standard
 * "play from search" intent used by voice assistants.
 */
data class MediaApp(val packageName: String, val service: String?, val label: String, val searchIntent: Boolean) {
    val id get() = packageName

    companion object {
        /** Well-known Korean / global streaming apps, listed first when installed. Car builds may use other ids. */
        val KNOWN = linkedMapOf(
            "com.iloen.melon" to "멜론", "com.ktmusic.geniemusic" to "지니뮤직", "skplanet.musicmate" to "FLO",
            "com.neowiz.android.bugs" to "벅스", "com.naver.vibe" to "VIBE",
            "com.google.android.apps.youtube.music" to "YouTube Music", "com.spotify.music" to "Spotify",
        )
        private val EXCLUDED = setOf("com.android.bluetooth")

        fun discover(context: Context): List<MediaApp> {
            val pm = context.packageManager
            val services = pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }.filter { it.exported }.associate { it.packageName to it.name }
            val searchers = pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
            val known = KNOWN.keys.filter { pkg -> try { pm.getApplicationInfo(pkg, 0); true } catch (_: Exception) { false } }
            return (services.keys + searchers + known).distinct()
                .filter { it != context.packageName && it !in EXCLUDED }
                .map { pkg ->
                    val label = KNOWN[pkg] ?: try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
                    MediaApp(pkg, services[pkg], label, pkg in searchers)
                }
                .sortedWith(compareBy<MediaApp> { KNOWN.keys.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.label })
        }

        /** Accepts both the current id (package) and the v0.2.0 form "package/service". */
        fun parse(id: String?, apps: List<MediaApp>) = id?.substringBefore('/')?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
    }
}

/**
 * Optional "notification access" grant. It is the Android-standard way for a controller app to see another app's
 * active media session (like a car's media widget does). This service never reads notifications.
 */
class SessionAccessService : NotificationListenerService()

object SessionAccess {
    fun component(context: Context) = ComponentName(context, SessionAccessService::class.java)
    fun granted(context: Context) = try {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    } catch (_: Exception) { false }

    fun controllerFor(context: Context, packageName: String): MediaControllerCompat? = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
            .firstOrNull { it.packageName == packageName }
            ?.let { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(it.sessionToken)) }
    } catch (_: Exception) { null }
}

interface SongPlayer {
    val playing: Boolean
    val position: Int
    val duration: Int
    fun load(value: QueueEntry?, at: Int = 0)
    fun play()
    fun pause()
    fun seek(value: Int)
    fun publish()
    fun background()
    fun release()
}

/**
 * Drives an external music app with the vehicle's own account. Three ways, best first:
 *  1. BROWSER – the app's MediaBrowserService accepts us: search its catalog, play by media id, follow playback.
 *  2. SESSION – browsing is refused but notification access is granted: control its active media session,
 *     play requested songs by name ("play from search") and follow playback.
 *  3. INTENT – only the voice-assistant "play from search" intent: hand the request to the app; the party cannot
 *     see when the song ends, so people press "다음 곡" themselves.
 * Which of these a given app allows is decided by that app, not by this code.
 */
class MediaAppPlayer(
    context: Context,
    val app: MediaApp,
    private val canPlay: () -> Boolean,
    private val changed: (String) -> Unit,
    private val ended: () -> Unit,
    private val launch: (Intent) -> Boolean,
) : SongPlayer {
    enum class Mode(val text: String) {
        NONE("연결 안 됨"), BROWSER("검색·재생·곡 종료 감지"), SESSION("이름 요청·재생·곡 종료 감지 (알림 접근)"), INTENT("이름 요청만 · 곡이 끝나면 직접 '다음 곡'")
    }

    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private val waiting = mutableListOf<(String?) -> Unit>()
    private var connecting = false
    private var entry: QueueEntry? = null
    private var offset = 0
    private var started = false
    private var pendingSeek = 0
    private var identity: String? = null
    var mode = Mode.NONE
        private set
    var lastError: String? = null
        private set

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (!started || state == null) return
            when (state.state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    if (pendingSeek > 0) { controller?.transportControls?.seekTo(pendingSeek.toLong()); pendingSeek = 0 }
                    if (identity == null) identity = identityOf(controller?.metadata)
                }
                PlaybackStateCompat.STATE_STOPPED -> if (identity != null) { finish(); return }
                PlaybackStateCompat.STATE_ERROR -> { changed("${app.label}에서 재생하지 못했어요. ${state.errorMessage ?: ""}".trim()); return }
                else -> Unit
            }
            changed(if (playing) "${app.label}에서 함께 듣는 중" else "${app.label} · 일시정지")
        }
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (!started) return
            val now = identityOf(metadata) ?: return
            val mine = entry?.song?.mediaId
            if (mine != null && metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID) == mine) { identity = now; return }
            // The app moved on to its own next item: our queued song is over. Stop it before it plays further.
            if (identity != null && now != identity) { controller?.transportControls?.pause(); finish() }
        }
        override fun onSessionDestroyed() {
            detach()
            if (started) { started = false; changed("${app.label} 재생 세션이 닫혔어요. 다시 재생해 주세요.") }
        }
    }

    private fun identityOf(m: MediaMetadataCompat?): String? = m?.let {
        it.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            ?: listOfNotNull(it.getString(MediaMetadataCompat.METADATA_KEY_TITLE), it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)).joinToString("|").ifEmpty { null }
    }

    private fun finish() {
        started = false; identity = null; offset = 0
        ended()
    }

    private fun attach(c: MediaControllerCompat, newMode: Mode) {
        controller?.unregisterCallback(callback)
        controller = c; mode = newMode
        c.registerCallback(callback, handler)
    }

    private fun detach() {
        controller?.unregisterCallback(callback); controller = null
        try { browser?.disconnect() } catch (_: Exception) { }
        browser = null; mode = Mode.NONE
    }

    /** Picks the best available way to reach the app; [done] receives null when at least one way works. */
    fun connect(done: (String?) -> Unit) {
        if (controller != null && (mode == Mode.BROWSER || mode == Mode.SESSION)) { done(null); return }
        waiting.add(done)
        if (connecting) return
        connecting = true
        tryBrowser { ok -> if (!ok) fallback(); connecting = false; flush() }
    }

    private fun fallback() {
        if (SessionAccess.granted(ctx)) {
            SessionAccess.controllerFor(ctx, app.packageName)?.let { attach(it, Mode.SESSION); lastError = null; return }
        }
        mode = if (app.searchIntent) Mode.INTENT else Mode.NONE
        lastError = when {
            mode == Mode.INTENT -> null
            !SessionAccess.granted(ctx) -> "${app.label}이(가) 외부 연결을 허용하지 않아요. 연결 설정에서 '음악 앱 제어 권한'을 켜고 ${app.label}을(를) 한 번 실행해 주세요."
            else -> "${app.label}의 재생 세션이 없어요. ${app.label}을(를) 한 번 실행한 뒤 다시 시도해 주세요."
        }
    }

    private fun flush() { val list = waiting.toList(); waiting.clear(); list.forEach { it(if (mode == Mode.NONE) lastError ?: "${app.label}에 연결하지 못했어요." else null) } }

    private fun tryBrowser(done: (Boolean) -> Unit) {
        val service = app.service ?: return done(false)
        var answered = false
        fun answer(ok: Boolean) { if (!answered) { answered = true; done(ok) } }
        val b = MediaBrowserCompat(ctx, ComponentName(app.packageName, service), object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val c = try { MediaControllerCompat(ctx, browser!!.sessionToken) } catch (_: Exception) { null }
                if (c == null) { browser = null; answer(false); return }
                attach(c, Mode.BROWSER); lastError = null; answer(true)
            }
            override fun onConnectionFailed() { browser = null; answer(false) }
            override fun onConnectionSuspended() { detach() }
        }, null)
        browser = b
        try { b.connect() } catch (_: Exception) { browser = null; answer(false); return }
        handler.postDelayed({ if (!answered) { try { b.disconnect() } catch (_: Exception) { }; browser = null; answer(false) } }, 6_000)
    }

    /**
     * Catalog search when the app allows browsing. Otherwise (or with no results) the typed text itself becomes a
     * "request by name" item, so people can still pick any song the app can find.
     */
    fun search(query: String, done: (List<Song>?, String?) -> Unit) {
        connect { error ->
            val request = Song.request(app.id, query)
            if (error != null) { done(listOfNotNull(request).takeIf { app.searchIntent }, error); return@connect }
            val b = browser
            if (mode != Mode.BROWSER || b == null) {
                done(listOfNotNull(request), if (request == null) "곡 제목과 가수를 입력해 주세요. 예: 밤양갱 - 비비" else null)
                return@connect
            }
            if (query.isBlank()) { done(emptyList(), null); return@connect }
            val withRequest: (List<Song>) -> List<Song> = { found -> (found.take(MAX_RESULTS) + listOfNotNull(request)).distinctBy { it.key } }
            try {
                b.search(query, Bundle(), object : MediaBrowserCompat.SearchCallback() {
                    override fun onSearchResult(q: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                        val songs = toSongs(items)
                        if (songs.isNotEmpty()) done(withRequest(songs), null) else browseFallback(b, query) { found, e -> done(withRequest(found.orEmpty()), e.takeIf { found.isNullOrEmpty() && request == null }) }
                    }
                    override fun onError(q: String, extras: Bundle?) { browseFallback(b, query) { found, e -> done(withRequest(found.orEmpty()), e.takeIf { found.isNullOrEmpty() && request == null }) } }
                })
            } catch (_: Exception) { browseFallback(b, query) { found, e -> done(withRequest(found.orEmpty()), e.takeIf { found.isNullOrEmpty() && request == null }) } }
        }
    }

    private fun browseFallback(b: MediaBrowserCompat, query: String, done: (List<Song>?, String?) -> Unit) {
        val found = mutableListOf<Song>()
        var pending = 0
        var finished = false
        fun complete() {
            if (finished || pending > 0) return
            finished = true
            val q = query.trim()
            done(found.filter { it.title.contains(q, true) || it.subtitle.contains(q, true) }.distinctBy { it.key }.take(MAX_RESULTS),
                if (found.isEmpty()) "${app.label}이(가) 검색을 지원하지 않거나 결과가 없어요." else null)
        }
        fun load(id: String, depth: Int) {
            pending++
            try {
                b.subscribe(id, object : MediaBrowserCompat.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<MediaBrowserCompat.MediaItem>) {
                        b.unsubscribe(parentId)
                        found += toSongs(children)
                        if (depth < 2 && found.size < 300) children.filter { it.isBrowsable }.take(12).forEach { child -> child.mediaId?.let { load(it, depth + 1) } }
                        pending--; complete()
                    }
                    override fun onError(parentId: String) { pending--; complete() }
                })
            } catch (_: Exception) { pending--; complete() }
        }
        load(b.root, 0)
        handler.postDelayed({ if (!finished) { pending = 0; complete() } }, 6_000)
    }

    private fun toSongs(items: List<MediaBrowserCompat.MediaItem>) = items.filter { it.isPlayable && !it.mediaId.isNullOrBlank() }.map {
        val d = it.description
        Song.external(app.id, it.mediaId!!, d.title?.toString().orEmpty().ifBlank { "제목 없음" }, d.subtitle?.toString().orEmpty())
    }

    override val playing get() = started && controller?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
    override val position: Int get() {
        val s = controller?.playbackState
        if (!started || s == null) return offset
        val base = s.position
        val now = if (s.state == PlaybackStateCompat.STATE_PLAYING) base + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else base
        return now.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
    override val duration: Int get() = if (started) controller?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.toInt()?.coerceAtLeast(0) ?: 0 else 0

    override fun load(value: QueueEntry?, at: Int) {
        pause()
        entry = value; offset = at.coerceAtLeast(0); started = false; identity = null; pendingSeek = 0
    }

    private fun searchExtras(song: Song) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, song.title)
        song.subtitle.substringBefore(" · ").takeIf { song.requested && it != "이름으로 요청" }?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
        putString(SearchManager.QUERY, song.query ?: song.title)
    }

    override fun play() {
        if (!canPlay()) { changed("정차 중 참여 좌석을 확인한 뒤 재생해 주세요."); return }
        val value = entry ?: return
        connect { error ->
            if (!canPlay()) return@connect
            val song = value.song
            val c = controller
            when {
                c != null && started -> c.transportControls.play()
                c != null -> {
                    started = true; identity = null; pendingSeek = offset
                    if (!song.mediaId.isNullOrBlank()) c.transportControls.playFromMediaId(song.mediaId, Bundle())
                    else c.transportControls.playFromSearch(song.query ?: song.title, searchExtras(song))
                    changed("${app.label}에 재생을 요청했어요.")
                }
                app.searchIntent -> {
                    val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName)
                        .putExtras(searchExtras(song)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (launch(intent)) { started = true; changed("${app.label}에서 「${song.title}」 재생을 요청했어요. 곡이 끝나면 모두의 DJ로 돌아와 '다음 곡'을 눌러 주세요.") }
                    else changed("${app.label}을(를) 열지 못했어요.")
                }
                else -> changed(error ?: "${app.label}에 연결하지 못했어요.")
            }
        }
    }

    override fun pause() {
        if (!started) return
        offset = position
        try { controller?.transportControls?.pause() } catch (_: Exception) { }
    }

    override fun seek(value: Int) {
        offset = value.coerceAtLeast(0)
        if (started) try { controller?.transportControls?.seekTo(offset.toLong()) } catch (_: Exception) { }
    }

    override fun publish() { }
    override fun background() = pause()
    override fun release() { pause(); detach() }

    companion object { const val MAX_RESULTS = 30 }
}

/** Routes each queued song to the bundled player or to the music app it came from. */
class PlayerHub(
    private val bundled: PartyPlayer,
    private val external: (String) -> MediaAppPlayer?,
) : SongPlayer {
    private var active: SongPlayer = bundled

    override val playing get() = active.playing
    override val position get() = active.position
    override val duration get() = active.duration

    override fun load(value: QueueEntry?, at: Int) {
        active.pause()
        val app = value?.song?.app
        val target: SongPlayer? = if (app == null) bundled else external(app)
        if (target == null) {
            active = bundled; bundled.load(null)
            bundled.unavailable("이 곡의 음악 앱을 찾을 수 없어요. 연결 설정에서 음악 앱을 확인해 주세요.")
            return
        }
        if (target !== bundled) bundled.load(null) // keep our own MediaSession inactive while another app plays
        active = target
        target.load(value, at)
    }
    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun seek(value: Int) = active.seek(value)
    override fun publish() = active.publish()
    override fun background() = active.background()
    override fun release() { bundled.release() }
}
