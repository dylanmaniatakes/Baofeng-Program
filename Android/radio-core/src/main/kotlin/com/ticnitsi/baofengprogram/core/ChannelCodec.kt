package com.ticnitsi.baofengprogram.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.util.Locale

data class MemoryChannel(
    val number: Int,
    val name: String = "",
    val receiveHz: Long? = null,
    val transmitHz: Long? = null,
    val receiveTone: Int = 0,
    val transmitTone: Int = 0,
    val power: Int = 0,
    val narrow: Boolean = false,
    val scan: Boolean = true,
    val busyLock: Boolean = false,
    val signal: Int = 0,
    val pttId: Int = 0,
    val receiveDtmf: Int = 0,
    val hopping: Boolean = false,
) {
    val empty get() = receiveHz == null
}

object Frequencies {
    fun parse(text: String): Long {
        val hz = try {
            BigDecimal(text.trim()).multiply(BigDecimal(1000000)).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: Exception) { throw IllegalArgumentException("Enter a frequency in MHz, such as 146.52000") }
        require(hz in 1000000..999999990 && hz % 10 == 0L) { "Frequency must be positive and use 10 Hz steps" }
        return hz
    }
    fun format(hz: Long?) = hz?.let { String.format(Locale.US, "%.5f", it / 1000000.0) }.orEmpty()
}

object ToneCodes {
    val dcs = listOf(23,25,26,31,32,36,43,47,51,53,54,65,71,72,73,74,114,115,116,122,125,131,132,134,143,145,152,155,156,162,165,172,174,205,212,223,225,226,243,244,245,246,251,252,255,261,263,265,266,271,274,306,311,315,325,331,332,343,346,351,356,364,365,371,411,412,413,423,431,432,445,446,452,454,455,462,464,465,466,503,506,516,523,526,532,546,565,606,612,624,627,631,632,645,654,662,664,703,712,723,731,732,734,743,754)
    val ctcss = listOf(670,693,719,744,770,797,825,854,885,915,948,974,1000,1035,1072,1109,1148,1188,1230,1273,1318,1365,1413,1462,1514,1567,1598,1622,1655,1679,1713,1738,1773,1799,1835,1862,1899,1928,1966,1995,2035,2065,2107,2181,2257,2291,2336,2418,2503,2541)
    val choices = listOf(0) + ctcss + dcs.indices.map { it + 1 } + dcs.indices.map { it + 0x6A }
    fun label(raw: Int): String = when {
        raw == 0 || raw == 65535 -> "Off"
        raw in ctcss -> String.format(Locale.US, "CTCSS %.1f Hz", raw / 10.0)
        raw in 1..105 -> "DCS %03d N".format(dcs[raw - 1])
        raw in 106..210 -> "DCS %03d I".format(dcs[raw - 106])
        else -> "Unknown (%04X)".format(raw)
    }
}

object ChannelCodec {
    private val nameCharset = Charset.forName("GB2312")
    fun readAll(image: RadioImage) = (1..image.model.channelCount).map { read(image, it) }
    fun read(image: RadioImage, number: Int): MemoryChannel {
        require(number in 1..image.model.channelCount)
        val b = image.block((number - 1) * 32, 32)
        if (b.take(4).all { it == 0xFF.toByte() }) return MemoryChannel(number)
        val flags = b.u8(15)
        return MemoryChannel(
            number, decodeName(b.copyOfRange(20, 32)), decodeFrequency(b, 0), decodeFrequency(b, 4),
            b.le16(8), b.le16(10), b.u8(14) and 3,
            narrow = flags and 0x40 != 0,
            scan = flags and 0x04 != 0,
            busyLock = flags and 0x08 != 0,
            signal = b.u8(12), pttId = b.u8(13),
            receiveDtmf = (flags ushr 4) and 3, hopping = flags and 1 != 0,
        )
    }

