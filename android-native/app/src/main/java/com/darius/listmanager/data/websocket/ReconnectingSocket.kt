package com.darius.listmanager.data.websocket

/** HTTP status of a handshake the server refused, which a new token may fix. */
internal fun isRejectedHandshake(httpCode: Int?): Boolean = httpCode == 401 || httpCode == 403

/** Close codes /ws uses for a bad or revoked token. */
internal fun isRejectedClose(code: Int): Boolean = code in 4001..4003

/**
 * Decides when the live socket opens. While logged in and in the foreground a lost
 * socket is retried with a capped backoff, and there is never more than one socket.
 *
 * OkHttp calls back on its own threads, so every entry point is synchronized.
 */
internal class ReconnectingSocket<S : Any>(
    private val open: (token: String) -> S,
    private val close: (S) -> Unit,
    /** Runs the action after the delay and returns a function that cancels it. */
    private val schedule: (delayMillis: Long, action: () -> Unit) -> () -> Unit,
    private val now: () -> Long,
    private val setState: (WebSocketState) -> Unit,
    /** The server refused the token, so the app should try to refresh it. */
    private val onTokenRejected: () -> Unit,
) {
    private var token: String? = null
    private var foreground = false
    private var socket: S? = null
    private var openedAt: Long? = null
    private var attempts = 0
    private var cancelRetry: (() -> Unit)? = null

    /** The token is always kept, because the next open must not use an expired one. */
    @Synchronized
    fun connect(newToken: String) {
        val tokenChanged = newToken != token
        token = newToken
        val current = socket
        when {
            current == null -> {
                stopWaiting()
                openIfWanted()
            }
            // A handshake with the old token is likely to be refused, so don't wait for it.
            openedAt == null && tokenChanged -> {
                socket = null
                close(current)
                setState(WebSocketState.Disconnected)
                openIfWanted()
            }
        }
    }

    /** Stops for good: nothing opens again until the next [connect]. */
    @Synchronized
    fun disconnect() {
        token = null
        attempts = 0
        stopWaiting()
        socket?.let(close)
        socket = null
        openedAt = null
        setState(WebSocketState.Disconnected)
    }

    /** An open socket is left alone in the background, it just isn't retried if it drops. */
    @Synchronized
    fun setForeground(value: Boolean) {
        foreground = value
        stopWaiting()
        if (value) {
            attempts = 0
            openIfWanted()
        }
    }

    @Synchronized
    fun onOpened(opened: S) {
        if (opened !== socket) return
        openedAt = now()
        setState(WebSocketState.Connected)
    }

    @Synchronized
    fun onDropped(dropped: S, state: WebSocketState, tokenRejected: Boolean = false) {
        if (dropped !== socket) return
        // Only a lasting connection resets the backoff. A server that accepts and
        // closes straight away must not be retried every second.
        if (openedAt?.let { now() - it >= STABLE_CONNECTION_MILLIS } == true) attempts = 0
        socket = null
        openedAt = null
        setState(state)
        stopWaiting()
        if (token != null && foreground) {
            cancelRetry = schedule(retryDelayMillis(attempts++)) { retry() }
        }
        if (tokenRejected) onTokenRejected()
    }

    @Synchronized
    fun isCurrent(candidate: S): Boolean = candidate === socket

    @Synchronized
    fun current(): S? = socket

    @Synchronized
    private fun retry() {
        cancelRetry = null
        openIfWanted()
    }

    private fun openIfWanted() {
        val currentToken = token ?: return
        if (!foreground || socket != null) return
        openedAt = null
        setState(WebSocketState.Connecting)
        socket = open(currentToken)
    }

    private fun stopWaiting() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    companion object {
        const val FIRST_RETRY_MILLIS = 1_000L
        const val MAX_RETRY_MILLIS = 30_000L
        const val STABLE_CONNECTION_MILLIS = 30_000L

        /** 1, 2, 4, 8, 16, then 30 seconds. */
        fun retryDelayMillis(attempt: Int): Long =
            (FIRST_RETRY_MILLIS shl attempt.coerceAtMost(5)).coerceAtMost(MAX_RETRY_MILLIS)
    }
}
