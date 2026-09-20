package com.ticnitsi.baofengprogram.core

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional read-only check. User radio images stay outside the repository. */
class LocalImageTest {
    @Test fun localBackupRoundtripsWithoutChangingAnyRadioBytes() {
        val path = System.getProperty("radio.backup").orEmpty()
        assumeTrue("Supply -PradioBackup=/path/to/read.bfp to validate a local image", path.isNotBlank())
        val image = BackupCodec.decode(File(path).readBytes())
        val memories = ChannelCodec.readAll(image)
        var edited = image
        memories.forEach { edited = ChannelCodec.patch(edited, it) }
        assertArrayEquals("Untouched memory fields must roundtrip exactly", image.bytes(), edited.bytes())
        SettingsCodec.definitions(image.model).forEach { field ->
            val value = field.value(image)
            if (field.choices.any { it.value == value }) edited = SettingsCodec.patch(edited, field.key, value)
        }
        assertArrayEquals("Untouched settings must roundtrip exactly", image.bytes(), edited.bytes())
        println("Validated ${memories.count { !it.empty }} programmed memories and ${memories.count { it.empty }} empty slots")
    }
}
