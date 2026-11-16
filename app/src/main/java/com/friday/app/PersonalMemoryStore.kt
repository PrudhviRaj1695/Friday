package com.friday.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PersonalProfile(
    val displayName: String = "",
    val communicationStyle: String = "Direct, practical and concise",
    val responsePreferences: String = "Explain assumptions, give actionable steps and be honest about limitations.",
    val goals: String = "",
    val workContext: String = "",
    val interests: String = "",
    val customInstructions: String = "",
    val learnedNotes: String = "",\n    val fineTuneExamples: String = ""
) {
    fun toJson() = JSONObject().put("displayName",displayName).put("communicationStyle",communicationStyle)
        .put("responsePreferences",responsePreferences).put("goals",goals).put("workContext",workContext)
        .put("interests",interests).put("customInstructions",customInstructions).put("learnedNotes",learnedNotes).put("fineTuneExamples",fineTuneExamples)
    companion object {
        fun fromJson(j: JSONObject) = PersonalProfile(
            j.optString("displayName"), j.optString("communicationStyle","Direct, practical and concise"),
            j.optString("responsePreferences"), j.optString("goals"), j.optString("workContext"),
            j.optString("interests"), j.optString("customInstructions"), j.optString("learnedNotes"), j.optString("fineTuneExamples")
        )
    }
}

/** Encrypted, app-private personal profile. Encryption key is held by Android Keystore. */
class PersonalMemoryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("friday_personal_memory", Context.MODE_PRIVATE)
    private val alias = "friday_personal_profile_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun read(): PersonalProfile = runCatching {
        val iv = Base64.decode(prefs.getString("iv",""), Base64.NO_WRAP)
        val cipherText = Base64.decode(prefs.getString("data",""), Base64.NO_WRAP)
        if (iv.isEmpty() || cipherText.isEmpty()) return PersonalProfile()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        PersonalProfile.fromJson(JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8)))
    }.getOrDefault(PersonalProfile())
    fun save(profile: PersonalProfile) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(profile.toJson().toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
