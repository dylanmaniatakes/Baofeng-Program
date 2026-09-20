package com.ticnitsi.baofengprogram.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RadioCoreTest {
    private fun blank() = RadioImage(RadioModels.mini.id, "35 52 4D 49 4E 49", ByteArray(RadioModels.mini.imageSize) { -1 })
    private fun programmed(): RadioImage = ChannelCodec.patch(blank(), MemoryChannel(1, "LOCAL", 146520000, 146520000))

    @Test fun frequencyParsingIsExactAndLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(146520000L, Frequencies.parse("146.52000"))
            assertEquals("146.52000", Frequencies.format(146520000))
            assertThrows(IllegalArgumentException::class.java) { Frequencies.parse("146.520001") }
            assertThrows(IllegalArgumentException::class.java) { Frequencies.parse("146,520") }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun emptyIsNotFactoryData() { assertTrue(ChannelCodec.readAll(blank()).all { it.empty }) }

    @Test fun channelFlagsAreMsbFirstAndUnknownBytesSurvive() {
        val bytes = programmed().bytes()
        bytes[14] = 0xA0.toByte()
        bytes[15] = 0x86.toByte()
        bytes[16] = 0x31
        val image = programmed().updated(bytes)
        val channel = ChannelCodec.read(image, 1)
        assertFalse(channel.narrow)
        assertTrue(channel.scan)
        assertFalse(channel.busyLock)
        assertArrayEquals(bytes, ChannelCodec.patch(image, channel).bytes())
        val changed = ChannelCodec.patch(image, channel.copy(narrow = true, busyLock = true, receiveDtmf = 2, hopping = true, power = 1))
        assertEquals(0xEF, changed.bytes().u8(15))
        assertEquals(0xA1, changed.bytes().u8(14))
        assertEquals(0x31, changed.bytes().u8(16))
        assertArrayEquals(image.block(32, 96), changed.block(32, 96))
    }

    @Test fun receiveOnlyStaysReceiveOnly() {
        val image = ChannelCodec.patch(programmed(), ChannelCodec.read(programmed(), 1).copy(transmitHz = null))
        assertArrayEquals(ByteArray(4) { -1 }, image.block(4, 4))
        assertNull(ChannelCodec.read(image, 1).transmitHz)
    }

    @Test fun namesAndUnknownToneValuesSurviveOtherEdits() {
        val image = programmed()
        val bytes = image.bytes()
        "测试".toByteArray(charset("GB2312")).copyInto(bytes, 20)
        bytes.putLe16(8, 0x4242)
        val original = image.updated(bytes)
        val changed = ChannelCodec.patch(original, ChannelCodec.read(original, 1).copy(power = 1))
        assertArrayEquals(original.block(20, 12), changed.block(20, 12))
        assertEquals(0x4242, ChannelCodec.read(changed, 1).receiveTone)
    }

    @Test fun tonesUseDcsIndicesIncluding645() {
        assertEquals(105, ToneCodes.dcs.size)
        assertEquals("DCS 023 N", ToneCodes.label(1))
        assertEquals("DCS 023 I", ToneCodes.label(106))
        assertEquals("DCS 754 I", ToneCodes.label(210))
        assertEquals("CTCSS 100.0 Hz", ToneCodes.label(1000))
        val image = ChannelCodec.patch(programmed(), ChannelCodec.read(programmed(), 1).copy(receiveTone = 106, transmitTone = 1000))
        assertArrayEquals(byteArrayOf(106, 0, 0xE8.toByte(), 3), image.block(8, 4))
    }

    @Test fun settingsUseExplicitAddressesAndPreserveNibbles() {
        val image = blank()
        val changed = SettingsCodec.patch(image, "modeA", 1)
        assertEquals(0xF1, changed.bytes().u8(0x901A))
        assertEquals(0xFF, changed.bytes().u8(0x901B))
        val language = SettingsCodec.patch(changed, "language", 0)
        assertEquals(0, language.bytes().u8(0x9008))
        assertEquals(0xFF, language.bytes().u8(0x9007))
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.patch(image, "squelch", 200) }
    }

    @Test fun backupRoundtripAndCorruptionCheck() {
        val encoded = BackupCodec.encode(programmed())
        assertArrayEquals(programmed().bytes(), BackupCodec.decode(encoded).bytes())
        encoded[100] = (encoded[100].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded) }
    }

    @Test fun cipherIsInvolutionForAllByteValuesAndPositions() {
        for (position in 0..3) for (value in 0..255) {
            val data = ByteArray(4).also { it[position] = value.toByte() }
            assertArrayEquals(data, ColorCipher.transform(ColorCipher.transform(data)))
        }
    }

    @Test fun streamPreservesCoalescedAndFragmentedReplies() = runBlocking {
        val fake = FakeRadio(blank())
        fake.replies.send(byteArrayOf(1, 2, 3, 4, 5))
        fake.replies.send(byteArrayOf(6))
        val stream = RadioStream(fake)
        assertArrayEquals(byteArrayOf(1, 2), stream.exact(2))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), stream.exact(4))
    }

    @Test fun fullReadAcceptsVariableBandsAndFragmentedData() = runBlocking {
        val fake = FakeRadio(programmed())
        val read = ColorRadioDriver(fake).read(RadioModels.mini)
        assertArrayEquals(programmed().bytes(), read.bytes())
        assertEquals(0x45, fake.commands.last().u8(0))
        assertFalse(fake.commands.any { it.size == 1 && it[0] == 6.toByte() })
    }

    @Test fun miniWrites128BytesChecksBeforeAndVerifiesAfter() = runBlocking {
        val fake = FakeRadio(programmed())
        val driver = ColorRadioDriver(fake)
        val before = driver.read(RadioModels.mini)
        val after = ChannelCodec.patch(before, ChannelCodec.read(before, 1).copy(name = "CHANGED"))
        assertEquals(1, driver.write(before, after))
        val writes = fake.commands.filter { it[0] == 'W'.code.toByte() }
        assertEquals(1, writes.size)
        assertEquals(132, writes.single().size)
        assertEquals(128, writes.single().u8(3))
        assertArrayEquals(after.bytes(), fake.memory)
        assertArrayEquals(before.block(32, 96), fake.memory.copyOfRange(32, 128))
    }

    @Test fun preflightConflictPreventsAllWrites() = runBlocking {
        val fake = FakeRadio(programmed())
        val driver = ColorRadioDriver(fake)
        val before = driver.read(RadioModels.mini)
        val after = ChannelCodec.patch(before, ChannelCodec.read(before, 1).copy(name = "CHANGED"))
        fake.memory[16] = 55
        val result = runCatching { driver.write(before, after) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Radio data changed"))
        assertFalse(fake.commands.any { it[0] == 'W'.code.toByte() })
    }

    @Test fun wrongIdentityPreventsReadingMemory() = runBlocking {
        val fake = FakeRadio(blank(), identity = "WRONG!")
        assertTrue(runCatching { ColorRadioDriver(fake).read(RadioModels.mini) }.isFailure)
        assertFalse(fake.commands.any { it[0] == 'R'.code.toByte() })
    }

    @Test fun wrongReadAddressFailsInsteadOfPublishingPartialImage() = runBlocking {
        val fake = FakeRadio(blank(), badHeader = true)
        assertTrue(runCatching { ColorRadioDriver(fake).read(RadioModels.mini) }.isFailure)
    }

    @Test fun rejectedWriteAndCorruptReadbackAreFailures() = runBlocking {
        for (reject in listOf(true, false)) {
            val fake = FakeRadio(programmed(), rejectWrites = reject, corruptWrites = !reject)
            val driver = ColorRadioDriver(fake)
            val before = driver.read(RadioModels.mini)
            val after = ChannelCodec.patch(before, ChannelCodec.read(before, 1).copy(name = "CHANGED"))
            assertTrue(runCatching { driver.write(before, after) }.isFailure)
        }
    }

    private class FakeRadio(image: RadioImage, val identity: String = "5RMINI", val badHeader: Boolean = false,
        val rejectWrites: Boolean = false, val corruptWrites: Boolean = false) : RadioTransport {
        override val kind = TransportKind.BLUETOOTH
        override val deviceId = "test"
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        val commands = mutableListOf<ByteArray>()
        val memory = image.bytes()
        override suspend fun receive() = replies.receive()
        override suspend fun clearInput() { while (replies.tryReceive().isSuccess) {} }
        override fun close() { replies.close() }
        override suspend fun write(bytes: ByteArray) {
            commands += bytes.copyOf()
            val reply = when (bytes.u8(0)) {
                'P'.code, 'S'.code -> byteArrayOf(6)
                'F'.code -> ByteArray(16) { (it * 13).toByte() }
                'M'.code -> identity.toByteArray()
                'E'.code -> byteArrayOf()
                'R'.code -> {
                    val address = (bytes.u8(1) shl 8) or bytes.u8(2)
                    val header = bytes.copyOf()
                    if (badHeader) header[2] = (header[2] + 1).toByte()
                    header + ColorCipher.transform(memory.copyOfRange(address, address + bytes.u8(3)))
                }
                'W'.code -> {
                    val address = (bytes.u8(1) shl 8) or bytes.u8(2)
                    if (!rejectWrites) ColorCipher.transform(bytes.copyOfRange(4, bytes.size)).copyInto(memory, address)
                    if (corruptWrites) memory[address] = (memory[address].toInt() xor 1).toByte()
                    byteArrayOf(if (rejectWrites) 0x15 else 6)
                }
                else -> error("Unexpected command: ${bytes.hex()}")
            }
            reply.toList().chunked(7).forEach { replies.send(it.toByteArray()) }
        }
    }
}
