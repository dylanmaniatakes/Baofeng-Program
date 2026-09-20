package com.ticnitsi.baofengprogram.core

data class AddressRange(val start: Int, val length: Int) {
    val end: Int get() = start + length
    fun contains(address: Int) = address in start until end
}

data class Band(val low: Long, val high: Long) {
    operator fun contains(hz: Long) = hz in low..high
}

data class RadioModel(
    val id: String,
    val name: String,
    val handshake: String,
    val identityPrefix: String,
    val regions: List<AddressRange>,
    val bands: List<Band>,
    val powerNames: List<String>,
    val bluetoothWriteSize: Int = 64,
    val mini: Boolean = false,
) {
    val channelCount = 999
    val imageSize: Int get() = regions.maxOf { it.end }
    val readSize = 64
    fun acceptsFrequency(hz: Long) = bands.any { hz in it }
}

object RadioModels {
    // Read the complete final BLE write blocks, including their unedited tails.
    val mini = RadioModel(
        "uv5r-mini", "UV-5R Mini", "PROGRAMCOLORPROU", "5RMINI",
        listOf(AddressRange(0, 0x8040), AddressRange(0x9000, 0x80), AddressRange(0xA000, 0x200)),
        listOf(Band(136000000, 174000000), Band(350000000, 390000000), Band(400000000, 520000000)),
        listOf("High", "Low"), bluetoothWriteSize = 128, mini = true,
    )
    val uv5rh = RadioModel(
        "uv5rh", "UV-5RH", "PROGRAMBFNORMALU", "5RH",
        listOf(AddressRange(0, 0x7D00), AddressRange(0x8000, 0x80), AddressRange(0x9000, 0x80), AddressRange(0xA000, 0x1C0)),
        listOf(Band(136000000, 174000000), Band(200000000, 260000000), Band(400000000, 520000000)),
        listOf("High", "Low", "Mid"),
    )
    val supported = listOf(mini)
    fun byId(id: String) = supported.firstOrNull { it.id == id } ?: error("Unsupported radio model: $id")
}

class RadioImage(val modelId: String, val identity: String, bytes: ByteArray) {
    private val contents = bytes.copyOf()
    val model get() = RadioModels.byId(modelId)
    init { require(contents.size == model.imageSize) { "Incomplete radio image" } }
    fun bytes() = contents.copyOf()
    fun block(address: Int, length: Int): ByteArray {
        require(address >= 0 && address + length <= contents.size)
        return contents.copyOfRange(address, address + length)
    }
    fun updated(bytes: ByteArray) = RadioImage(modelId, identity, bytes)
}

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 255) }
fun Int.hexAddress(): String = "0x%04X".format(this)
