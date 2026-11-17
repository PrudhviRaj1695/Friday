package com.friday.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.friday.app.FridayState
import com.friday.app.FridayViewModel
import com.friday.app.core.AppLanguage
import com.friday.app.core.GenerationSettings
import com.friday.app.core.InferenceBackend
import com.friday.app.core.LanguagePreference
import com.friday.app.core.PersonalityProfile
import com.friday.app.core.ResponseLength
import com.friday.app.core.VoiceMode
import com.friday.app.voice.VoiceEnginePreference
import com.friday.app.worker.WorkerStatus

enum class SettingsPage(val title: String, val subtitle: String) {
    GENERAL("General", "Spoken replies, notifications, history"),
    APPEARANCE("Appearance", "Theme and reading comfort"),
    LANGUAGE("Language", "English or Telugu for voice and replies"),
    VOICE("Voice", "Voice mode, recognition, speech output"),
    AI_MODELS("AI models", "Model, sampling, presets, system prompt"),
    PERSONALITY("Personality", "Profiles, tone and your personal profile"),
    CONNECTIVITY("Connectivity", "Windows worker and Google account"),
    PRIVACY("Privacy & data", "Documents, caches and what leaves the phone"),
    ADVANCED("Advanced", "Diagnostics and performance")
}

fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.GENERAL -> FxIcons.Bell
    SettingsPage.APPEARANCE -> FxIcons.Palette
    SettingsPage.LANGUAGE -> FxIcons.Globe
    SettingsPage.VOICE -> FxIcons.Wave
    SettingsPage.AI_MODELS -> FxIcons.Chip
    SettingsPage.PERSONALITY -> FxIcons.Sparkle
    SettingsPage.CONNECTIVITY -> FxIcons.Monitor
    SettingsPage.PRIVACY -> FxIcons.Shield
    SettingsPage.ADVANCED -> FxIcons.Terminal
}

/** Android-specific hooks and facts the settings pages need (permissions, intents, device info). */
data class PlatformHooks(
    val sttAvailable: Boolean = true,
    val onDeviceSttAvailable: Boolean = false,
    val allFilesGranted: Boolean = false,
    val folderLabel: String = "No folder selected",
    val googleError: String? = null,
    val requestNotifications: () -> Unit = {},
    val openTtsSettings: () -> Unit = {},
    val openVoiceInputSettings: () -> Unit = {},
    val connectGoogle: () -> Unit = {},
    val grantAllFiles: () -> Unit = {},
    val pickFolder: () -> Unit = {},
    val openDiagnostics: () -> Unit = {},
    val openProfileEditor: () -> Unit = {}
)

