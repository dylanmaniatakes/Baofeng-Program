package com.ticnitsi.baofengprogram.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class TransferProgress(val stage: String, val done: Int, val total: Int)

class ColorRadioDriver(
    private val transport: RadioTransport,
    private val progress: (TransferProgress) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val stream = RadioStream(transport)

    suspend fun read(model: RadioModel): RadioImage = mutex.withLock {
        session(model) { identity ->
            val bytes = ByteArray(model.imageSize) { 0xFF.toByte() }
            val total = model.regions.sumOf { it.length } / model.readSize
            var done = 0
            for (region in model.regions) {
                for (address in region.start until region.end step model.readSize) {
                    progress(TransferProgress("Reading radio", done, total))
                    readBlock(address, model.readSize).copyInto(bytes, address)
                    done++
                }
            }
            progress(TransferProgress("Read complete", total, total))
            RadioImage(model.id, identity, bytes)
        }
    }

    fun changedBlocks(original: RadioImage, edited: RadioImage): List<Int> {
        require(original.modelId == edited.modelId && original.identity == edited.identity)
        val size = writeSize(original.model)
        return original.model.regions.flatMap { region ->
            (region.start until region.end step size).filter { address ->
                val length = minOf(size, region.end - address)
                !original.block(address, length).contentEquals(edited.block(address, length))
            }
        }
    }

    suspend fun write(original: RadioImage, edited: RadioImage): Int = mutex.withLock {
        val model = original.model
        val addresses = changedBlocks(original, edited)
        if (addresses.isEmpty()) return@withLock 0
        val blockSize = writeSize(model)
        for (address in addresses) {
            require(model.regions.any { address >= it.start && address + blockSize <= it.end }) {
                "Cannot write an incomplete block at ${address.hexAddress()}"
            }
        }
        session(model) { identity ->
            check(identity == original.identity) { "Radio identity changed. Read this radio before writing." }
            // Check every affected block before the first write, so panel edits or a different radio are not overwritten.
            addresses.forEachIndexed { i, address ->
                progress(TransferProgress("Checking radio", i, addresses.size))
                val actual = readWriteBlock(address, blockSize)
                check(actual.contentEquals(original.block(address, blockSize))) {
                    "Radio data changed at ${address.hexAddress()}. Read again before writing."
                }
            }
            addresses.forEachIndexed { i, address ->
                progress(TransferProgress("Writing radio", i, addresses.size))
                val data = edited.block(address, blockSize)
                stream.send(frame('W', address, blockSize) + ColorCipher.transform(data))
                expectAck("writing ${address.hexAddress()}")
                log("Wrote ${address.hexAddress()} ($blockSize bytes)")
            }
            addresses.forEachIndexed { i, address ->
                progress(TransferProgress("Verifying radio", i, addresses.size))
                check(readWriteBlock(address, blockSize).contentEquals(edited.block(address, blockSize))) {
                    "Read-back verification failed at ${address.hexAddress()}. Read the radio again."
                }
            }
            progress(TransferProgress("Write verified", addresses.size, addresses.size))
            addresses.size
        }
    }

    private fun writeSize(model: RadioModel) = if (transport.kind == TransportKind.BLUETOOTH) model.bluetoothWriteSize else 64

    private suspend fun readWriteBlock(address: Int, size: Int): ByteArray {
        val result = ByteArray(size)
        for (offset in 0 until size step 64) readBlock(address + offset, 64).copyInto(result, offset)
        return result
    }

    private suspend fun readBlock(address: Int, size: Int): ByteArray {
        stream.send(frame('R', address, size))
        val header = stream.exact(4)
        check(header.contentEquals(frame('R', address, size))) {
            "Unexpected reply at ${address.hexAddress()}: ${header.hex()}"
        }
        val bytes = stream.exact(size)
        return ColorCipher.transform(bytes)
    }

    private suspend fun <T> session(model: RadioModel, body: suspend (String) -> T): T {
        try {
            progress(TransferProgress("Identifying radio", 0, 0))
            stream.clear()
            stream.send(model.handshake.toByteArray(Charsets.US_ASCII))
            expectAck("starting programming")
            stream.send(byteArrayOf(0x46))
            // Band limits are variable data, not a fixed prefix to match against an example in JSON.
            log("Band reply: ${stream.exact(16).hex()}")
            stream.send(byteArrayOf(0x4D))
            val identity = stream.identity(model.identityPrefix.length)
            check(identity.toString(Charsets.US_ASCII).startsWith(model.identityPrefix)) {
                "Selected ${model.name}, but radio returned ${identity.hex()}"
            }
            log("Identity: ${identity.hex()}")
            stream.send(byteArrayOf(0x53,0x45,0x4E,0x44,0x21,5,13,1,1,1,4,17,8,5,13,13,1,17,15,9,18,9,16,4,0))
            expectAck("opening programming session")
            return body(identity.hex())
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(1500) {
                    runCatching { stream.send(byteArrayOf(0x45)) }.onFailure { log("Session exit: ${it.message}") }
                }
            }
        }
    }

    private suspend fun expectAck(action: String) {
        val ack = stream.exact(1)
        check(ack[0] == 6.toByte()) { "Radio rejected $action: ${ack.hex()}" }
    }

    private fun frame(command: Char, address: Int, size: Int) = byteArrayOf(command.code.toByte(), (address ushr 8).toByte(), address.toByte(), size.toByte())
}

object ColorCipher {
    private val key = intArrayOf(0x43, 0x4F, 0x20, 0x37)
    fun transform(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { index ->
        val value = bytes[index].toInt() and 255
        val k = key[index % 4]
        if (k == 32 || value == 0 || value == 255 || value == k || value == (k xor 255)) value.toByte()
        else (value xor k).toByte()
    }
}
