package com.friday.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (e.g. the Windows worker pairing token), AES-GCM encrypted with an Android Keystore key. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("friday_secrets", Context.MODE_PRIVATE)
    private val alias = "friday_secrets_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun get(name: String): String? = runCatching {
        val iv = prefs.getString("$name.iv", null) ?: return null
        val data = prefs.getString("$name.data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.onFailure { Log.w("FridaySecrets", "could not decrypt $name: ${it.javaClass.simpleName}") }.getOrNull()

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove("$name.iv").remove("$name.data").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$name.data", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }
}

/**
 * Logging facade with fixed subsystem tags. Callers log sizes, ids, codes and states — never
 * prompt text, transcripts, tokens or document contents.
 */
object FridayLog {
    enum class Area(val tag: String) { VOICE("FridayVoice"), TTS("FridayTTS"), LLM("FridayLLM"), WORKER("FridayWorker"), MODEL("FridayModel"), CONFIG("FridayConfig"), PERMISSION("FridayPerm") }
    fun i(area: Area, msg: String) { runCatching { Log.i(area.tag, msg) } }
    fun w(area: Area, msg: String, t: Throwable? = null) { runCatching { if (t != null) Log.w(area.tag, msg + " (" + t.javaClass.simpleName + ")") else Log.w(area.tag, msg) } }
    fun e(area: Area, msg: String, t: Throwable? = null) { runCatching { Log.e(area.tag, msg + (t?.let { " (" + it.javaClass.simpleName + ")" } ?: "")) } }
}