@Composable
fun SettingsScreen(page: SettingsPage?, state: FridayState, actions: FridayActions, hooks: PlatformHooks, onNavigate: (SettingsPage?) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Fx.BackgroundTop, Fx.Background))), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxHeight().widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == null) onClose() else onNavigate(null) }, modifier = Modifier.semantics { contentDescription = "Back" }) { Icon(FxIcons.Back, null, tint = Fx.Text) }
            Column {
                Text(page?.title ?: "Settings", style = MaterialTheme.typography.titleLarge)
                if (page != null) Text(page.subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        val scroll = key(page) { rememberScrollState() } // every page opens at its top
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                null -> SettingsHome(state, onNavigate)
                SettingsPage.GENERAL -> GeneralPage(state, actions, hooks)
                SettingsPage.APPEARANCE -> AppearancePage(state, actions)
                SettingsPage.LANGUAGE -> LanguagePage(state, actions, hooks)
                SettingsPage.VOICE -> VoicePage(state, actions, hooks)
                SettingsPage.AI_MODELS -> AiStudioPage(state, actions)
                SettingsPage.PERSONALITY -> PersonalityStudioPage(state, actions, hooks)
                SettingsPage.CONNECTIVITY -> ConnectivityPage(state, actions, hooks)
                SettingsPage.PRIVACY -> PrivacyPage(state, actions, hooks)
                SettingsPage.ADVANCED -> AdvancedPage(state, actions, hooks)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun SettingsHome(state: FridayState, onNavigate: (SettingsPage?) -> Unit) {
    val (conn, connColor) = connectionStatus(state)
    FxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceOrb(state.voice.state, 0f, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("FRIDAY", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(connColor); Spacer(Modifier.width(6.dp)); Text("$conn • ${state.activeModelLabel}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    SettingsPage.entries.forEach { p ->
        Surface(color = Fx.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Fx.Outline),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onNavigate(p) }.testTag("settings-${p.name}")) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Fx.SurfaceHigher), contentAlignment = Alignment.Center) { Icon(p.icon(), null, tint = Fx.Cyan, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text(p.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(FxIcons.ChevronRight, null, tint = Fx.TextTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------- General
@Composable
private fun GeneralPage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    var confirmClear by remember { mutableStateOf(false) }
    FxCard {
        ToggleRow("Speak replies", "Read FRIDAY's answers aloud (not in Text-only mode)", state.speakReplies, onChange = actions::setSpeakReplies)
        HorizontalDivider(color = Fx.Outline)
        ToggleRow("Cloud fallback approval", "No cloud provider is connected; nothing is ever sent to the cloud. Reserved for a future, per-request opt-in.", state.cloudApprovalEnabled, onChange = actions::setCloudApproval)
    }
    SectionLabel("Notifications")
    FxCard {
        Text("Status alerts only (model ready, scan finished, errors). Never message or document content.", style = MaterialTheme.typography.bodySmall)
        FxSecondaryButton("Manage notification permission", hooks.requestNotifications, icon = FxIcons.Bell)
    }
    SectionLabel("Conversation history")
    FxCard {
        Text("${state.messages.size} messages on screen. History is stored only in this app's private database.", style = MaterialTheme.typography.bodySmall)
        FxSecondaryButton("Clear chat history", { confirmClear = true }, icon = FxIcons.Trash, danger = true)
    }
    if (confirmClear) ConfirmDialog("Clear chat history?", "This permanently deletes FRIDAY's locally stored conversation. It does not delete the Google cache or your profile.", "Clear", onConfirm = { actions.clearMemory(); confirmClear = false }, onDismiss = { confirmClear = false })
}

// ---------------------------------------------------------------------------- Appearance
@Composable
private fun AppearancePage(state: FridayState, actions: FridayActions) {
    FxCard {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Text("FRIDAY uses a single dark graphite theme designed for contrast and low glare. A light theme is not available.", style = MaterialTheme.typography.bodySmall)
    }
    FxCard {
        var size by remember(state.chatFontSize) { mutableFloatStateOf(state.chatFontSize.toFloat()) }
        LabeledSlider("Message text size", "${size.toInt()} sp", size, 14f..24f, steps = 9, onChange = { size = it }, onDone = { actions.setChatFontSize(size.toInt()) })
        Surface(color = Fx.Background, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownText("**Preview** — readable at a glance, with `code` and lists:\n- English sample text", size.toInt())
                MarkdownText("తెలుగు నమూనా: ఈ వాక్యం సరిగ్గా కనిపిస్తుందా?", size.toInt())
            }
        }
    }
}

// ---------------------------------------------------------------------------- Language
@Composable
private fun LanguagePage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    Text("Your choice controls speech recognition, the reply language and the speaking voice. FRIDAY never switches language automatically.", style = MaterialTheme.typography.bodySmall)
    AppLanguage.entries.forEach { lang ->
        val selected = state.language == lang
        Surface(color = if (selected) Fx.SurfaceHigh else Fx.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Fx.Blue else Fx.Outline),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.RadioButton) { actions.setLanguage(lang) }.testTag("lang-${lang.name}")) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lang.nativeName, style = scriptAwareStyle(lang.nativeName, MaterialTheme.typography.titleMedium, 18))
                    Text(if (lang == AppLanguage.TELUGU) "Speech recognition te-IN • Telugu replies • Android system Telugu voice" else "Speech recognition en-US • English replies • Kokoro neural voice", style = MaterialTheme.typography.bodySmall)
                }
                if (selected) Icon(FxIcons.Check, null, tint = Fx.Cyan)
            }
        }
    }
    state.activePersonality?.takeIf { it.language != LanguagePreference.FOLLOW_APP }?.let {
        Notice("The active personality '${it.name}' always replies in ${it.language.label}.", Fx.Warning)
    }
    if (state.language == AppLanguage.TELUGU) {
        FxCard {
            Text("Telugu checklist", style = MaterialTheme.typography.titleSmall)
            Text("• Voice input needs the Telugu language in Android voice typing (offline pack recommended).\n• Spoken replies need Telugu voice data in Android text-to-speech.\n• Small on-device models write weaker Telugu; the Windows worker with a larger model gives better results.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FxSecondaryButton("Voice typing", hooks.openVoiceInputSettings)
                FxSecondaryButton("TTS voices", hooks.openTtsSettings)
            }
        }
    }
}

