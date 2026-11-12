package io.github.myshihi.pleosproject26.e10seatdjparty

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

class PartyPlayer(
    context: Context,
    private val canPlay: () -> Boolean,
    private val changed: (String) -> Unit,
    private val ended: () -> Unit,
    private val skip: (Boolean) -> Unit,
) : SongPlayer {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ value ->
            if (value != AudioManager.AUDIOFOCUS_GAIN) {
                pause()
                changed("통화·차량 안내로 멈췄어요. 직접 재생해 주세요.")
            }
        }, handler).build()
    private val session = MediaSession(app, "SeatDjParty").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (canPlay()) play() }
            override fun onPause() { pause(); changed("잠시 멈췄어요") }
            override fun onStop() { onPause() }
            override fun onSkipToNext() { if (canPlay()) skip(true) }
            override fun onSkipToPrevious() { if (canPlay()) skip(false) }
            override fun onSeekTo(pos: Long) { if (canPlay()) { seek(pos.toInt()); changed("재생 위치를 옮겼어요") } }
        }, handler)
    }
    private var media: MediaPlayer? = null
    private var entry: QueueEntry? = null
    private var offset = 0
    override var playing = false
        private set
    override val position get() = try { media?.currentPosition ?: offset } catch (_: IllegalStateException) { offset }
    override val duration get() = try { media?.duration ?: entry?.song?.track?.durationMs ?: 0 } catch (_: IllegalStateException) { 0 }

    override fun load(value: QueueEntry?, at: Int) {
        pause()
        media?.release(); media = null
        entry = value; offset = at.coerceAtLeast(0)
        val track = value?.song?.track
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track?.title ?: "모두의 DJ")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, value?.seat?.label ?: "참여 좌석을 확인해 주세요")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track?.durationMs?.toLong() ?: 0)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "함께 고르는 여행 · 오리지널 미니트랙").build())
        publish()
    }

    override fun play() {
        if (!canPlay()) { changed("정차 중 참여 좌석을 확인한 뒤 재생해 주세요."); return }
        val value = entry ?: return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            changed("오디오 사용 요청이 거절됐어요. 잠시 후 직접 다시 재생해 주세요."); return
        }
        try {
            if (media == null) {
                val player = MediaPlayer()
                media = player
                player.setAudioAttributes(attributes)
                app.assets.openFd("music/${value.song.key}.wav").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                player.prepare()
                player.seekTo(offset.coerceAtMost((player.duration - 1).coerceAtLeast(0)))
                player.setOnCompletionListener {
                    playing = false
                    offset = 0
                    // Natural completion advances exactly once. The activity decides whether the queue may continue.
                    audio.abandonAudioFocusRequest(focus)
                    ended()
                }
                player.setOnErrorListener { _, _, _ ->
                    pause(); media?.release(); media = null
                    changed("이 곡을 재생하지 못했어요. 다시 재생하거나 다음 곡을 골라 주세요.")
                    true
                }
            }
            media?.start(); playing = true
            publish()
            changed("우리의 선곡을 함께 듣는 중")
        } catch (_: Exception) {
            pause(); media?.release(); media = null
            changed("음원 파일을 열지 못했어요. 다시 재생해 주세요.")
        }
    }

    override fun seek(value: Int) {
        offset = value.coerceIn(0, (duration - 1).coerceAtLeast(0))
        try { media?.seekTo(offset) } catch (_: IllegalStateException) { }
        publish()
    }
    override fun pause() {
        offset = position
        try { if (playing) media?.pause() } catch (_: IllegalStateException) { }
        playing = false
        audio.abandonAudioFocusRequest(focus)
        publish()
    }
    override fun publish() {
        val enabled = canPlay()
        session.isActive = entry != null
        val actions = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            if (enabled) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO else 0L
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position.toLong(), if (playing) 1f else 0f).build())
    }
    override fun background() { pause(); session.isActive = false }
    /** Called when a queued song cannot be played by any available source. */
    fun unavailable(message: String) = changed(message)
    override fun release() { pause(); media?.release(); media = null; session.release() }
}
