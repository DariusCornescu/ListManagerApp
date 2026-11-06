package com.darius.listmanager.data.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectingSocketTest {

    private class FakeSocket(val token: String)

    private class Retry(val delayMillis: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private var clock = 0L
    private val opened = mutableListOf<FakeSocket>()
    private val closed = mutableListOf<FakeSocket>()
    private val retries = mutableListOf<Retry>()
    private var state: WebSocketState = WebSocketState.Disconnected
    private var rejectedCalls = 0

    private val socket = ReconnectingSocket<FakeSocket>(
        open = { token -> FakeSocket(token).also(opened::add) },
        close = { closed += it },
        schedule = { delayMillis, action ->
            val retry = Retry(delayMillis, action).also(retries::add)
            val cancel: () -> Unit = { retry.cancelled = true }
            cancel
        },
        now = { clock },
        setState = { state = it },
        onTokenRejected = { rejectedCalls++ },
    )

    private val failure = WebSocketState.Error("Software caused connection abort")

    private fun pendingRetries() = retries.filterNot { it.cancelled }

    /** Fires the only retry still waiting, as the timer would. */
    private fun fireRetry() {
        val retry = pendingRetries().single()
        retries.remove(retry)
        retry.action()
    }

    private fun connectedInForeground(token: String = "token"): FakeSocket {
        socket.setForeground(true)
        socket.connect(token)
        return opened.last().also(socket::onOpened)
    }

    // ===== Retry =====

    @Test fun drop_retriesWithTheToken() {
        val first = connectedInForeground()

        socket.onDropped(first, failure)

        assertEquals(failure, state)
        assertEquals(1_000L, pendingRetries().single().delayMillis)
        fireRetry()
        assertEquals(WebSocketState.Connecting, state)
        assertEquals(listOf("token", "token"), opened.map { it.token })
        socket.onOpened(opened.last())
        assertEquals(WebSocketState.Connected, state)
    }

    @Test fun closeFromTheServer_retriesLikeAFailure() {
        val first = connectedInForeground()

        socket.onDropped(first, WebSocketState.Disconnected)

        assertEquals(WebSocketState.Disconnected, state)
        assertEquals(1, pendingRetries().size)
    }

    @Test fun repeatedFailures_backOffUpToTheCap() {
        socket.setForeground(true)
        socket.connect("token")

        val delays = (1..8).map {
            socket.onDropped(opened.last(), failure)
            val delay = pendingRetries().single().delayMillis
            fireRetry()
            delay
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test fun shortConnections_keepBackingOff() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        fireRetry()

        // Accepted, then lost straight away: not a recovery.
        socket.onOpened(opened.last())
        clock += ReconnectingSocket.STABLE_CONNECTION_MILLIS - 1
        socket.onDropped(opened.last(), failure)

        assertEquals(2_000L, pendingRetries().single().delayMillis)
    }

    @Test fun lastingConnection_resetsTheBackoff() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        fireRetry()

        socket.onOpened(opened.last())
        clock += ReconnectingSocket.STABLE_CONNECTION_MILLIS
        socket.onDropped(opened.last(), failure)

        assertEquals(1_000L, pendingRetries().single().delayMillis)
    }

    // ===== Foreground =====

    @Test fun dropInTheBackground_doesNotRetry() {
        val first = connectedInForeground()
        socket.setForeground(false)

        socket.onDropped(first, failure)

        assertTrue(pendingRetries().isEmpty())
        assertEquals(1, opened.size)
    }

    @Test fun goingToTheBackground_cancelsAWaitingRetry() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)

        socket.setForeground(false)

        assertTrue(pendingRetries().isEmpty())
    }

    @Test fun returningToTheForeground_reconnectsAtOnceWithAFreshBackoff() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        fireRetry()
        socket.onDropped(opened.last(), failure)
        socket.setForeground(false)

        socket.setForeground(true)

        assertEquals(3, opened.size)
        assertTrue(pendingRetries().isEmpty())
        socket.onDropped(opened.last(), failure)
        assertEquals(1_000L, pendingRetries().single().delayMillis)
    }

    @Test fun returningToTheForeground_leavesALiveSocketAlone() {
        connectedInForeground()
        socket.setForeground(false)

        socket.setForeground(true)

        assertEquals(1, opened.size)
        assertTrue(closed.isEmpty())
        assertEquals(WebSocketState.Connected, state)
    }

    @Test fun connectInTheBackground_opensOnlyOnReturn() {
        socket.connect("token")
        assertTrue(opened.isEmpty())
        assertEquals(WebSocketState.Disconnected, state)

        socket.setForeground(true)

        assertEquals(listOf("token"), opened.map { it.token })
    }

    @Test fun foregroundWithoutALogin_opensNothing() {
        socket.setForeground(true)

        assertTrue(opened.isEmpty())
        assertTrue(pendingRetries().isEmpty())
    }

    // ===== Stop =====

    @Test fun disconnect_closesAndCancelsAWaitingRetry() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        fireRetry()
        val second = opened.last()

        socket.disconnect()

        assertEquals(listOf(second), closed)
        assertEquals(WebSocketState.Disconnected, state)
        assertTrue(pendingRetries().isEmpty())
    }

    @Test fun disconnect_thenLateClose_doesNotRetry() {
        val first = connectedInForeground()

        socket.disconnect()
        socket.onDropped(first, WebSocketState.Disconnected)

        assertTrue(pendingRetries().isEmpty())
        assertEquals(1, opened.size)
    }

    @Test fun disconnect_staysDownEvenOnReturningToTheForeground() {
        connectedInForeground()
        socket.disconnect()

        socket.setForeground(false)
        socket.setForeground(true)

        assertEquals(1, opened.size)
    }

    @Test fun retryFiringAfterDisconnect_opensNothing() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        val racing = pendingRetries().single()

        socket.disconnect()
        racing.action()

        assertEquals(1, opened.size)
    }

    @Test fun connectAfterDisconnect_worksAgain() {
        connectedInForeground("old")
        socket.disconnect()

        socket.connect("new")

        assertEquals("new", opened.last().token)
    }

    // ===== No duplicates =====

    @Test fun connectWhileConnecting_withTheSameToken_opensNothing() {
        socket.setForeground(true)
        socket.connect("token")

        socket.connect("token")

        assertEquals(1, opened.size)
    }

    @Test fun connectWhileConnected_opensNothing() {
        connectedInForeground()

        socket.connect("token")

        assertEquals(1, opened.size)
        assertEquals(WebSocketState.Connected, state)
    }

    @Test fun connectDuringTheWait_opensOnceAndCancelsTheRetry() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)

        socket.connect("token")

        assertEquals(2, opened.size)
        assertTrue(pendingRetries().isEmpty())
    }

    @Test fun retryFiringWhileASocketIsOpen_opensNothing() {
        val first = connectedInForeground()
        socket.onDropped(first, failure)
        val racing = pendingRetries().single()
        socket.connect("token")

        racing.action()

        assertEquals(2, opened.size)
    }

    @Test fun lateEventsFromAnOldSocket_areIgnored() {
        val old = connectedInForeground()
        socket.onDropped(old, failure)
        fireRetry()
        val current = opened.last()
        socket.onOpened(current)

        socket.onDropped(old, WebSocketState.Disconnected)
        socket.onOpened(old)

        assertEquals(WebSocketState.Connected, state)
        assertTrue(pendingRetries().isEmpty())
        assertTrue(socket.isCurrent(current))
        assertFalse(socket.isCurrent(old))
    }

    // ===== Tokens =====

    @Test fun tokenRefreshedWhileConnected_keepsTheSocketAndIsUsedNextTime() {
        val first = connectedInForeground("old")

        socket.connect("new")
        assertEquals(1, opened.size)
        assertTrue(closed.isEmpty())

        socket.onDropped(first, failure)
        fireRetry()
        assertEquals("new", opened.last().token)
    }

    @Test fun tokenRefreshedWhileConnecting_replacesTheSocket() {
        socket.setForeground(true)
        socket.connect("old")
        val refused = opened.last()

        socket.connect("new")

        assertEquals(listOf(refused), closed)
        assertEquals(listOf("old", "new"), opened.map { it.token })
        assertEquals(WebSocketState.Connecting, state)

        // The old handshake failing now must not disturb the new one.
        socket.onDropped(refused, failure, tokenRejected = true)
        assertEquals(WebSocketState.Connecting, state)
        assertEquals(0, rejectedCalls)
        assertTrue(pendingRetries().isEmpty())
    }

    @Test fun tokenRefreshedWhileConnectingInTheBackground_isNotLeftConnecting() {
        socket.setForeground(true)
        socket.connect("old")
        socket.setForeground(false)

        socket.connect("new")

        assertEquals(WebSocketState.Disconnected, state)
        assertEquals(1, opened.size)
    }

    @Test fun rejectedToken_asksForARefreshAndKeepsRetrying() {
        socket.setForeground(true)
        socket.connect("expired")

        socket.onDropped(opened.last(), failure, tokenRejected = true)

        assertEquals(1, rejectedCalls)
        assertEquals(1, pendingRetries().size)
    }

    @Test fun ordinaryDrop_doesNotAskForARefresh() {
        val first = connectedInForeground()

        socket.onDropped(first, failure)

        assertEquals(0, rejectedCalls)
    }

    @Test fun refreshCompletingInsideTheRejection_endsWithOneSocketAndNoRetry() {
        // The refresh can only land through connect(), whichever thread it runs on.
        lateinit var reconnecting: ReconnectingSocket<FakeSocket>
        reconnecting = ReconnectingSocket(
            open = { token -> FakeSocket(token).also(opened::add) },
            close = { closed += it },
            schedule = { delayMillis, action ->
                val retry = Retry(delayMillis, action).also(retries::add)
                val cancel: () -> Unit = { retry.cancelled = true }
                cancel
            },
            now = { clock },
            setState = { state = it },
            onTokenRejected = { reconnecting.connect("fresh") },
        )
        reconnecting.setForeground(true)
        reconnecting.connect("expired")

        reconnecting.onDropped(opened.last(), failure, tokenRejected = true)

        assertEquals(listOf("expired", "fresh"), opened.map { it.token })
        assertTrue(pendingRetries().isEmpty())
    }

    @Test fun rejectedHandshakeCodes() {
        assertTrue(isRejectedHandshake(401))
        assertTrue(isRejectedHandshake(403))
        assertFalse(isRejectedHandshake(502))
        assertFalse(isRejectedHandshake(null))
    }

    @Test fun rejectedCloseCodes() {
        assertTrue(isRejectedClose(4001))
        assertTrue(isRejectedClose(4002))
        assertTrue(isRejectedClose(4003))
        assertFalse(isRejectedClose(1000))
        assertFalse(isRejectedClose(1006))
    }
}