// ---------------------------------------------------------------------------- Voice
@Composable
private fun VoicePage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    SectionLabel("Voice mode")
    FxCard {
        VoiceMode.entries.forEach { mode ->
            RadioRow(mode.label, mode.description, state.voiceMode == mode) { actions.setVoiceMode(mode) }
        }
        RadioRow("Wake word (“Hey FRIDAY”)", "Not available: FRIDAY has no on-device hotword engine, and always-on listening would drain the battery. You can still start requests with “Hey Friday”.", selected = false, enabled = false) {}
    }
    SectionLabel("Speech recognition")
    FxCard {
        KeyValue("Service", if (hooks.sttAvailable) "Installed" else "Not installed", if (hooks.sttAvailable) Fx.Success else Fx.Danger)
        KeyValue("On-device engine", if (hooks.onDeviceSttAvailable) "Available" else "Not available", if (hooks.onDeviceSttAvailable) Fx.Success else Fx.TextSecondary)
        KeyValue("Language", "${state.language.nativeName} (${state.language.bcp47})")
        ToggleRow("Prefer on-device recognizer", "Falls back to the standard recognizer automatically if the language pack is missing", state.preferOnDeviceStt, enabled = hooks.onDeviceSttAvailable) { actions.setSttPreferences(it, state.preferOfflineStt) }
        ToggleRow("Prefer offline recognition", "Asks the recognizer not to use the network. Whether it is honoured depends on the installed service and language pack.", state.preferOfflineStt) { actions.setSttPreferences(state.preferOnDeviceStt, it) }
        Text("Silence detection ends an utterance after about 1.6 s; FRIDAY stops listening after 15 s without a result.", style = MaterialTheme.typography.bodySmall)
        FxSecondaryButton("Android voice typing settings", hooks.openVoiceInputSettings)
    }
    SectionLabel("Speech output")
    FxCard {
        Text("Engine", style = MaterialTheme.typography.titleSmall)
        Segmented(VoiceEnginePreference.entries, state.voiceOutput.engine, { it.label }, { actions.setVoiceOutput(state.voiceOutput.copy(engine = it)) })
        Text(state.voiceOutput.engine.description, style = MaterialTheme.typography.bodySmall)
        if (state.voiceEngineNote.isNotBlank()) Notice(state.voiceEngineNote, if (state.voiceEngineNote.contains("not", true)) Fx.Warning else Fx.Blue)
        var speed by remember(state.voiceOutput.speed) { mutableFloatStateOf(state.voiceOutput.speed) }
        var pitch by remember(state.voiceOutput.pitch) { mutableFloatStateOf(state.voiceOutput.pitch) }
        LabeledSlider("Speaking speed", "%.2f×".format(speed), speed, 0.5f..2f, onChange = { speed = it }, onDone = { actions.setVoiceOutput(state.voiceOutput.copy(speed = speed)) })
        LabeledSlider("Pitch", "%.2f".format(pitch), pitch, 0.5f..2f, help = "Pitch applies to the Android system voice only; Kokoro has a fixed pitch.", onChange = { pitch = it }, onDone = { actions.setVoiceOutput(state.voiceOutput.copy(pitch = pitch)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxSecondaryButton("Test English", { actions.testVoice(AppLanguage.ENGLISH) }, icon = FxIcons.Speaker)
            FxSecondaryButton("Test తెలుగు", { actions.testVoice(AppLanguage.TELUGU) }, icon = FxIcons.Speaker)
        }
        Text("Pause/resume works with the Kokoro voice. Android's system voice supports stop only.", style = MaterialTheme.typography.bodySmall)
    }
    SectionLabel("Kokoro neural voice (English, offline)")
    FxCard {
        Text("Kokoro-82M via Sherpa-ONNX • on-device CPU inference • voice: Michael (American English, male)", style = MaterialTheme.typography.bodySmall)
        Text(state.voiceStatus, style = MaterialTheme.typography.bodyMedium)
        if (state.voiceDownloading) {
            LinearProgressIndicator(progress = { state.voiceDownloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Fx.Blue, trackColor = Fx.SurfaceHigher)
        } else if (!state.voiceModelReady) {
            FxPrimaryButton("Download Kokoro voice", actions::downloadVoiceModel)
        }
    }
    FxCard { ToggleRow("Speak replies", null, state.speakReplies, onChange = actions::setSpeakReplies) }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Fx.Blue else Fx.SurfaceHigher), contentAlignment = Alignment.Center) {
            if (selected) Icon(FxIcons.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Fx.Text else Fx.TextTertiary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- AI configuration studio
@Composable
private fun AiStudioPage(state: FridayState, actions: FridayActions) {
    SectionLabel("Inference engine")
    FxCard {
        Segmented(InferenceBackend.entries, state.backend, { if (it == InferenceBackend.ON_DEVICE) "Phone" else "Windows PC" }, actions::setBackend, Modifier.testTag("backend-selector"))
        Text(if (state.backend == InferenceBackend.ON_DEVICE) "Runs on this phone with LiteRT-LM. Nothing leaves the device." else "Runs on your Windows PC through Ollama over your home network. Configure it in Connectivity.", style = MaterialTheme.typography.bodySmall)
    }
    if (state.backend == InferenceBackend.ON_DEVICE) OnDeviceModelCard(state, actions) else WorkerModelCard(state, actions)

    SectionLabel("Sampling & limits")
    val g = state.generation
    var draft by remember(g) { mutableStateOf(g) }
    fun commit(s: GenerationSettings) { draft = s; actions.updateGeneration(s) }
    FxCard(Modifier.testTag("generation-card")) {
        LabeledSlider("Temperature", "%.2f".format(draft.temperature), draft.temperature, 0f..2f, help = "Lower = focused and repeatable. Higher = more varied and creative.", onChange = { draft = draft.copy(temperature = (it * 20).toInt() / 20f) }, onDone = { commit(draft) })
        LabeledSlider("Top-P", "%.2f".format(draft.topP), draft.topP, 0.05f..1f, help = "Samples only from the most likely tokens whose probabilities add up to this value.", onChange = { draft = draft.copy(topP = (it * 100).toInt() / 100f) }, onDone = { commit(draft) })
        LabeledSlider("Top-K", "${draft.topK}", draft.topK.toFloat(), 1f..200f, help = "Limits sampling to the K most likely tokens.", onChange = { draft = draft.copy(topK = it.toInt()) }, onDone = { commit(draft) })
        val maxCtx = if (state.backend == InferenceBackend.ON_DEVICE) (FridayViewModel.MODEL_OPTIONS.firstOrNull { it.id == state.selectedModelId }?.maxContext ?: 4096) else state.worker.maxContext
        LabeledSlider("Max output tokens", "${draft.maxOutputTokens}", draft.maxOutputTokens.toFloat(), 64f..(maxCtx / 2f).coerceAtLeast(128f), help = "Hard cap on reply length.", onChange = { draft = draft.copy(maxOutputTokens = (it / 32).toInt() * 32) }, onDone = { commit(draft) })
        LabeledSlider("Context length", "${draft.contextLength}", draft.contextLength.toFloat(), 1024f..maxCtx.toFloat().coerceAtLeast(1025f),
            help = if (state.backend == InferenceBackend.ON_DEVICE) "Tokens of conversation the model can see. Changing it reloads the on-device model (max $maxCtx)." else "Sent to Ollama as num_ctx (worker max $maxCtx). Larger values use more VRAM.",
            onChange = { draft = draft.copy(contextLength = (it / 256).toInt() * 256) }, onDone = { commit(draft) })
        if (state.pendingReload && state.backend == InferenceBackend.ON_DEVICE) Notice("Context length changes take effect after the model reloads.", Fx.Warning) { TextButton(onClick = actions::applyPendingReload) { Text("Reload", color = Fx.Cyan) } }
        HorizontalDivider(color = Fx.Outline)
        ToggleRow("Stream responses", "Show text as it is generated. Off = show the complete reply at once.", draft.streaming) { commit(draft.copy(streaming = it)) }
        state.generationNotes.forEach { Notice(it, Fx.Warning) }
    }

    SectionLabel("System prompt")
    FxCard {
        var prompt by remember(g.systemPrompt) { mutableStateOf(g.systemPrompt) }
        Text("Added after FRIDAY's built-in instructions and the active personality.", style = MaterialTheme.typography.bodySmall)
        FxTextField(prompt, { prompt = it }, "Additional instructions", singleLine = false, minLines = 3, placeholder = "e.g. Always answer with a one-line summary first.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxPrimaryButton("Save prompt", { actions.updateGeneration(state.generation.copy(systemPrompt = prompt)) }, enabled = prompt != g.systemPrompt)
            if (g.systemPrompt.isNotBlank()) FxSecondaryButton("Clear", { prompt = ""; actions.updateGeneration(state.generation.copy(systemPrompt = "")) })
        }
        Text("Content style", style = MaterialTheme.typography.titleSmall)
        Segmented(listOf("Strict", "Balanced", "Relaxed"), state.contentStyle, { it }, actions::setContentStyle)
    }

    SectionLabel("Saved configurations")
    FxCard {
        state.presets.forEach { p ->
            val builtIn = p.id.startsWith("preset-") && com.friday.app.core.AiPreset.BUILT_IN.any { it.id == p.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text("temp %.2f • top-p %.2f • top-k %d • %d tokens".format(p.settings.temperature, p.settings.topP, p.settings.topK, p.settings.maxOutputTokens), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { actions.applyPreset(p) }) { Text("Apply", color = Fx.Cyan) }
                if (!builtIn) IconButton(onClick = { actions.deletePreset(p.id) }) { Icon(FxIcons.Trash, "Delete ${p.name}", tint = Fx.TextTertiary, modifier = Modifier.size(18.dp)) }
            }
        }
        var name by remember { mutableStateOf("") }
        FxTextField(name, { name = it }, "Save current settings as…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxPrimaryButton("Save preset", { actions.savePreset(name); name = "" }, enabled = name.isNotBlank(), icon = FxIcons.Plus)
            FxSecondaryButton("Reset to defaults", actions::resetGeneration, icon = FxIcons.Refresh)
        }
    }
}

@Composable
private fun OnDeviceModelCard(state: FridayState, actions: FridayActions) {
    SectionLabel("On-device model library")
    FxCard {
        FridayViewModel.MODEL_OPTIONS.forEach { option ->
            val selected = option.id == state.selectedModelId
            val installed = actions.isModelInstalled(option.id)
            Surface(color = if (selected) Fx.SurfaceHigh else Fx.Background, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (selected) Fx.Blue else Fx.Outline),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !state.busy && !state.downloading, role = Role.RadioButton) { actions.selectModel(option.id) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.name, style = MaterialTheme.typography.titleSmall)
                        Text("${option.expectedSize} • ${option.license} • ${if (installed) "Downloaded" else "Not downloaded"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected) Icon(FxIcons.Check, null, tint = Fx.Cyan)
                }
            }
        }
        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = if (state.modelReady) Fx.Success else Fx.TextSecondary)
        when {
            state.downloading -> {
                LinearProgressIndicator(progress = { state.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Fx.Blue, trackColor = Fx.SurfaceHigher)
                Text("Downloading ${state.downloadProgress}%", style = MaterialTheme.typography.bodySmall)
            }
            !actions.isModelInstalled(state.selectedModelId) -> FxPrimaryButton("Download selected model", actions::downloadModel, enabled = !state.busy)
        }
        Text("Models stay in app-private storage. Use Wi-Fi; larger models need more RAM.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WorkerModelCard(state: FridayState, actions: FridayActions) {
    SectionLabel("Windows worker model")
    FxCard {
        val (conn, color) = connectionStatus(state)
        Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(color); Spacer(Modifier.width(8.dp)); Text(conn, style = MaterialTheme.typography.titleSmall) }
        Text(state.worker.detail, style = MaterialTheme.typography.bodySmall)
        if (state.worker.models.isEmpty()) Text("No model list yet. Connect the worker in Connectivity.", style = MaterialTheme.typography.bodySmall)
        state.worker.models.forEach { m ->
            val selected = (state.worker.selectedModel.ifBlank { state.worker.defaultModel }) == m
            RadioRow(m, if (m == state.worker.defaultModel) "Worker default" else "Installed in Ollama", selected) { actions.selectWorkerModel(m) }
        }
        Text("For an 8 GB GPU, 3B–8B models at Q4 fit comfortably (e.g. qwen2.5:7b). Only one model is kept loaded at a time.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------- Personality studio
@Composable
private fun PersonalityStudioPage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    var editing by remember { mutableStateOf<PersonalityProfile?>(null) }
    var deleting by remember { mutableStateOf<PersonalityProfile?>(null) }
    FxPrimaryButton("New personality", { editing = PersonalityProfile(PersonalityProfile.newId(), "") }, icon = FxIcons.Plus, modifier = Modifier.fillMaxWidth())
    state.personalities.forEach { p ->
        val active = p.id == state.activePersonalityId
        Surface(color = if (active) Fx.SurfaceHigh else Fx.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) Fx.Blue else Fx.Outline), modifier = Modifier.fillMaxWidth().testTag("personality-${p.id}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (active) Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = Fx.Cyan)
                    else if (p.builtIn) Text("BUILT-IN", style = MaterialTheme.typography.labelSmall, color = Fx.TextTertiary)
                }
                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(p.tone.takeIf { it.isNotBlank() }, p.responseLength.label + " replies", p.language.takeIf { it != LanguagePreference.FOLLOW_APP }?.label, p.temperature?.let { "temp %.2f".format(it) }).joinToString(" • "), style = MaterialTheme.typography.labelMedium, color = Fx.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!active) TextButton(onClick = { actions.activatePersonality(p.id) }) { Text("Activate", color = Fx.Cyan) }
                    if (!p.builtIn) TextButton(onClick = { editing = p }) { Text("Edit", color = Fx.Text) }
                    TextButton(onClick = { actions.duplicatePersonality(p.id)?.let { editing = it } }) { Text("Duplicate", color = Fx.Text) }
                    if (!p.builtIn) TextButton(onClick = { deleting = p }) { Text("Delete", color = Fx.Danger) }
                }
            }
        }
    }
    SectionLabel("About you")
    FxCard {
        Text("Your encrypted personal profile (name, goals, work context) is added to every prompt, whichever personality is active.", style = MaterialTheme.typography.bodySmall)
        FxSecondaryButton("Edit personal profile", hooks.openProfileEditor, icon = FxIcons.User)
    }
    editing?.let { p -> PersonalityEditor(p, onSave = { actions.savePersonality(it); editing = null }, onDismiss = { editing = null }) }
    deleting?.let { p -> ConfirmDialog("Delete ${p.name}?", "This personality profile will be removed. This cannot be undone.", "Delete", onConfirm = { actions.deletePersonality(p.id); deleting = null }, onDismiss = { deleting = null }) }
}

