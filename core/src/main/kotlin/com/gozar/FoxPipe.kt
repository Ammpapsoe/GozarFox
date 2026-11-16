package com.gozar

/**
 * One byte-stream pipe for a tunnel leg (a TUN flow, a local proxy leg, a
 * relay connection). Implemented by the Netty/OkHttp-backed pipes, the direct
 * socket pipes, and the DNS pipe tests.
 */
interface FoxPipe {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun finishWrite() = Unit
    fun close()
}
