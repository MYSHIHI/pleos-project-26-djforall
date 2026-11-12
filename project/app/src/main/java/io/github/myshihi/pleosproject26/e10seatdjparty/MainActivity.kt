package io.github.myshihi.pleosproject26.e10seatdjparty

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val baseColor = Color.rgb(20, 18, 29)
    private val panel = Color.rgb(34, 30, 47)
    private val muted = Color.rgb(174, 165, 187)
    private val ink = Color.rgb(247, 242, 236)
    private val lime = Color.rgb(209, 250, 136)
    private val lavender = Color.rgb(182, 161, 245)
    private val handler = Handler(Looper.getMainLooper())
    private val engine = PartyEngine()
    private lateinit var store: SessionStore
    private lateinit var bundledPlayer: PartyPlayer
    private lateinit var player: PlayerHub
    private lateinit var source: VehicleSeatSource
    private lateinit var guard: DrivingGuard
    private val random = SecureRandom()
    private val tokens = SeatTokens { ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) } }
    private val server = PartyServer(PORT) { handleWeb(it) }
    private var addresses: List<String> = emptyList()
    private var mediaApps: List<MediaApp> = emptyList()
    private var musicApp: MediaApp? = null
    private val appPlayers = mutableMapOf<String, MediaAppPlayer>()
    /** Songs recently shown in search results, so a pick by key can be resolved. Main thread only. */
    private val shownSongs = object : LinkedHashMap<String, Song>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) = size > 600
    }
    private val qrCache = mutableMapOf<String, Bitmap>()
    private val signals = SeatId.entries.associateWith { SeatSignal() }.toMutableMap()
    private val baseline = mutableMapOf<SeatId, Boolean?>()
    private val origins = mutableMapOf<SeatId, String>()
    private val signalLabels = mutableMapOf<SeatId, TextView>()
    private var draft = mutableSetOf<SeatId>()
    private var selectedSeat: SeatId? = null
    private var selectedSong: Song? = null
    private var carQuery = ""
    private var carResults: List<Song> = Catalog.search("")
    private var carNote: String? = null
    private var searchBox: EditText? = null
    private var stage = 0
    private var started = false
    private var uxAllowed = false
    private var uxMessage = "정차 상태 확인 중"
    private var sample = false
    private var sampleKind = SampleKind.NORMAL
    private var sampleAt = 0L
    private var simulatedDeparture = false
    private var message = "참여 좌석을 사람이 직접 확인해 주세요."
    private var sourceDialog: AlertDialog? = null
    private var footer: TextView? = null
    private var progressText: TextView? = null
    private var statusText: TextView? = null
    private var playbackButton: Button? = null
    private var seekBar: SeekBar? = null
    private var seeking = false
    private var lastSave = 0L
    private var renderPending = false
    private val allowed get() = started && uxAllowed && !simulatedDeparture
    private val driving get() = !uxAllowed || simulatedDeparture
    // Playback continues while driving; only on-screen input is limited by the car's UX restrictions.
    private val canPlay get() = started && engine.current != null && !engine.needsReview
    private val sourceLabel get() = musicApp?.label ?: "앱 내장 음원 6곡"
    private val uiScale get() = if (resources.configuration.screenWidthDp >= 1400) 1.15f else 1f
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            refreshSampleSignals()
            updateSignals()
            updatePlayback()
            if (player.playing && SystemClock.elapsedRealtime() - lastSave > 2_000) save()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        store.load(engine)
        origins.putAll(store.origins)
        tokens.restore(store.tokens, engine.participants)
        mediaApps = MediaApp.discover(this)
        musicApp = MediaApp.parse(store.musicApp, mediaApps)
        resetCarSearch()
        draft.addAll(engine.participants)
        stage = if (engine.queue.isNotEmpty()) 2 else if (engine.participants.isNotEmpty()) 1 else 0
        bundledPlayer = PartyPlayer(this, { canPlay }, ::playerMessage, { moveTrack(forward = true, completed = true) }, { forward -> moveTrack(forward) })
        player = PlayerHub(bundledPlayer) { id -> appPlayer(id) }
        player.load(engine.current, store.position)
        source = VehicleSeatSource(this) { seat, value ->
            val visibilityChanged = seat !in SeatId.primary && (signals[seat]?.supported == true) != (value.supported == true)
            signals[seat] = value
            val wasReview = engine.needsReview
            checkSignalsForReview()
            if ((!wasReview && engine.needsReview) || visibilityChanged) { save(); render() } else updateSignals()
        }
        guard = DrivingGuard(this) { value, info ->
            if (!started) return@DrivingGuard
            val oldAllowed = allowed
            uxAllowed = value; uxMessage = info
            if (!allowed) {
                if (oldAllowed) message = "운전 중에는 화면 조작 없이 음악이 이어져요. 동승자는 휴대폰으로 계속 고를 수 있어요."
                save(); sourceDialog?.dismiss()
                autoBuildWhileDriving()
            }
            player.publish()
            if (oldAllowed != allowed || !allowed) render()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true; uxAllowed = false
        engine.markForReview()
        addresses = LocalAddress.candidates()
        if (!server.start()) message = "휴대폰 선곡 연결(포트 $PORT)을 열지 못했어요. 차량 화면에서 선곡해 주세요."
        render()
        guard.start()
        if (sample) sampleSignals(sampleKind) else source.start()
        handler.post(ticker)
    }
    override fun onStop() {
        started = false
        server.stop()
        player.background(); message = "책갈피를 저장했어요. 직접 재생해 주세요."
        save()
        handler.removeCallbacksAndMessages(null)
        sourceDialog?.dismiss(); source.stop(); guard.stop()
        uxAllowed = false
        super.onStop()
    }
    override fun onDestroy() { player.release(); appPlayers.values.forEach { it.release() }; super.onDestroy() }

    private fun playerMessage(value: String) {
        message = value
        save()
        updatePlayback()
    }

    private fun appPlayer(id: String): MediaAppPlayer? {
        val app = MediaApp.parse(id, mediaApps) ?: return null
        appPlayers[app.id]?.let { return it }
        return MediaAppPlayer(this, app, { canPlay }, ::playerMessage, { moveTrack(forward = true, completed = true) }) { intent ->
            try { startActivity(intent); true } catch (_: Exception) { false }
        }.also { appPlayers[app.id] = it }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density * uiScale).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun rounded(fill: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    private fun label(value: String, size: Float = 18f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size * uiScale; setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1.06f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, primary: Boolean = false, enabled: Boolean = true, drivingOk: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = value; isAllCaps = false; textSize = 17f * uiScale
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (primary) baseColor else ink)
        background = rounded(if (primary) lime else panel, 13, if (primary) null else Color.rgb(73, 65, 88))
        isEnabled = enabled; alpha = if (enabled) 1f else .42f
        // Only simple playback controls stay usable while driving.
        setOnClickListener { if (allowed || (drivingOk && started)) action() }
    }
    private fun gap(parent: LinearLayout, amount: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(amount))) }
    private fun action(parent: LinearLayout, value: String, primary: Boolean = false, enabled: Boolean = true, clicked: () -> Unit): Button {
        val view = button(value, primary, enabled, action = clicked)
        parent.addView(view, LinearLayout.LayoutParams(-1, dp(54)))
        return view
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    private fun clock(value: Int) = "%d:%02d".format(Locale.US, value / 60_000, value / 1000 % 60)

    private fun render() {
        renderPending = false
        signalLabels.clear(); progressText = null; statusText = null; playbackButton = null; seekBar = null; searchBox = null
        val root = column().apply { setBackgroundColor(baseColor); setPadding(dp(28), dp(14), dp(28), dp(14)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(28) + bars.left, dp(14) + bars.top, dp(28) + bars.right, dp(14) + bars.bottom)
            insets
        }
        val header = row()
        header.addView(label("모두의 DJ", 23f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (allowed) header.addView(button(if (sample) "샘플 체험 · 연결 설정" else "Vehicle SDK · 연결 설정") { sourceSettings() })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(54)))
        gap(root, 18)
        if (!allowed) {
            drivingView(root)
        } else {
            val wide = resources.configuration.screenWidthDp >= 900
            val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            val playerScroll = ScrollView(this).apply { isFillViewport = true }
            val playerColumn = column()
            nowPlaying(playerColumn)
            playerScroll.addView(playerColumn)
            body.addView(playerScroll, if (wide) LinearLayout.LayoutParams(0, -1, .37f).apply { marginEnd = dp(28) }
                else LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(12) })
            val workflow = column()
            val tabs = row()
            listOf("01 참여 좌석", "02 한 곡씩 선택", "03 함께 듣기").forEachIndexed { index, title ->
                tabs.addView(button(title, stage == index) {
                    stage = index
                    if (stage == 0) draft = engine.participants.toMutableSet()
                    render()
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            workflow.addView(tabs)
            gap(workflow, 16)
            if (engine.needsReview) {
                val review = button("좌석 신호·탑승 회차가 달라졌어요 · 참여 좌석 재확인", true) {
                    stage = 0; draft = engine.participants.toMutableSet(); render()
                }
                workflow.addView(review, LinearLayout.LayoutParams(-1, -2))
                gap(workflow, 12)
            }
            val scroll = ScrollView(this).apply { isFillViewport = true }
            val content = column().apply { setPadding(0, 0, dp(6), dp(12)) }
            when (stage) { 0 -> seats(content); 1 -> picking(content); else -> queue(content) }
            scroll.addView(content)
            workflow.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            body.addView(workflow, if (wide) LinearLayout.LayoutParams(0, -1, .63f) else LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        gap(root, 12)
        footer = label("", 13f, muted)
        root.addView(footer)
        setContentView(root)
        updateSignals(); updatePlayback()
    }

    /** Glanceable screen while driving: no typing or seat changes, just what plays, what's next and who has picked. */
    private fun drivingView(root: LinearLayout) {
        val box = column().apply { background = rounded(panel); setPadding(dp(36), dp(28), dp(36), dp(28)) }
        box.addView(label(if (simulatedDeparture) "샘플 · 출발 상황 · 화면 조작 없이 음악이 이어져요" else "운전 중 · 화면 조작 없이 음악이 이어져요", 20f, lime, true))
        gap(box, 14)
        val cur = engine.current
        box.addView(label(cur?.song?.title ?: "지금 나오는 곡이 없어요", 40f, ink, true))
        box.addView(label(cur?.let { "${it.seat.label}의 선곡  ·  라운드 ${it.round}" } ?: "동승자가 휴대폰으로 고르면 재생 목록에 자동으로 더해져요", 20f, muted))
        gap(box, 8)
        progressText = label("", 18f, muted); box.addView(progressText)
        gap(box, 14)
        val controls = row()
        controls.addView(button("이전 곡", enabled = canPlay, drivingOk = true) { moveTrack(false) }, LinearLayout.LayoutParams(0, dp(76), 1f))
        playbackButton = button(if (player.playing) "일시정지" else "재생", true, canPlay || player.playing, drivingOk = true) {
            if (player.playing) { player.pause(); message = "잠시 멈췄어요."; save(); updatePlayback() } else player.play()
        }
        controls.addView(playbackButton, LinearLayout.LayoutParams(0, dp(76), 1.4f).apply { marginStart = dp(10); marginEnd = dp(10) })
        controls.addView(button("다음 곡", enabled = canPlay, drivingOk = true) { moveTrack(true) }, LinearLayout.LayoutParams(0, dp(76), 1f))
        box.addView(controls)
        gap(box, 10)
        statusText = label(message, 17f, muted); box.addView(statusText)
        gap(box, 22)
        box.addView(label("다음 곡 · 남은 ${engine.waiting.size}곡", 21f, ink, true))
        gap(box, 4)
        val next = engine.waiting.take(3)
        if (next.isEmpty()) box.addView(label("아직 없어요", 19f, muted))
        next.forEach { box.addView(label("·  ${it.song.title}  —  ${it.seat.label}", 19f, muted)) }
        gap(box, 22)
        box.addView(label("이번 차례 선곡 · 라운드 ${engine.round}", 21f, ink, true))
        gap(box, 8)
        val seats = row()
        engine.participants.forEachIndexed { i, seat ->
            val picked = engine.picks[seat]
            val text = when { picked != null -> "${seat.label} ✓"; seat == SeatId.FL -> "${seat.label} · 운전 중 쉬어요"; else -> "${seat.label} · 고르는 중" }
            seats.addView(label(text, 17f, if (picked != null) lime else muted).apply {
                background = rounded(Color.rgb(27, 24, 38), 12); setPadding(dp(14), dp(10), dp(14), dp(10))
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(10) })
        }
        box.addView(seats)
        gap(box, 12)
        box.addView(label(if (engine.needsReview) "좌석 정보가 바뀌었어요. 정차 후 함께 고를 자리를 다시 확인해 주세요."
            else "동승석·뒷좌석은 휴대폰으로 계속 고를 수 있어요. 모두 고르면 재생 목록에 자동으로 더해져요.", 17f, if (engine.needsReview) Color.rgb(244, 186, 130) else lime))
        if (simulatedDeparture && uxAllowed) {
            gap(box, 16)
            val back = button("샘플 · 정차로 돌아가기", true) { }
            back.setOnClickListener { simulatedDeparture = false; message = "정차했어요."; player.publish(); render() }
            box.addView(back)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(box) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** While driving a round is complete without the driver; add it to the playlist without anyone touching the screen. */
    private fun autoBuildWhileDriving() {
        if (!started || !driving || !engine.ready(driving = true)) return
        val old = engine.current?.key
        if (engine.buildRound(driving = true)) {
            if (old != engine.current?.key) player.load(engine.current)
            message = "모두 골라서 다음 차례 곡을 재생 목록에 더했어요. 운전석은 이번 차례를 쉬어요."
            save()
        }
    }

    /** Re-render unless someone is typing in the car-screen search box; then render once the box loses focus. */
    private fun renderSoon() {
        if (searchBox?.hasFocus() == true) { renderPending = true; updatePlayback() } else render()
    }

    private fun nowPlaying(parent: LinearLayout) {
        parent.addView(label("모두의 취향이,\n하나의 플레이리스트로", 32f, ink, true))
        gap(parent, 8)
        parent.addView(label("한 좌석에 한 곡, 첫 순서는 라운드마다 돌아가요.", 15f, muted))
        gap(parent, 20)
        val song = engine.current?.song
        val art = RecordView(this, Color.parseColor(song?.color ?: "#B6A1F5")).apply {
            contentDescription = "모두의 DJ 오리지널 레코드 삽화"
        }
        parent.addView(art, LinearLayout.LayoutParams(-1, dp(270)))
        gap(parent, 18)
        parent.addView(label(engine.current?.let { "${it.seat.label}의 선곡  ·  라운드 ${it.round}" } ?: "지금은 함께 고르는 시간", 15f, lime, true))
        gap(parent, 7)
        parent.addView(label(song?.title ?: "우리의 첫 곡은?", 28f, ink, true))
        gap(parent, 5)
        val appName = song?.app?.let { id -> MediaApp.parse(id, mediaApps)?.label ?: "연결 안 된 음악 앱" }
        parent.addView(label(song?.let { listOfNotNull(it.subtitle.ifBlank { null }, appName).joinToString("  ·  ") }
            ?: "휴대폰으로 QR을 스캔하거나 이 화면에서 골라요", 15f, muted))
        gap(parent, 14)
        seekBar = SeekBar(this).apply {
            max = 1000
            isEnabled = canPlay
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(view: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(view: SeekBar) {
                    if (canPlay && player.duration > 0) player.seek((view.progress.toLong() * player.duration / 1000).toInt())
                    seeking = false; save(); updatePlayback()
                }
                override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) { }
            })
            contentDescription = "곡 재생 위치"
        }
        parent.addView(seekBar)
        progressText = label("", 14f, muted)
        parent.addView(progressText)
        gap(parent, 14)
        val controls = row()
        controls.addView(button("이전 곡", enabled = canPlay) { moveTrack(false) }, LinearLayout.LayoutParams(0, dp(54), 1f))
        playbackButton = button(if (player.playing) "일시정지" else "재생", true, canPlay || player.playing) {
            if (player.playing) { player.pause(); message = "잠시 멈췄어요. 책갈피는 자동 저장돼요."; save(); updatePlayback() }
            else player.play()
        }
        controls.addView(playbackButton, LinearLayout.LayoutParams(0, dp(54), 1.2f).apply { marginStart = dp(8); marginEnd = dp(8) })
        controls.addView(button("다음 곡", enabled = canPlay) { moveTrack(true) }, LinearLayout.LayoutParams(0, dp(54), 1f))
        parent.addView(controls)
        gap(parent, 12)
        statusText = label(message, 14f, muted)
        parent.addView(statusText)
        gap(parent, 14)
        parent.addView(label(musicApp?.let { "음악: ${it.label} (차량에 로그인된 계정으로 재생)\n이 앱은 계정 정보를 받거나 저장하지 않아요." }
            ?: "음악: 앱 내장 오리지널 6곡\n연결 설정에서 차량의 음악 앱(지니·멜론 등)을 고를 수 있어요.", 14f, muted))
    }

    private fun seats(parent: LinearLayout) {
        parent.addView(label("누가 함께 고를까요?", 29f, ink, true))
        gap(parent, 8)
        parent.addView(label("점유 신호는 참고만 해요. 사람인지 짐인지는 구분하지 않아요.\n음악 선택에 참여할 좌석을 사람이 직접 골라 주세요.", 17f, muted))
        gap(parent, 16)
        val visible = SeatId.entries.filter { it in SeatId.primary || signals[it]?.supported == true || it in draft || it in engine.participants }
        visible.chunked(2).forEach { pair ->
            val line = row()
            pair.forEachIndexed { i, seat ->
                val card = column().apply { background = rounded(panel, 15, if (seat in draft) lavender else null); setPadding(dp(12), dp(12), dp(12), dp(12)) }
                card.addView(button("${seat.label} · ${if (seat in draft) "참여 취소" else "참여 선택"}", seat in draft) {
                    if (!draft.add(seat)) draft.remove(seat)
                    render()
                }, LinearLayout.LayoutParams(-1, dp(52)))
                gap(card, 8)
                signalLabels[seat] = label("", 13f, muted)
                card.addView(signalLabels.getValue(seat))
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            parent.addView(line)
            gap(parent, 10)
        }
        gap(parent, 8)
        action(parent, "참여 좌석 확정 · ${draft.size}곳", true, draft.isNotEmpty()) {
            val removed = engine.participants.filter { it !in draft }
            if (removed.isNotEmpty() && engine.current != null) {
                AlertDialog.Builder(this).setTitle("참여 좌석을 바꿀까요?")
                    .setMessage("빠지는 좌석의 대기 곡을 목록에서 제외하고 그 좌석의 QR 코드도 무효가 돼요. 이미 듣기 시작한 곡은 보존하고 잠시 멈춰요.")
                    .setNegativeButton("돌아가기", null).setPositiveButton("참여 변경 적용") { _, _ -> if (allowed) confirmSeats() }.show()
            } else confirmSeats()
        }
        gap(parent)
        parent.addView(label("점유 미지원·신호 없음·만료는 수동 확인으로 표시해요.\n빈 좌석 표시만으로 참가자를 자동으로 빼지 않아요.\n확정하면 좌석마다 휴대폰 선곡용 QR 코드가 만들어져요.", 14f, muted))
    }

    private fun joinUrl(seat: SeatId, host: String? = addresses.firstOrNull()) =
        tokens.tokenFor(seat)?.let { token -> host?.let { "http://$it:$PORT/s/$token" } }

    private fun qrImage(url: String) = ImageView(this).apply {
        val bitmap = qrCache.getOrPut(url) {
            val qr = QrCode.encode(url)
            val quiet = 4
            val n = qr.size + quiet * 2
            val pixels = IntArray(n * n) { i ->
                val x = i % n - quiet; val y = i / n - quiet
                if (x in 0 until qr.size && y in 0 until qr.size && qr[x, y]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
        }
        setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "좌석 선곡 QR 코드"
    }

    private fun picking(parent: LinearLayout) {
        parent.addView(label("라운드 ${engine.round} · 좌석마다 한 곡", 28f, ink, true))
        gap(parent, 8)
        parent.addView(label("한 번 확정한 곡은 이번 라운드에 한 번만 들어가요.\n다음 라운드는 다른 좌석이 먼저 시작해요.", 16f, muted))
        gap(parent, 16)
        if (engine.participants.isEmpty() || engine.needsReview) {
            action(parent, "먼저 참여 좌석 확인하기", true) { stage = 0; draft = engine.participants.toMutableSet(); render() }
            return
        }
        remotePickCards(parent)
        gap(parent, 18)
        parent.addView(label("이 화면에서 고르기", 22f, ink, true))
        gap(parent, 10)
        val pending = engine.participants.filter { it !in engine.picks }
        if (selectedSeat !in pending) selectedSeat = pending.firstOrNull()
        engine.participants.chunked(3).forEach { group ->
            val line = row()
            group.forEachIndexed { i, seat ->
                line.addView(button("${seat.label}${if (seat in engine.picks) " ✓" else ""}", selectedSeat == seat, seat !in engine.picks) {
                    selectedSeat = seat; render()
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            parent.addView(line); gap(parent, 8)
        }
        gap(parent, 8)
        parent.addView(label(selectedSeat?.let { "${it.label}의 곡을 골라 주세요 · $sourceLabel" } ?: "모든 좌석의 곡이 모였어요!", 20f, lavender, true))
        gap(parent, 12)
        if (selectedSeat != null) {
            val bar = row()
            val box = EditText(this).apply {
                setText(carQuery); setSelection(text.length)
                hint = if (musicApp == null) "곡 제목·장르 검색 (비우면 전체)" else "곡·가수 검색 (예: 밤양갱 - 비비)"
                setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
                textSize = 17f * uiScale; setTextColor(ink); setHintTextColor(muted)
                background = rounded(Color.rgb(27, 24, 38), 13, Color.rgb(73, 65, 88)); setPadding(dp(14), 0, dp(14), 0)
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { searchCar(text.toString()); true } else false }
                setOnFocusChangeListener { _, focused -> if (!focused && renderPending) handler.post { render() } }
            }
            searchBox = box
            bar.addView(box, LinearLayout.LayoutParams(0, dp(54), 1f))
            bar.addView(button("검색") { searchCar(box.text.toString()) }, LinearLayout.LayoutParams(-2, dp(54)).apply { marginStart = dp(8) })
            parent.addView(bar)
            gap(parent, 10)
            carNote?.let { parent.addView(label(it, 14f, muted)); gap(parent, 8) }
            carResults.chunked(2).forEach { group ->
                val line = row()
                group.forEachIndexed { i, song ->
                    val chosen = selectedSong?.key == song.key
                    val view = button("${song.title}\n${song.subtitle}") { selectedSong = song; render() }
                    view.maxLines = 3
                    view.background = rounded(if (chosen) Color.rgb(66, 52, 88) else panel, 14, if (chosen) Color.parseColor(song.color) else null)
                    line.addView(view, LinearLayout.LayoutParams(0, dp(88), 1f).apply { if (i > 0) marginStart = dp(10) })
                }
                if (group.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
                parent.addView(line); gap(parent, 10)
            }
        }
        gap(parent, 4)
        action(parent, "이 좌석 선곡 확정", true, selectedSeat != null && selectedSong != null) {
            val seat = selectedSeat ?: return@action
            val song = selectedSong ?: return@action
            val result = engine.pick(seat, song)
            if (result == PickResult.OK) { save(); selectedSeat = null; selectedSong = null; render() } else toast(result.message)
        }
        gap(parent, 10)
        action(parent, "공동 큐 만들기 · ${engine.picks.size}/${engine.participants.size}곡", enabled = engine.ready) {
            val old = engine.current?.key
            if (engine.buildRound()) {
                if (old != engine.current?.key) player.load(engine.current)
                stage = 2; message = "큐를 만들었어요. 재생을 눌러 함께 들어요."
                save(); render()
            } else toast("대기 큐가 가득 찼어요. 몇 곡을 듣고 다시 추가해 주세요.")
        }
    }

    private fun remotePickCards(parent: LinearLayout) {
        val box = column().apply { background = rounded(panel, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(label("휴대폰·좌석 화면으로 고르기", 22f, ink, true))
        gap(box, 6)
        val host = addresses.firstOrNull()
        box.addView(label(when {
            !server.running -> "휴대폰 선곡 연결을 열지 못했어요. 이 화면에서 골라 주세요."
            host == null -> "차량 Wi‑Fi·핫스팟 연결이 없어요. 휴대폰을 차량 Wi‑Fi에 연결하거나 이 화면에서 골라 주세요."
            else -> "휴대폰을 차량 Wi‑Fi(핫스팟)에 연결하고 내 좌석 QR을 스캔하세요.\n검색해서 한 곡을 고르면 여기에 바로 표시돼요. 로그인·개인정보는 필요 없어요."
        }, 15f, muted))
        gap(box, 12)
        val columns = if (resources.configuration.screenWidthDp >= 900) 4 else 2
        engine.participants.chunked(columns).forEach { group ->
            val line = row().apply { gravity = Gravity.TOP }
            group.forEachIndexed { i, seat ->
                val card = column().apply { gravity = Gravity.CENTER_HORIZONTAL; background = rounded(Color.rgb(27, 24, 38), 14); setPadding(dp(10), dp(10), dp(10), dp(10)) }
                card.addView(label(seat.label, 16f, ink, true))
                gap(card, 6)
                val url = joinUrl(seat)
                if (url != null && server.running) card.addView(qrImage(url), LinearLayout.LayoutParams(dp(132), dp(132)))
                gap(card, 6)
                val pick = engine.picks[seat]
                card.addView(label(if (pick != null) "✓ ${pick.title}" else if (seat == SeatId.FL) "정차 중 선곡" else "선곡 대기", 13f, if (pick != null) lime else muted).apply {
                    gravity = Gravity.CENTER; maxLines = 2
                })
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            repeat(columns - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
            box.addView(line)
            gap(box, 8)
        }
        if (host != null && server.running) box.addView(label("QR이 안 되면 주소 입력: http://$host:$PORT/s/…  (연결 설정에서 좌석별 주소 확인)", 12f, muted))
        parent.addView(box)
    }

    private fun resetCarSearch() {
        carQuery = ""; selectedSong = null; carNote = null
        carResults = if (musicApp == null) Catalog.search("") else emptyList()
        if (musicApp != null) carNote = "${musicApp?.label}에서 곡·가수를 검색해 주세요. 목록에 없으면 「제목 - 가수」로 입력하면 그대로 요청해요."
        remember(carResults)
    }

    private fun remember(songs: List<Song>) { songs.forEach { shownSongs[it.key] = it } }

    private fun searchCar(query: String) {
        carQuery = query.trim().take(80)
        (getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(searchBox?.windowToken, 0)
        searchBox?.clearFocus()
        val app = musicApp
        if (app == null) {
            carResults = Catalog.search(carQuery); carNote = if (carResults.isEmpty()) "검색 결과가 없어요." else null
            remember(carResults); render(); return
        }
        carNote = "${app.label}에서 검색 중…"; render()
        appPlayer(app.id)?.search(carQuery) { songs, error ->
            carResults = songs.orEmpty(); remember(carResults)
            carNote = error ?: if (carResults.isEmpty()) "검색 결과가 없어요." else null
            if (started) renderSoon()
        }
    }

    private fun queue(parent: LinearLayout) {
        parent.addView(label("함께 만든 플레이리스트", 28f, ink, true))
        gap(parent, 8)
        parent.addView(label("${engine.participants.size}개 참여 좌석  ·  남은 ${engine.queue.drop(engine.index).size}곡\n선곡한 좌석과 차례가 함께 저장돼요.", 16f, muted))
        if (engine.participants.isNotEmpty()) {
            gap(parent, 8)
            parent.addView(label("참여 확인 기록\n" + engine.participants.joinToString("\n") { "${it.label}: ${origins[it] ?: "이전 회차 확인"}" }, 13f, muted))
        }
        gap(parent, 18)
        if (engine.queue.isEmpty() || engine.current == null) {
            parent.addView(label(if (engine.queue.isEmpty()) "아직 큐가 비어 있어요.\n우리의 첫 곡을 모아 볼까요?" else "이번 큐를 모두 들었어요.\n다음 라운드를 함께 골라 볼까요?", 23f, lavender))
            gap(parent, 18)
        }
        engine.queue.drop(engine.index).forEachIndexed { i, entry ->
            val song = entry.song
            val item = row().apply { background = rounded(if (i == 0) Color.rgb(53, 43, 70) else panel, 14); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            item.addView(label("%02d".format(Locale.US, i + 1), 23f, Color.parseColor(song.color), true), LinearLayout.LayoutParams(dp(50), -2))
            val info = column()
            info.addView(label(song.title, 21f, ink, true))
            info.addView(label("${entry.seat.label}  ·  라운드 ${entry.round}${song.subtitle.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""}${if (i == 0) "  ·  현재 곡" else ""}", 14f, muted).apply { maxLines = 2 })
            item.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            song.track?.let { item.addView(label(clock(it.durationMs), 15f, muted)) }
            parent.addView(item); gap(parent, 10)
        }
        gap(parent, 8)
        action(parent, "다음 라운드 선곡", true, engine.participants.isNotEmpty() && !engine.needsReview) { stage = 1; selectedSeat = null; render() }
        gap(parent, 10)
        action(parent, "참여 좌석 다시 확인") { stage = 0; draft = engine.participants.toMutableSet(); render() }
        gap(parent, 10)
        action(parent, "파티 새로 시작") {
            AlertDialog.Builder(this).setTitle("새 파티를 시작할까요?").setMessage("현재 큐·참여 좌석·책갈피를 비우고, 좌석 QR 코드도 모두 새로 만들어요.")
                .setNegativeButton("돌아가기", null).setPositiveButton("새 파티 시작") { _, _ -> if (allowed) {
                    player.load(null); engine.reset(); draft.clear(); origins.clear(); baseline.clear(); tokens.clear(); qrCache.clear()
                    stage = 0; selectedSeat = null; resetCarSearch(); save(); render()
                } }.show()
        }
    }

    private fun confirmSeats() {
        player.pause()
        val old = engine.current?.key
        // Only a track with listening progress counts as "the song we were listening to".
        engine.confirmParticipants(draft, keepCurrent = player.position > 0)
        if (old != engine.current?.key) player.load(engine.current)
        tokens.issue(engine.participants)
        origins.clear()
        val now = SystemClock.elapsedRealtime()
        engine.participants.forEach { seat ->
            val signal = signals.getValue(seat)
            origins[seat] = if (signal.value(now) == null) "수동 확인" else if (signal.origin == Origin.SAMPLE) "샘플 참고 · 사람 확인" else "Vehicle SDK 참고 · 사람 확인"
        }
        baseline.clear(); signals.forEach { (id, signal) -> baseline[id] = signal.value(now) }
        stage = if (engine.queue.isNotEmpty()) 2 else 1
        message = "참여 좌석을 확인했어요. 선곡하거나 직접 재생해 주세요."
        save(); player.publish(); render()
    }

    private fun moveTrack(forward: Boolean, completed: Boolean = false) {
        if (!started) return
        val resume = player.playing || completed
        val moved = if (forward) engine.next() else engine.previous()
        if (!moved) { if (!forward && engine.current != null) player.seek(0); return }
        player.load(engine.current)
        message = when {
            engine.current == null -> "큐를 모두 들었어요. 다음 라운드를 골라 주세요."
            engine.needsReview -> "듣던 곡이 끝났어요. 참여 좌석을 다시 확인한 뒤 직접 재생해 주세요."
            else -> "다음 좌석의 차례예요."
        }
        save(); render()
        if (resume && canPlay) player.play()
    }

    private fun checkSignalsForReview() {
        if (engine.needsReview || baseline.isEmpty()) return
        if (signalsChanged(baseline, signals, SystemClock.elapsedRealtime())) {
            engine.markForReview()
            // The current song keeps playing to its end; only the following tracks wait for human review.
            message = if (player.playing) "점유 신호가 바뀌었어요. 지금 곡은 끝까지 듣고, 다음 곡 전에 참여 좌석을 확인해 주세요."
                else "점유 신호가 바뀌었어요. 참여 좌석을 다시 확인해 주세요."
            player.publish()
        }
    }

    // ---- Local web API (called on server threads; all state access hops to the main thread) ----

    private fun <T> onMain(timeoutMs: Long = 8_000, block: ((T) -> Unit) -> Unit): T? {
        val result = CompletableFuture<T>()
        handler.post { try { block { result.complete(it) } } catch (e: Exception) { result.completeExceptionally(e) } }
        return try { result.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
    }

    private fun handleWeb(request: HttpRequest): HttpResponse {
        val busy = HttpResponse.json(503, mapOf("ok" to false, "message" to "차량 앱이 응답하지 않아요. 차량 화면에서 모두의 DJ를 열어 주세요."))
        val gone = mapOf("ok" to false, "message" to "이 QR 코드는 더 이상 유효하지 않아요. 차량 화면의 새 QR을 스캔해 주세요.")
        if (request.method == "GET" && request.path == "/") return HttpResponse.html(JoinPage.HOME)
        if (request.method == "GET" && request.path.startsWith("/s/")) {
            val valid = onMain<Boolean> { it(tokens.seatFor(request.path.removePrefix("/s/")) != null) } ?: return busy
            return if (valid) HttpResponse.html(JoinPage.PAGE) else HttpResponse.text(404, gone.getValue("message") as String)
        }
        val token = request.query["t"]
        return when {
            request.method == "GET" && request.path == "/api/state" -> onMain<HttpResponse> { reply ->
                val seat = tokens.seatFor(token)
                reply(if (seat == null) HttpResponse.json(403, gone) else HttpResponse.json(200, stateOf(seat)))
            } ?: busy
            request.method == "GET" && request.path == "/api/search" -> onMain<HttpResponse>(12_000) { reply ->
                val seat = tokens.seatFor(token) ?: return@onMain reply(HttpResponse.json(403, gone))
                engine.pickBlock(seat, driving)?.let { return@onMain reply(HttpResponse.json(200, mapOf("ok" to false, "message" to it.message))) }
                val q = request.query["q"].orEmpty().trim().take(80)
                val app = musicApp
                fun send(songs: List<Song>, error: String?) {
                    remember(songs)
                    reply(HttpResponse.json(200, mapOf("ok" to (error == null || songs.isNotEmpty()), "message" to (error ?: ""),
                        "items" to songs.map { mapOf("key" to it.key, "title" to it.title, "subtitle" to it.subtitle) })))
                }
                if (app == null) send(Catalog.search(q), null)
                else appPlayer(app.id)?.search(q) { songs, error -> send(songs.orEmpty(), error) } ?: send(emptyList(), "음악 앱을 찾을 수 없어요.")
            } ?: busy
            request.method == "POST" && request.path == "/api/pick" -> onMain<HttpResponse> { reply ->
                val seat = tokens.seatFor(token) ?: return@onMain reply(HttpResponse.json(403, gone))
                val key = request.form["key"].orEmpty()
                val song = shownSongs[key] ?: Catalog.find(key)?.song
                val result = if (song == null) PickResult.INVALID_SONG else engine.pick(seat, song, driving)
                if (result == PickResult.OK) {
                    message = "${seat.label}이(가) 휴대폰으로 「${song!!.title}」을(를) 골랐어요."
                    autoBuildWhileDriving()
                    save()
                    if (started) renderSoon()
                }
                reply(HttpResponse.json(200, mapOf("ok" to (result == PickResult.OK), "message" to result.message)))
            } ?: busy
            else -> HttpResponse.json(404, mapOf("ok" to false, "message" to "없는 주소예요."))
        }
    }

    private fun stateOf(seat: SeatId): Map<String, Any?> {
        val block = if (!started) null else engine.pickBlock(seat, driving)
        val pick = engine.picks[seat]
        return mapOf(
            "seat" to seat.label, "round" to engine.round,
            "participants" to engine.participants.size, "pickedCount" to engine.picks.size,
            "picked" to (pick != null), "pickTitle" to (pick?.title ?: ""),
            "canPick" to (started && block == null),
            "message" to (if (!started) "차량 화면에서 모두의 DJ를 열어 주세요." else block?.message ?: ""),
            "source" to sourceLabel,
        )
    }

    // ---- Signals / playback status ----

    private fun signalText(id: SeatId, detailed: Boolean = false): String {
        val signal = signals.getValue(id)
        val now = SystemClock.elapsedRealtime()
        // Vehicle 2.3.2 maps the underlying occupancy enum to Boolean. Do not equate
        // that Boolean with a person or even with Virtual Console's occupied label.
        val state = when (signal.value(now)) { true -> "참고 신호 ON"; false -> "참고 신호 OFF"; null -> "점유 확인 불가" }
        val from = if (signal.origin == Origin.SAMPLE) "샘플" else "Vehicle SDK"
        val age = if (signal.receivedAt > 0) " · ${(now - signal.receivedAt).coerceAtLeast(0) / 1000}초 전 수신" else " · 수신 없음"
        val stale = signal.receivedAt > 0 && now - signal.receivedAt > SeatSignal.FRESH_MS
        val note = if (detailed) signal.detail else if (signal.value(now) == null) "수동으로 참여 여부를 확인해 주세요" else "참여 여부는 사람이 직접 확인해요"
        return "$state · $from$age${if (stale) " · 만료" else ""}\n$note"
    }

    private fun updateSignals() {
        val old = engine.needsReview
        checkSignalsForReview()
        if (!old && engine.needsReview && allowed) { save(); render(); return }
        signalLabels.forEach { (seat, view) -> view.text = signalText(seat) }
        val now = SystemClock.elapsedRealtime()
        val supported = signals.values.count { it.supported == true }
        val fresh = signals.values.count { it.value(now) != null }
        footer?.text = "${if (sample) "샘플 신호" else "Vehicle SDK"}  ·  점유 지원 ${supported}곳 / 유효 수신 ${fresh}곳  ·  ${if (allowed) "정차 중 선곡" else "운전 중 · 화면 입력 멈춤, 음악·휴대폰 선곡 계속"}  ·  사람·짐·신원을 자동 판정하지 않아요  ·  v$versionName"
    }

    private val versionName by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" } }

    private fun updatePlayback() {
        if (!::player.isInitialized) return
        playbackButton?.text = if (player.playing) "일시정지" else "재생"
        progressText?.text = "${clock(player.position)} / ${if (player.duration > 0) clock(player.duration) else "--:--"}  ·  ${if (player.playing) "함께 듣는 중" else "책갈피 저장"}"
        statusText?.text = message
        if (!seeking) seekBar?.progress = if (player.duration > 0) (player.position.toLong() * 1000 / player.duration).toInt() else 0
    }

    private fun sampleSignals(kind: SampleKind = SampleKind.NORMAL) {
        sampleKind = kind; sampleAt = SystemClock.elapsedRealtime()
        signals.putAll(SampleSignals.build(kind, sampleAt))
        checkSignalsForReview(); updateSignals()
    }

    /** Re-stamps the chosen sample scenario, like the real source's 30 s read; the stale scenario keeps aging. */
    private fun refreshSampleSignals() {
        if (!sample || !SampleSignals.refreshes(sampleKind)) return
        val now = SystemClock.elapsedRealtime()
        if (now - sampleAt < SampleSignals.REFRESH_MS) return
        sampleAt = now
        signals.putAll(SampleSignals.build(sampleKind, now))
    }

    private fun sourceSettings() {
        if (!allowed) return
        val content = column().apply { setPadding(dp(22), dp(12), dp(22), dp(14)) }
        content.addView(label("음악 앱", 18f, ink, true))
        gap(content, 6)
        content.addView(label("차량에 로그인된 음악 앱(지니·멜론 등)이 외부 연결을 허용하면 그 앱으로 검색·재생해요. 계정 정보는 이 앱이 받지 않아요.", 14f, muted))
        gap(content, 8)
        val choose: (MediaApp?) -> Unit = { app ->
            musicApp = app; resetCarSearch(); sourceDialog?.dismiss(); save(); render()
            if (app != null) appPlayer(app.id)?.let { p ->
                p.connect { error -> carNote = error ?: "${app.label} 연결 방식: ${p.mode.text}"; if (allowed) renderSoon() }
            }
        }
        action(content, "앱 내장 음원 6곡${if (musicApp == null) " · 사용 중" else ""}", musicApp == null) { choose(null) }
        mediaApps.forEach { app ->
            gap(content, 8)
            action(content, "${app.label}${if (musicApp?.id == app.id) " · 사용 중" else ""}", musicApp?.id == app.id) { choose(app) }
            val mode = appPlayers[app.id]?.mode?.takeIf { it != MediaAppPlayer.Mode.NONE }?.text
            content.addView(label(listOfNotNull(app.packageName, mode ?: listOfNotNull(
                if (app.service != null) "검색 연결 시도 가능" else null, if (app.searchIntent) "이름 요청 가능" else null).joinToString(" · ").ifEmpty { null }
            ).joinToString("  ·  "), 12f, muted))
        }
        if (mediaApps.isEmpty()) { gap(content, 6); content.addView(label("연결할 수 있는 음악 앱을 찾지 못했어요. 멜론·지니 같은 앱이 차량에 설치돼 있어야 해요.", 13f, muted)) }
        gap(content, 14)
        val access = SessionAccess.granted(this)
        content.addView(label("음악 앱 제어 권한 · ${if (access) "허용됨" else "꺼짐"}", 16f, ink, true))
        gap(content, 4)
        content.addView(label("음악 앱이 외부 검색 연결을 막아도, 이 권한(알림 접근)을 켜면 실행 중인 멜론·지니의 재생을 제어하고 곡이 끝나는 것을 알 수 있어요. 알림 내용은 읽지 않아요.", 13f, muted))
        if (!access) {
            gap(content, 8)
            action(content, "권한 설정 열기") {
                try { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: Exception) { toast("이 차량에서는 설정 화면을 열 수 없어요. 아래 명령으로 허용해 주세요.") }
            }
            gap(content, 4)
            content.addView(label("에뮬레이터: adb shell cmd notification allow_listener $packageName/${SessionAccessService::class.java.name}", 12f, muted).apply { setTextIsSelectable(true) })
        }
        gap(content, 18)
        content.addView(label("휴대폰 선곡 주소", 18f, ink, true))
        gap(content, 6)
        content.addView(label(buildString {
            if (!server.running) append("휴대폰 선곡 연결이 꺼져 있어요(포트 $PORT).")
            else if (engine.participants.isEmpty()) append("참여 좌석을 확정하면 좌석별 주소가 만들어져요.")
            else {
                append("차량 Wi‑Fi: ${addresses.joinToString().ifEmpty { "연결 없음" }}\n")
                engine.participants.forEach { seat -> joinUrl(seat)?.let { append("${seat.label}: $it\n") } }
                append("\nPC 에뮬레이터 테스트: adb forward tcp:$PORT tcp:$PORT 후\n")
                engine.participants.forEach { seat -> joinUrl(seat, "127.0.0.1")?.let { append("${seat.label}: $it\n") } }
            }
        }.trim(), 13f, muted).apply { setTextIsSelectable(true) })
        gap(content, 18)
        content.addView(label("점유 정보 · 체험 설정", 18f, ink, true))
        gap(content, 6)
        content.addView(label("점유 신호와 참가자 확인은 별개예요.\n${if (sample) "현재 샘플 모드" else "현재 실제 Vehicle SDK 모드"}", 16f, muted))
        gap(content)
        action(content, "실제 Seat SDK로 연결", !sample) {
            player.pause(); engine.markForReview(); sample = false; baseline.clear(); source.start()
            sourceDialog?.dismiss(); message = "차량 점유 정보를 다시 확인해 주세요."; save(); render()
        }
        gap(content)
        action(content, "샘플 체험으로 전환", sample) {
            player.pause(); source.stop(); sample = true; baseline.clear(); engine.markForReview(); sampleSignals()
            sourceDialog?.dismiss(); save(); render(); sourceSettings()
        }
        if (sample) {
            listOf("샘플 · 기본 점유" to SampleKind.NORMAL, "샘플 · 승하차 변경" to SampleKind.CHANGED, "샘플 · 신호 없음" to SampleKind.MISSING,
                "샘플 · 오래된 신호" to SampleKind.STALE, "샘플 · 점유 미지원" to SampleKind.UNSUPPORTED).forEach { (title, kind) ->
                gap(content, 8)
                action(content, title) { sampleSignals(kind); sourceDialog?.dismiss(); save(); render() }
            }
            gap(content, 8)
            action(content, "샘플 · 오디오 중단") {
                player.pause(); message = "샘플 오디오 중단 · 직접 재생해야 이어져요."; sourceDialog?.dismiss(); save(); render()
            }
            gap(content, 8)
            action(content, "샘플 · 출발 상황") {
                simulatedDeparture = true; sourceDialog?.dismiss(); message = "샘플 · 출발했어요. 음악은 이어지고 동승자는 휴대폰으로 계속 골라요."
                autoBuildWhileDriving(); save(); player.publish(); render()
            }
        }
        gap(content, 14)
        SeatId.primary.forEach { content.addView(label("${it.label}: ${signalText(it, true)}", 13f, muted)); gap(content, 8) }
        val scroll = ScrollView(this).apply { addView(content) }
        sourceDialog = AlertDialog.Builder(this).setTitle("연결 설정").setView(scroll).setNegativeButton("닫기", null).create()
        sourceDialog?.show(); sourceDialog?.window?.setBackgroundDrawable(rounded(baseColor))
    }

    private fun save() {
        if (!::player.isInitialized || !::store.isInitialized) return
        store.save(engine, player.position, origins, tokens.all, musicApp?.id)
        lastSave = SystemClock.elapsedRealtime()
    }

    companion object { const val PORT = 8765 }
}