@Composable
fun PersonalityEditor(initial: PersonalityProfile, onSave: (PersonalityProfile) -> Unit, onDismiss: () -> Unit) {
    var p by remember(initial.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Fx.SurfaceHigh,
        title = { Text(if (initial.name.isBlank()) "New personality" else "Edit personality") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FxTextField(p.name, { p = p.copy(name = it.take(40)) }, "Name")
                FxTextField(p.description, { p = p.copy(description = it.take(120)) }, "Description")
                FxTextField(p.systemPrompt, { p = p.copy(systemPrompt = it.take(3000)) }, "System prompt", singleLine = false, minLines = 4, placeholder = "How should FRIDAY think and behave?")
                FxTextField(p.tone, { p = p.copy(tone = it.take(80)) }, "Tone", placeholder = "e.g. Calm, precise, dry wit")
                Text("Language", style = MaterialTheme.typography.titleSmall)
                Segmented(LanguagePreference.entries, p.language, { if (it == LanguagePreference.FOLLOW_APP) "App" else it.label }, { p = p.copy(language = it) })
                Text("Response length", style = MaterialTheme.typography.titleSmall)
                Segmented(ResponseLength.entries, p.responseLength, { it.label }, { p = p.copy(responseLength = it) })
                ToggleRow("Override temperature", "Use this creativity level whenever the personality is active", p.temperature != null) { p = p.copy(temperature = if (it) 0.7f else null) }
                p.temperature?.let { t -> LabeledSlider("Temperature", "%.2f".format(t), t, 0f..2f, onChange = { p = p.copy(temperature = (it * 20).toInt() / 20f) }) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p.copy(name = p.name.trim().ifBlank { "Custom" })) }, enabled = p.name.isNotBlank()) { Text("Save", color = Fx.Cyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Fx.TextSecondary) } }
    )
}

