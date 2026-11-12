package com.titanvps.app

import com.titanvps.app.ui.ConnectFeedback
import com.titanvps.app.ui.ConnectFeedback.Asked
import com.titanvps.app.ui.ConnectFeedback.Outcome
import com.titanvps.app.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectFeedbackTest {

    private val on = VpnState.Connected("🇫🇮 Финляндия", since = 1L)

    @Test
    fun nothingAskedNothingFelt() {
        val f = ConnectFeedback()
        // A reconnect by itself, Не отключать VPN, the tile, the widget: never asked here.
        assertNull(f.onState(VpnState.Connecting, 0))
        assertNull(f.onState(on, 0))
        assertNull(f.onState(VpnState.Error("Не удалось подключиться"), 0))
        assertNull(f.onState(VpnState.Disconnected, 0))
    }

    @Test
    fun theUsersConnectIsFeltOnceWhenConnected() {
        val f = ConnectFeedback()
        f.expect(Asked.CONNECT, 1_000)
        assertNull(f.onState(VpnState.Connecting, 1_100))
        assertEquals(Outcome.CONNECTED, f.onState(on, 3_000))
        // Its later reconnects are the VPN's own.
        assertNull(f.onState(VpnState.Connecting, 9_000))
        assertNull(f.onState(on, 9_500))
    }

    @Test
    fun aFailedConnectGoesOffOnTheWayToItsError() {
        val f = ConnectFeedback()
        f.expect(Asked.CONNECT, 0)
        assertNull(f.onState(VpnState.Connecting, 10))
        assertNull(f.onState(VpnState.Disconnecting, 20))
        assertNull(f.onState(VpnState.Disconnected, 30))
        assertEquals(Outcome.FAILED, f.onState(VpnState.Error("Не удалось подключиться"), 40))
        assertNull(f.onState(VpnState.Error("Другая ошибка"), 50))
    }

    @Test
    fun theUsersOffIsFeltWhenOff() {
        val f = ConnectFeedback()
        f.expect(Asked.DISCONNECT, 0)
        assertNull(f.onState(VpnState.Disconnecting, 10))
        assertEquals(Outcome.DISCONNECTED, f.onState(VpnState.Disconnected, 200))
        assertNull(f.onState(VpnState.Disconnected, 300))
    }

    @Test
    fun onAgainRightAfterOffWaitsForTheConnect() {
        val f = ConnectFeedback()
        f.expect(Asked.DISCONNECT, 0)
        f.expect(Asked.CONNECT, 50)
        assertNull(f.onState(VpnState.Disconnected, 100))
        assertEquals(Outcome.CONNECTED, f.onState(on, 2_000))
    }

    @Test
    fun leavingTheScreenOrADeniedPermissionForgetsIt() {
        val f = ConnectFeedback()
        f.expect(Asked.CONNECT, 0)
        f.forget()
        assertNull(f.onState(on, 1_000))
    }

    @Test
    fun tooLateIsNotItsOutcome() {
        val f = ConnectFeedback()
        f.expect(Asked.CONNECT, 0)
        assertNull(f.onState(on, 60_001))
        f.expect(Asked.DISCONNECT, 0)
        assertNull(f.onState(VpnState.Disconnected, 15_001))
        // And it's gone after that.
        assertNull(f.onState(VpnState.Disconnected, 15_002))
    }
}
