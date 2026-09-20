package com.ticnitsi.baofengprogram.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

object BackupCodec {
    private const val MAGIC = "BAOFENG-ANALOG-1"
    const val MAX_SIZE = 131072
    fun encode(image: RadioImage): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeUTF(MAGIC)
            it.writeUTF(image.modelId)
            it.writeUTF(image.identity)
            val bytes = image.bytes()
            it.writeInt(bytes.size)
            it.write(bytes)
        }
        val payload = buffer.toByteArray()
        return payload + sha256(payload)
    }
    fun decode(bytes: ByteArray): RadioImage {
        require(bytes.size in 64..MAX_SIZE) { "Invalid backup size" }
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(sha256(payload), bytes.copyOfRange(bytes.size - 32, bytes.size))) { "Backup checksum failed" }
        DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readUTF() == MAGIC) { "Unsupported backup format" }
            val model = RadioModels.byId(it.readUTF())
            val identity = it.readUTF()
            require(identity.isNotBlank() && identity.length <= 100) { "Backup has no radio identity" }
            require(it.readInt() == model.imageSize) { "Backup is incomplete" }
            val data = ByteArray(model.imageSize)
            it.readFully(data)
            require(it.available() == 0) { "Unexpected data in backup" }
            return RadioImage(model.id, identity, data)
        }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