// ---------------------------------------------------------------------------- Connectivity
@Composable
private fun ConnectivityPage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    SectionLabel("Windows worker")
    val w = state.worker
    FxCard(Modifier.testTag("worker-card")) {
        val color = when (w.status) { WorkerStatus.CONNECTED -> Fx.Success; WorkerStatus.CONNECTING, WorkerStatus.MODEL_UNAVAILABLE -> Fx.Warning; WorkerStatus.NOT_CONFIGURED -> Fx.TextTertiary; else -> Fx.Danger }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 10.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(w.status.label, style = MaterialTheme.typography.titleMedium)
                Text(w.detail, style = MaterialTheme.typography.bodySmall)
            }
            if (w.status != WorkerStatus.NOT_CONFIGURED) IconButton(onClick = actions::checkWorkerNow) { Icon(FxIcons.Refresh, "Check connection", tint = Fx.TextSecondary) }
        }
        var url by remember(w.url) { mutableStateOf(w.url.removePrefix("http://")) }
        var token by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        FxTextField(url, { url = it; error = null }, "PC address", placeholder = "192.168.1.20:8765", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        FxTextField(token, { token = it; error = null }, if (w.tokenSaved) "Pairing token (saved — leave blank to keep)" else "Pairing token", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Notice(it, Fx.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxPrimaryButton("Save & test", { error = actions.saveWorker(url, token.ifBlank { null }); if (error == null) token = "" }, enabled = url.isNotBlank() && (token.isNotBlank() || w.tokenSaved))
            if (w.url.isNotBlank()) FxSecondaryButton("Forget", actions::forgetWorker, danger = true)
        }
        HorizontalDivider(color = Fx.Outline)
        ToggleRow("Use Windows worker for replies", if (w.tokenSaved) "Switches the inference engine to your PC" else "Save the address and token first", state.backend == InferenceBackend.WINDOWS_WORKER, enabled = w.tokenSaved) {
            actions.setBackend(if (it) InferenceBackend.WINDOWS_WORKER else InferenceBackend.ON_DEVICE)
        }
        ToggleRow("Share document & Google context with PC", "Off by default. When on, matching excerpts from scanned documents and the Google cache are sent to your PC with the prompt.", w.shareContext, onChange = actions::setWorkerShareContext)
    }
    FxCard {
        Text("Set up the PC", style = MaterialTheme.typography.titleSmall)
        Text("1. Install Ollama and run: ollama pull qwen2.5:3b\n2. Double-click windows-worker\\start-friday.bat\n3. Copy the pairing token from that window\n4. Allow Python on Private networks in Windows Firewall\n5. Enter the PC's Wi-Fi IPv4 address (from ipconfig) above", style = MaterialTheme.typography.bodySmall)
        Text("Plain HTTP is accepted only for private network addresses, and every request carries the pairing token. Use the worker only on a trusted home network.", style = MaterialTheme.typography.bodySmall, color = Fx.Warning)
    }
    SectionLabel("Google account")
    FxCard {
        Text(if (state.googleConnected) "Connected • local snapshot available" else state.googleStatus, style = MaterialTheme.typography.titleSmall, color = if (state.googleConnected) Fx.Success else Fx.Text)
        Text("Read-only access to Drive, Gmail, Calendar and Contacts. Only matching cached lines are added to relevant prompts.", style = MaterialTheme.typography.bodySmall)
        FxPrimaryButton(if (state.googleConnected) "Refresh Google data" else "Connect Google (read only)", hooks.connectGoogle)
        hooks.googleError?.let { Notice(it, Fx.Danger) }
    }
}

