package com.friday.app.ui

import com.friday.app.PersonalProfile
import com.friday.app.core.AiPreset
import com.friday.app.core.AppLanguage
import com.friday.app.core.GenerationSettings
import com.friday.app.core.InferenceBackend
import com.friday.app.core.PersonalityProfile
import com.friday.app.core.VoiceMode
import com.friday.app.voice.VoiceOutputSettings

/**
 * Everything the UI can ask FRIDAY to do. Screens depend on this interface (not the ViewModel)
 * so they can be rendered and tested with a fake implementation. Defaults are no-ops.
 */
interface FridayActions {
    fun send(text: String) {}
    fun onMicPressed() {}
    fun stopSpeaking() {}
    fun togglePauseSpeaking() {}
    fun dismissVoiceError() {}
    fun cancelListening() {}
    fun stopGeneration() {}
    fun speakMessage(text: String) {}
    fun setVoiceMode(mode: VoiceMode) {}
    fun setVoiceOutput(settings: VoiceOutputSettings) {}
    fun setSttPreferences(onDevice: Boolean, offline: Boolean) {}
    fun testVoice(language: AppLanguage) {}
    fun setLanguage(language: AppLanguage) {}
    fun updateGeneration(settings: GenerationSettings) {}
    fun resetGeneration() {}
    fun applyPreset(preset: AiPreset) {}
    fun savePreset(name: String) {}
    fun deletePreset(id: String) {}
    fun applyPendingReload() {}
    fun activatePersonality(id: String) {}
    fun savePersonality(profile: PersonalityProfile) {}
    fun duplicatePersonality(id: String): PersonalityProfile? = null
    fun deletePersonality(id: String) {}
    fun setBackend(backend: InferenceBackend) {}
    fun saveWorker(url: String, token: String?): String? = null
    fun forgetWorker() {}
    fun selectWorkerModel(name: String) {}
    fun setWorkerShareContext(enabled: Boolean) {}
    fun checkWorkerNow() {}
    fun selectModel(modelId: String) {}
    fun downloadModel() {}
    fun benchmarkSelectedModel() {}
    fun downloadVoiceModel() {}
    fun setSpeakReplies(enabled: Boolean) {}
    fun setContentStyle(value: String) {}
    fun setChatFontSize(value: Int) {}
    fun setCloudApproval(enabled: Boolean) {}
    fun clearMemory() {}
    fun clearError() {}
    fun scanLocalDocuments() {}
    fun disconnectGoogle() {}
    fun savePersonalProfile(profile: PersonalProfile) {}
    fun clearPersonalProfile() {}
    fun isModelInstalled(modelId: String): Boolean = false
}
