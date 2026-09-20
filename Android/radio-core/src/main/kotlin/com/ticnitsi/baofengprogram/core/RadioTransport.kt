package com.ticnitsi.baofengprogram.core

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

enum class TransportKind { BLUETOOTH, USB }

/** A reliable ordered byte stream. USB serial can implement this without changing a radio driver. */
interface RadioTransport {
    val kind: TransportKind
    val deviceId: String
    suspend fun write(bytes: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun clearInput()
    fun close()
}

class RadioStream(private val transport: RadioTransport) {
    private val pending = ArrayDeque<Byte>()
    suspend fun clear() { pending.clear(); transport.clearInput() }
    suspend fun send(bytes: ByteArray) = transport.write(bytes)
    suspend fun exact(count: Int, timeoutMs: Long = 4000): ByteArray = withTimeout(timeoutMs) {
        require(count in 1..65536)
        val result = ByteArray(count)
        for (i in result.indices) {
            while (pending.isEmpty()) transport.receive().forEach { pending.addLast(it) }
            result[i] = pending.removeFirst()
        }
        result
    }
    suspend fun identity(minimum: Int): ByteArray {
        val result = exact(minimum).toMutableList()
        while (result.size < 32) {
            val next = withTimeoutOrNull(150) { exact(1) } ?: break
            result.add(next[0])
        }
        check(result.size < 32) { "Overlong radio identity response" }
        return result.toByteArray()
    }
}
