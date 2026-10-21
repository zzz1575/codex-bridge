package com.y2yy.codex.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts private local data with a non-exportable, app-scoped Android Keystore key. */
internal class EncryptedVault(context: Context) {
    private val applicationId = context.applicationContext.packageName
    private val alias = "$applicationId.native.private.v1"

    fun encrypt(purpose: String, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad(purpose))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
    }

    /** Missing/invalid keys, corrupted payloads and altered purpose fail closed. */
    fun decrypt(purpose: String, payload: ByteArray): String? = runCatching {
        require(payload.size >= 2 + IV_BYTES + TAG_BYTES)
        require(payload[0] == FORMAT_VERSION)
        val ivSize = payload[1].toInt() and 0xff
        require(ivSize == IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BYTES * 8, payload.copyOfRange(2, 2 + ivSize))
        )
        cipher.updateAAD(aad(purpose))
        String(cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun aad(purpose: String) =
        "$applicationId:native-storage:v1:$purpose".toByteArray(StandardCharsets.UTF_8)

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private val KEY_LOCK = Any()
    }
}
