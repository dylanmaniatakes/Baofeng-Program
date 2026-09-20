package com.ticnitsi.baofengprogram.core

data class SettingChoice(val value: Int, val label: String)
data class RadioSetting(
    val key: String, val label: String, val group: String,
    val offset: Int, val choices: List<SettingChoice>,
    val mask: Int = 255, val shift: Int = 0,
) {
    fun value(image: RadioImage) = (image.block(0x9000 + offset, 1)[0].toInt() and mask) ushr shift
    fun label(image: RadioImage) = choices.firstOrNull { it.value == value(image) }?.label ?: "Unrecognized (${value(image)})"
}

object SettingsCodec {
    private fun options(vararg names: String) = names.mapIndexed { index, name -> SettingChoice(index, name) }
    private fun numbered(range: IntRange, unit: String = "") = range.mapIndexed { index, value -> SettingChoice(index, "$value$unit") }
    private fun onOff() = options("Off", "On")
    fun definitions(model: RadioModel): List<RadioSetting> = buildList {
        fun field(key: String, label: String, group: String, offset: Int, choices: List<SettingChoice>, mask: Int = 255, shift: Int = 0) {
            add(RadioSetting(key, label, group, offset, choices, mask, shift))
        }
        field("squelch", "Squelch", "Receive & scan", 0, options("Off", * (1..if(model.mini) 5 else 9).map { it.toString() }.toTypedArray()))
        field("dualWatch", "Dual watch", "Receive & scan", 4, onOff())
        field("scanMode", "Scan resume", "Receive & scan", 10, options("Time", "Carrier", "Search"))
        field("toneScan", "Tone scan saves", "Receive & scan", 43, options("RX and TX", "RX only", "TX only"))
        field("timeout", "Transmit timeout", "Transmit", 5, options("Off", *(15..180 step 15).map { "$it seconds" }.toTypedArray()))
        field("timeoutAlarm", "Timeout warning", "Transmit", 40, options("Off", *(1..10).map { "$it seconds" }.toTypedArray()))
        field("roger", "Roger beep", "Transmit", 23, onOff())
        field("tail", "Squelch tail elimination", "Transmit", 20, onOff())
        field("rptTail", "Repeater tail clear", "Transmit", 21, (0..1000 step 100).mapIndexed { i, n -> SettingChoice(i, "$n ms") })
        field("rptDelay", "Repeater tail delay", "Transmit", 22, (0..1000 step 100).mapIndexed { i, n -> SettingChoice(i, "$n ms") })
        if (model.mini) field("voxSwitch", "VOX", "Transmit", 58, onOff())
        field("vox", "VOX sensitivity", "Transmit", 2, if(model.mini) numbered(1..9) else options("Off", *(1..9).map { it.toString() }.toTypedArray()))
        field("voxDelay", "VOX delay", "Transmit", 32, (500..2000 step 100).mapIndexed { i, n -> SettingChoice(i, "$n ms") })
        field("voice", "Voice prompts", "Sound & display", 7, onOff())
        field("language", "Radio voice language", "Sound & display", 8, options("English", "Chinese"))
        field("beep", "Key beep", "Sound & display", 6, onOff())
        field("backlight", "Backlight", "Sound & display", 3, options("Always on", "5 seconds", "10 seconds", "15 seconds", "20 seconds"))
        field("displayA", "Channel A display", "Sound & display", 13, options("Name", "Frequency", "Channel number"))
        field("displayB", "Channel B display", "Sound & display", 14, options("Name", "Frequency", "Channel number"))
        field("modeA", "Channel A mode", "Sound & display", 26, options("Frequency", "Memory"), 15, 0)
        field("modeB", "Channel B mode", "Sound & display", 26, options("Frequency", "Memory"), 240, 4)
        field("startup", "Startup display", "Sound & display", 28, options("Logo", "Battery voltage"))
        field("menuTimeout", "Menu timeout", "Sound & display", 33, options(*(5..50 step 5).map { "$it seconds" }.toTypedArray(), "60 seconds"))
        field("battery", "Battery saver", "General", 1, onOff())
        field("autoLock", "Automatic keypad lock", "General", 16, onOff())
        field("keyLock", "Keypad locked", "General", 27, onOff())
        field("fm", "FM broadcast receiver", "General", 25, listOf(SettingChoice(0, "On"), SettingChoice(1, "Off")))
        field("alarm", "Alarm mode", "General", 17, options("Local", "Send tone", "Send code"))
        field("alarmSound", "Alarm sound", "General", 18, onOff())
        field("dtmfSidetone", "DTMF sidetone", "Signaling", 9, options("Off", "Keypad", "ANI", "Keypad and ANI"))
        field("pttId", "VFO PTT ID", "Signaling", 11, options("Off", "Start", "End", "Both"))
        field("pttDelay", "PTT ID delay", "Signaling", 12, (100..3000 step 100).mapIndexed { i, n -> SettingChoice(i, "$n ms") })
        field("toneBurst", "Tone burst", "Signaling", 29, options("1000 Hz", "1450 Hz", "1750 Hz", "2100 Hz"))
    }

    fun patch(image: RadioImage, key: String, value: Int): RadioImage {
        val field = definitions(image.model).firstOrNull { it.key == key } ?: error("Unknown radio setting")
        require(field.choices.any { it.value == value }) { "Invalid setting value" }
        val bytes = image.bytes()
        val address = 0x9000 + field.offset
        bytes[address] = ((bytes.u8(address) and field.mask.inv()) or ((value shl field.shift) and field.mask)).toByte()
        return image.updated(bytes)
    }
}