    fun patch(image: RadioImage, channel: MemoryChannel): RadioImage {
        require(channel.number in 1..image.model.channelCount)
        val previous = read(image, channel.number)
        val b = image.bytes()
        val offset = (channel.number - 1) * 32
        if (channel.empty) {
            if (previous.empty) return image
            b.fill(0xFF.toByte(), offset, offset + 32)
            return image.updated(b)
        }
        val model = image.model
        fun validFrequency(hz: Long?) {
            if (hz == null) return
            require(hz % 10 == 0L && model.acceptsFrequency(hz)) { "Frequency is outside ${model.name}'s supported bands" }
        }
        if (previous.empty || channel.receiveHz != previous.receiveHz) validFrequency(channel.receiveHz)
        if (previous.empty || channel.transmitHz != previous.transmitHz) validFrequency(channel.transmitHz)
        require(channel.power in model.powerNames.indices || channel.power == previous.power) { "Invalid power level" }
        require(channel.signal in 0..19 || channel.signal == previous.signal) { "Signaling group must be 1 to 20" }
        require(channel.pttId in 0..3 || channel.pttId == previous.pttId)
        require(channel.receiveDtmf in 0..3)
        if (previous.empty) b.fill(0, offset, offset + 32)
        if (previous.empty || channel.receiveHz != previous.receiveHz) encodeFrequency(channel.receiveHz).copyInto(b, offset)
        if (previous.empty || channel.transmitHz != previous.transmitHz) encodeFrequency(channel.transmitHz).copyInto(b, offset + 4)
        if (previous.empty || channel.receiveTone != previous.receiveTone) putTone(b, offset + 8, channel.receiveTone)
        if (previous.empty || channel.transmitTone != previous.transmitTone) putTone(b, offset + 10, channel.transmitTone)
        b[offset + 12] = channel.signal.toByte()
        b[offset + 13] = channel.pttId.toByte()
        b[offset + 14] = ((b.u8(offset + 14) and 0xFC) or channel.power).toByte()
        var flags = b.u8(offset + 15)
        flags = flags.bit(0x40, channel.narrow).bit(0x04, channel.scan).bit(0x08, channel.busyLock)
        if (model.mini) flags = (flags and 0xCE) or (channel.receiveDtmf shl 4) or (if (channel.hopping) 1 else 0)
        b[offset + 15] = flags.toByte()
        // Only rewrite a name when edited; existing non-ASCII names keep their exact bytes.
        if (previous.empty || channel.name != previous.name) {
            require(channel.name.all { it.code in 32..126 }) { "Use English letters, numbers, and punctuation in names" }
            require(channel.name.length <= 12) { "Names can contain up to 12 characters" }
            b.fill(0, offset + 20, offset + 32)
            channel.name.toByteArray(Charsets.US_ASCII).copyInto(b, offset + 20)
        }
        return image.updated(b)
    }

    private fun putTone(b: ByteArray, offset: Int, value: Int) {
        require(value in ToneCodes.choices || value == 65535) { "Unsupported tone code" }
        b.putLe16(offset, value)
    }
    private fun decodeName(b: ByteArray): String = b.takeWhile { it != 0.toByte() && it != 0xFF.toByte() }
        .toByteArray().toString(nameCharset).trimEnd()
    private fun decodeFrequency(b: ByteArray, offset: Int): Long? {
        if ((offset until offset + 4).all { b.u8(it) == 255 }) return null
        var value = 0L
        for (i in 3 downTo 0) {
            val x = b.u8(offset + i)
            require((x and 15) <= 9 && (x ushr 4) <= 9) { "Invalid frequency bytes in radio image" }
            value = value * 100 + (x ushr 4) * 10 + (x and 15)
        }
        return value * 10
    }
    private fun encodeFrequency(hz: Long?): ByteArray {
        if (hz == null) return ByteArray(4) { 0xFF.toByte() }
        var value = hz / 10
        return ByteArray(4) {
            val pair = (value % 100).toInt()
            value /= 100
            ((pair / 10 shl 4) or (pair % 10)).toByte()
        }
    }
}

internal fun ByteArray.u8(offset: Int) = this[offset].toInt() and 255
internal fun ByteArray.le16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
internal fun ByteArray.putLe16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}
internal fun Int.bit(mask: Int, set: Boolean) = if (set) this or mask else this and mask.inv()
