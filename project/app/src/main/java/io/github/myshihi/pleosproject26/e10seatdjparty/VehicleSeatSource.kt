package io.github.myshihi.pleosproject26.e10seatdjparty

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Read supported occupancy areas only; never writes seats or infers people from occupancy. */
class VehicleSeatSource(private val context: Context, private val changed: (SeatId, SeatSignal) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var api: Seat? = null
    private var generation = 0
    private val listeners = mutableMapOf<SeatId, SeatOccupancyListener>()
    private val versions = mutableMapOf<SeatId, Int>()
    private val reads = mutableMapOf<SeatId, Int>()
    private val received = mutableSetOf<SeatId>()
    private fun area(id: SeatId) = SeatArea(SeatValue.valueOf(id.sdkArea))
    private fun post(token: Int, action: () -> Unit) { handler.post { if (token == generation) action() } }

    fun start() {
        stop()
        val token = generation
        SeatId.entries.forEach { changed(it, SeatSignal(detail = "지원 영역 확인 중")) }
        handler.postDelayed({
            if (token == generation) SeatId.entries.filter { it !in received }.forEach {
                changed(it, SeatSignal(detail = "응답 시간 초과 · 수동 확인 가능"))
            }
        }, 12_000)
        try {
            vehicle = Vehicle(context.applicationContext).also { it.initialize() }
            val seat = vehicle!!.getSeat()
            api = seat
            seat.checkSeatOccupancyCapabilities(onComplete = { config, areas -> post(token) {
                val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                SeatId.entries.forEach { id ->
                    if (readable && area(id) in areas) {
                        changed(id, SeatSignal(supported = true, detail = "점유 조회 중 · ${id.sdkArea}"))
                        subscribe(seat, id, token)
                        read(seat, id, token)
                    } else {
                        received.add(id)
                        changed(id, SeatSignal(supported = false, detail = "점유 미지원 · 수동 확인"))
                    }
                }
            } }, onFailed = { post(token) { failAll("지원 영역 조회 실패 · 수동 확인 가능") } })
        } catch (_: Exception) { post(token) { failAll("차량 연결 실패 · 다시 연결 또는 수동 확인") } }
    }

    private fun failAll(detail: String) {
        SeatId.entries.forEach { received.add(it); changed(it, SeatSignal(detail = detail)) }
    }
    private fun show(id: SeatId, value: Boolean?) {
        received.add(id)
        changed(id, SeatSignal(value, true, Origin.VEHICLE, SystemClock.elapsedRealtime(),
            if (value == null) "점유 값 없음 · ${id.sdkArea}" else "${id.sdkArea} · raw=$value"))
    }

    private fun read(seat: Seat, id: SeatId, token: Int) {
        val request = (reads[id] ?: 0) + 1
        reads[id] = request
        val eventVersion = versions[id] ?: 0
        try {
            seat.getCurrentSeatOccupancy(area(id), onComplete = { value -> post(token) {
                if (reads[id] == request && (versions[id] ?: 0) == eventVersion) show(id, value)
            } }, onFailed = { post(token) {
                if (reads[id] == request && (versions[id] ?: 0) == eventVersion) {
                    received.add(id)
                    changed(id, SeatSignal(supported = true, detail = "조회 실패 · 수동 확인 가능"))
                }
            } })
        } catch (_: Exception) {
            received.add(id)
            changed(id, SeatSignal(supported = true, detail = "조회 실패 · 수동 확인 가능"))
        }
        handler.postDelayed({ if (token == generation) read(seat, id, token) }, 30_000)
    }

    private fun subscribe(seat: Seat, id: SeatId, token: Int) {
        if (id in listeners) return
        val callback = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) = post(token) {
                versions[id] = (versions[id] ?: 0) + 1
                show(id, occupancy)
            }
            override fun onFailed(e: Exception) = post(token) {
                received.add(id)
                changed(id, SeatSignal(supported = true, detail = "점유 구독 실패 · 수동 확인 가능"))
            }
        }
        try { seat.registerSeatOccupancy(area(id), callback); listeners[id] = callback }
        catch (_: Exception) { changed(id, SeatSignal(supported = true, detail = "구독 실패 · 주기 조회 중")) }
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        listeners.forEach { (id, callback) -> try { api?.unregisterSeatOccupancy(area(id), callback) } catch (_: Exception) { } }
        try { vehicle?.release() } catch (_: Exception) { }
        listeners.clear(); reads.clear(); versions.clear(); received.clear()
        vehicle = null; api = null
    }
}