// ---------------------------------------------------------------------------- Privacy
@Composable
private fun PrivacyPage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    FxCard {
        Text("What stays on this phone", style = MaterialTheme.typography.titleSmall)
        Text("• Chat history, personal profile (encrypted), worker token (encrypted), document index.\n• Microphone audio goes only to Android's speech recognizer and is not recorded or stored by FRIDAY.\n• With the Windows worker active, your prompt and recent conversation are sent to your PC over your home network.\n• No cloud AI provider is connected.", style = MaterialTheme.typography.bodySmall)
    }
    SectionLabel("Document intelligence")
    FxCard {
        KeyValue("Broad storage", if (hooks.allFilesGranted) "Granted" else "Not granted", if (hooks.allFilesGranted) Fx.Success else Fx.TextSecondary)
        KeyValue("Folder access", hooks.folderLabel)
        Text("FRIDAY extracts text locally from TXT, MD, CSV, JSON, XML, HTML, LOG, PDF, DOCX, XLSX, PPTX and images (OCR).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FxSecondaryButton(if (hooks.allFilesGranted) "Manage access" else "Grant access", hooks.grantAllFiles)
            FxSecondaryButton("Pick folder", hooks.pickFolder)
        }
        FxPrimaryButton(if (state.scanningDocuments) "Scanning…" else "Scan phone documents", actions::scanLocalDocuments, enabled = !state.scanningDocuments)
        if (state.scanningDocuments || state.documentProgress > 0) {
            LinearProgressIndicator(progress = { state.documentProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Fx.Blue, trackColor = Fx.SurfaceHigher)
            Text("${state.documentsProcessed}/${state.documentsTotal} files • ${state.documentsExtracted} extracted • ${state.documentsFailed} failed", style = MaterialTheme.typography.bodySmall)
            if (state.documentCurrentFile.isNotBlank()) Text("Current: ${state.documentCurrentFile}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(state.documentStatus, style = MaterialTheme.typography.bodySmall)
        state.localDocuments.take(12).forEach { doc -> Text("• ${doc.name} · ${if (doc.content.isNotBlank()) "searchable" else "not extracted"}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (state.localDocuments.size > 12) Text("…and ${state.localDocuments.size - 12} more", style = MaterialTheme.typography.bodySmall)
    }
    SectionLabel("Delete data")
    var confirmClear by remember { mutableStateOf(false) }
    FxCard {
        FxSecondaryButton("Delete Google cache", actions::disconnectGoogle, danger = true, icon = FxIcons.Trash)
        FxSecondaryButton("Clear chat history", { confirmClear = true }, danger = true, icon = FxIcons.Trash)
        FxSecondaryButton("Erase personal profile", actions::clearPersonalProfile, danger = true, icon = FxIcons.Trash)
    }
    if (confirmClear) ConfirmDialog("Clear chat history?", "This permanently deletes the locally stored conversation.", "Clear", onConfirm = { actions.clearMemory(); confirmClear = false }, onDismiss = { confirmClear = false })
}

// ---------------------------------------------------------------------------- Advanced
@Composable
private fun AdvancedPage(state: FridayState, actions: FridayActions, hooks: PlatformHooks) {
    FxCard {
        Text("Performance", style = MaterialTheme.typography.titleSmall)
        KeyValue("Last request", state.performanceStatus)
        KeyValue("Prompt / first token", state.promptProcessingStatus)
        KeyValue("Generation", state.generationStatus)
        KeyValue("Accelerator", state.modelBackendName.ifBlank { "—" })
        FxSecondaryButton("Benchmark on-device model", actions::benchmarkSelectedModel, enabled = state.modelReady && !state.busy)
    }
    FxCard {
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        Text("Device, model, voice, worker and permission status. Excludes chat, documents and secrets.", style = MaterialTheme.typography.bodySmall)
        FxSecondaryButton("Open diagnostics report", hooks.openDiagnostics, icon = FxIcons.Info)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Fx.SurfaceHigh, title = { Text(title) }, text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Fx.TextSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Fx.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Fx.TextSecondary) } })
}
