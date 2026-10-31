package com.cihmine.carvoicegame.vehicle

import com.cihmine.carvoicegame.core.PlayMode
import com.cihmine.carvoicegame.core.port.VehicleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VehicleState(val label: String) {
    PARKED("주차"),
    MOVING("주행"),

    /** 값 없음·오래된 값. 주행으로 보고 제한을 건다. */
    UNKNOWN("상태 불명"),
}

/** 시연 패널에서 바꾸는 모의 차량 상태. 실제 차량 신호가 아님을 화면에 표시한다. */
class MockVehicleStatus : VehicleStatus {
    private val _state = MutableStateFlow(VehicleState.PARKED)
    val state: StateFlow<VehicleState> = _state

    fun set(value: VehicleState) {
        _state.value = value
    }

    override fun currentMode(): PlayMode = if (_state.value == VehicleState.PARKED) PlayMode.PARKED else PlayMode.DRIVING

    override fun isUxRestricted(): Boolean = _state.value != VehicleState.PARKED
}
