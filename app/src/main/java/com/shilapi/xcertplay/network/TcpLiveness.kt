package com.shilapi.xcertplay.network

import java.net.Socket

/** The portable keepalive option available on API 16; never time out a legitimately idle screen. */
internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        socket.tcpNoDelay = true
        diagnostic("TCP keepalive enabled")
    }
}
