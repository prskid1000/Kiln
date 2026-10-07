package app.kiln.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and tokens, encrypted with an AES-GCM key that lives in the Android
 * Keystore (never exported). One small file per secret id. Keys are never put
 * in config files, transcripts or logs.
 */
class Secrets(context: Context) {
    private val dir = File(context.filesDir, "secrets").apply { mkdirs() }
    private val alias = "kiln-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".bin")

    fun put(id: String, value: String?) {
        if (value.isNullOrEmpty()) { file(id).delete(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(value.toByteArray())
        file(id).writeText(Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(enc))
    }

    fun get(id: String): String? = runCatching {
        val (iv, data) = file(id).takeIf { it.isFile }?.readText()?.split(":") ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        String(c.doFinal(Base64.getDecoder().decode(data)))
    }.getOrNull()

    fun has(id: String) = file(id).isFile
}
