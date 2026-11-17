package com.friday.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.app.ChatLine
import com.friday.app.FridayState
import com.friday.app.core.AppLanguage
import com.friday.app.core.InferenceBackend
import com.friday.app.core.Markdown
import com.friday.app.core.VoiceMode
import com.friday.app.core.VoiceState
import com.friday.app.worker.WorkerStatus
import kotlinx.coroutines.launch

/** Connection indicator: (label, color) for the active backend. */
fun connectionStatus(s: FridayState): Pair<String, Color> = when (s.backend) {
    InferenceBackend.ON_DEVICE -> when {
        s.downloading -> "Downloading model" to Fx.Warning
        s.modelReady -> "On-device ready" to Fx.Success
        s.status.contains("Loading", true) -> "Loading model" to Fx.Warning
        else -> "Model not loaded" to Fx.Danger
    }
    InferenceBackend.WINDOWS_WORKER -> s.worker.status.label to when (s.worker.status) {
        WorkerStatus.CONNECTED -> Fx.Success
        WorkerStatus.CONNECTING -> Fx.Warning
        WorkerStatus.MODEL_UNAVAILABLE -> Fx.Warning
        else -> Fx.Danger
    }
}

@Composable
fun HomeScreen(
    state: FridayState,
    actions: FridayActions,
    onOpenSettings: (SettingsPage?) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val messages = state.messages
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val lastLength = messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    LaunchedEffect(lastLength) { if (messages.isNotEmpty() && atBottom) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 2) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Fx.BackgroundTop, Fx.Background, Fx.Background))), contentAlignment = Alignment.TopCenter) {
        // Readable line length on tablets / foldables: content is capped and centred.
        Column(Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(state, onOpenSettings)
            StatusStrip(state, actions, onOpenSettings)
            if (messages.isEmpty()) {
                EmptyHero(state, actions, Modifier.weight(1f))
            } else {
                CompactPresence(state)
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("conversation"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(messages, key = { i, m -> "$i-${m.role}-${m.kind}" }) { i, m ->
                            MessageItem(m, state, isLast = i == messages.lastIndex, actions = actions)
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(!atBottom, Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
                        Surface(shape = CircleShape, color = Fx.SurfaceHigher, border = BorderStroke(1.dp, Fx.OutlineStrong), modifier = Modifier.size(40.dp).clickable { scope.launch { listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE / 2) } }) {
                            Box(contentAlignment = Alignment.Center) { Icon(FxIcons.Send, "Jump to latest", tint = Fx.Text, modifier = Modifier.size(18.dp).alpha(.9f).graphicsRotate(180f)) }
                        }
                    }
                }
            }
            VoiceBanner(state, actions, onOpenAppSettings)
            state.error?.takeIf { state.voice.state != VoiceState.ERROR }?.let { err ->
                Notice(err.take(240), Fx.Danger, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TextButton(onClick = actions::clearError) { Text("Dismiss", color = Fx.TextSecondary) }
                }
            }
            Composer(
                state = state,
                input = input,
                onInput = { input = it },
                onSend = { val t = input.trim(); if (t.isNotEmpty()) { actions.send(t); input = "" } },
                actions = actions,
                onOpenCamera = onOpenCamera
            )
        }
    }
}

private fun Modifier.graphicsRotate(deg: Float): Modifier = this.graphicsLayer { rotationZ = deg }

@Composable
private fun TopBar(state: FridayState, onOpenSettings: (SettingsPage?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FRIDAY", style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 6.sp, fontWeight = FontWeight.SemiBold), color = Fx.Text)
            Text("Personal AI • private by design", style = MaterialTheme.typography.labelMedium, color = Fx.TextTertiary)
        }
        IconButton(onClick = { onOpenSettings(null) }, modifier = Modifier.semantics { contentDescription = "Open settings" }) {
            Icon(FxIcons.Tune, null, tint = Fx.Text)
        }
    }
}

@Composable
private fun StatusStrip(state: FridayState, actions: FridayActions, onOpenSettings: (SettingsPage?) -> Unit) {
    var personalityMenu by remember { mutableStateOf(false) }
    val (conn, connColor) = connectionStatus(state)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(if (state.backend == InferenceBackend.ON_DEVICE) "PHONE" else "WINDOWS PC", conn, dot = connColor, onClick = { onOpenSettings(if (state.backend == InferenceBackend.ON_DEVICE) SettingsPage.AI_MODELS else SettingsPage.CONNECTIVITY) }, modifier = Modifier.testTag("chip-connection"))
        StatusChip("MODEL", state.activeModelLabel, icon = FxIcons.Chip, onClick = { onOpenSettings(SettingsPage.AI_MODELS) }, modifier = Modifier.widthIn(max = 200.dp))
        StatusChip("LANGUAGE", state.language.nativeName, icon = FxIcons.Globe, onClick = { actions.setLanguage(if (state.language == AppLanguage.ENGLISH) AppLanguage.TELUGU else AppLanguage.ENGLISH) }, modifier = Modifier.testTag("chip-language"))
        Box {
            StatusChip("PERSONALITY", state.activePersonality?.name ?: "—", icon = FxIcons.Sparkle, onClick = { personalityMenu = true }, modifier = Modifier.testTag("chip-personality"))
            DropdownMenu(personalityMenu, onDismissRequest = { personalityMenu = false }, containerColor = Fx.SurfaceHigh) {
                state.personalities.forEach { p ->
                    DropdownMenuItem(
                        text = { Column { Text(p.name, color = Fx.Text); if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        trailingIcon = { if (p.id == state.activePersonalityId) Icon(FxIcons.Check, null, tint = Fx.Cyan) },
                        onClick = { actions.activatePersonality(p.id); personalityMenu = false }
                    )
                }
                DropdownMenuItem(text = { Text("Manage personalities…", color = Fx.Cyan) }, onClick = { personalityMenu = false; onOpenSettings(SettingsPage.PERSONALITY) })
            }
        }
        StatusChip("VOICE", state.voiceMode.label, icon = if (state.voiceMode == VoiceMode.TEXT_ONLY) FxIcons.Keyboard else FxIcons.Wave, onClick = { onOpenSettings(SettingsPage.VOICE) })
    }
}

private fun greeting(lang: AppLanguage) = if (lang == AppLanguage.TELUGU) "నేను మీకు ఎలా సహాయం చేయగలను?" else "How can I help?"

@Composable
private fun EmptyHero(state: FridayState, actions: FridayActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VoiceOrb(state.voice.state, state.voice.level, Modifier.size(210.dp).testTag("voice-orb"))
        Text(voiceStateLabel(state.voice.state).uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp), color = voiceStateColor(state.voice.state))
        Spacer(Modifier.height(10.dp))
        Text(greeting(state.language), style = scriptAwareStyle(greeting(state.language), MaterialTheme.typography.headlineSmall, 24), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.voiceMode == VoiceMode.TEXT_ONLY) "Type a message below." else "Tap the microphone and speak, or type below. Try “Switch to Telugu” or “Change personality to JARVIS”.",
            style = MaterialTheme.typography.bodyMedium, color = Fx.TextSecondary, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Test voice", FxIcons.Speaker, Modifier.weight(1f)) { actions.testVoice(state.replyLanguage) }
            QuickAction(if (state.language == AppLanguage.ENGLISH) "తెలుగు" else "English", FxIcons.Globe, Modifier.weight(1f)) { actions.setLanguage(if (state.language == AppLanguage.ENGLISH) AppLanguage.TELUGU else AppLanguage.ENGLISH) }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(color = Fx.Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Fx.Outline), modifier = modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Fx.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Fx.Text)
        }
    }
}

@Composable
private fun CompactPresence(state: FridayState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        VoiceOrb(state.voice.state, state.voice.level, Modifier.size(44.dp).testTag("voice-orb"))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(voiceStateLabel(state.voice.state), style = MaterialTheme.typography.titleSmall, color = voiceStateColor(state.voice.state))
            val sub = when {
                state.busy && state.voice.state != VoiceState.SPEAKING -> "Generating on ${if (state.backend == InferenceBackend.ON_DEVICE) "phone" else "Windows PC"}…"
                state.voice.state == VoiceState.IDLE -> "${state.activePersonality?.name ?: ""} • ${state.replyLanguage.nativeName}"
                else -> state.voice.detail
            }
            Text(sub, style = scriptAwareStyle(sub, MaterialTheme.typography.bodySmall, 12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessageItem(m: ChatLine, state: FridayState, isLast: Boolean, actions: FridayActions) {
    val clipboard = LocalClipboardManager.current
    val font = state.chatFontSize
    when {
        m.kind == ChatLine.KIND_ERROR -> Notice(m.text, Fx.Danger)
        m.kind == ChatLine.KIND_COMMAND && m.role == "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color.Transparent, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Fx.Cyan.copy(alpha = .4f))) {
                Text(m.text, style = scriptAwareStyle(m.text, MaterialTheme.typography.bodyMedium, font - 1), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        m.kind == ChatLine.KIND_COMMAND -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FxIcons.Check, null, tint = Fx.Success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(m.text, style = scriptAwareStyle(m.text, MaterialTheme.typography.bodyMedium.copy(color = Fx.TextSecondary), font - 1))
        }
        m.role == "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Fx.UserBubble, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp), modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(0.86f).wrapContentWidth(Alignment.End)) {
                SelectionContainer { Text(m.text, style = scriptAwareStyle(m.text, MaterialTheme.typography.bodyLarge, font), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Fx.Cyan, 6.dp)
                Spacer(Modifier.width(8.dp))
                Text("FRIDAY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Fx.TextTertiary)
            }
            Spacer(Modifier.height(6.dp))
            if (m.text.isBlank() && state.busy && isLast) TypingIndicator()
            else SelectionContainer { MarkdownText(m.text, font, Modifier.fillMaxWidth().testTag("assistant-text")) }
            val streaming = state.busy && isLast
            if (!streaming && m.text.isNotBlank()) {
                Row(Modifier.padding(top = 2.dp)) {
                    SmallAction(FxIcons.Copy, "Copy") { clipboard.setText(AnnotatedString(Markdown.toPlainText(m.text))) }
                    if (state.voiceMode != VoiceMode.TEXT_ONLY) SmallAction(FxIcons.Speaker, "Read aloud") { actions.speakMessage(m.text) }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Fx.TextTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Fx.TextTertiary)
    }
}

@Composable
private fun TypingIndicator() {
    val t = rememberInfiniteTransition(label = "typing")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "FRIDAY is thinking" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(Fx.Cyan.copy(alpha = if (p.toInt() == i) 1f else .3f))) }
    }
}

@Composable
private fun VoiceBanner(state: FridayState, actions: FridayActions, onOpenAppSettings: () -> Unit) {
    val v = state.voice
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (v.state) {
        VoiceState.LISTENING -> Surface(color = Fx.Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Fx.Cyan.copy(alpha = .45f)), modifier = pad.fillMaxWidth().testTag("voice-banner")) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Listening · ${state.language.nativeName}", style = MaterialTheme.typography.labelMedium, color = Fx.Cyan, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions::cancelListening) { Text("Cancel", color = Fx.TextSecondary) }
                }
                val heard = v.detail.takeIf { it.isNotBlank() && it != "Listening…" }
                Text(heard?.let { "“$it”" } ?: "Speak now…", style = scriptAwareStyle(heard ?: "", MaterialTheme.typography.bodyLarge.copy(color = if (heard != null) Fx.Text else Fx.TextTertiary), 17))
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { v.level.coerceIn(0.03f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = Fx.Cyan, trackColor = Fx.SurfaceHigher, drawStopIndicator = {})
            }
        }
        VoiceState.SPEAKING -> Row(pad.fillMaxWidth().testTag("voice-banner"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (v.paused) "Paused" else "Speaking…", style = MaterialTheme.typography.labelLarge, color = Fx.Cyan, modifier = Modifier.weight(1f))
            if (v.pausable) FxSecondaryButton(if (v.paused) "Resume" else "Pause", actions::togglePauseSpeaking, icon = if (v.paused) FxIcons.Play else FxIcons.Pause)
            FxSecondaryButton("Stop", actions::stopSpeaking, icon = FxIcons.Stop)
        }
        VoiceState.ERROR -> {
            val permission = v.detail.contains("permission", true)
            Notice(v.detail.ifBlank { "Something went wrong." }, Fx.Warning, pad.testTag("voice-banner")) {
                Row {
                    if (permission) TextButton(onClick = onOpenAppSettings) { Text("Settings", color = Fx.Cyan) }
                    else TextButton(onClick = actions::onMicPressed) { Text("Retry", color = Fx.Cyan) }
                    TextButton(onClick = actions::dismissVoiceError) { Text("Dismiss", color = Fx.TextSecondary) }
                }
            }
        }
        else -> {
            if (v.state == VoiceState.IDLE && v.detail.isNotBlank() && v.detail != "Stopped") {
                Text(v.detail, style = MaterialTheme.typography.bodySmall, modifier = pad)
            }
        }
    }
}

@Composable
private fun Composer(state: FridayState, input: String, onInput: (String) -> Unit, onSend: () -> Unit, actions: FridayActions, onOpenCamera: () -> Unit) {
    val voiceEnabled = state.voiceMode != VoiceMode.TEXT_ONLY
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Fx.Surface).border(1.dp, Fx.OutlineStrong, RoundedCornerShape(26.dp)).padding(start = 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenCamera, enabled = !state.busy) { Icon(FxIcons.Camera, "Open camera vision", tint = Fx.TextSecondary) }
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (input.isEmpty()) Text(if (state.language == AppLanguage.TELUGU) "ఫ్రైడేకి సందేశం…" else "Message FRIDAY…", style = MaterialTheme.typography.bodyLarge, color = Fx.TextTertiary)
                BasicTextField(
                    value = input, onValueChange = onInput, maxLines = 6,
                    textStyle = scriptAwareStyle(input, MaterialTheme.typography.bodyLarge, 16),
                    cursorBrush = SolidColor(Fx.Cyan),
                    modifier = Modifier.fillMaxWidth().testTag("composer-input").semantics { contentDescription = "Message input" }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val (icon, desc, bg, onClick) = when {
            state.busy -> Quad(FxIcons.Stop, "Stop generating", Fx.Danger, actions::stopGeneration)
            input.isNotBlank() -> Quad(FxIcons.Send, "Send message", Fx.Blue, onSend)
            state.voice.state == VoiceState.SPEAKING -> Quad(FxIcons.Stop, "Stop speaking", Fx.SurfaceHigher, actions::stopSpeaking)
            !voiceEnabled -> Quad(FxIcons.Send, "Send message", Fx.SurfaceHigher, onSend)
            state.voice.state == VoiceState.LISTENING -> Quad(FxIcons.Check, "Finish listening", Fx.Cyan, actions::onMicPressed)
            else -> Quad(FxIcons.Mic, "Start voice input", Fx.Blue, actions::onMicPressed)
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc }.testTag("primary-action"),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = if (bg == Fx.Cyan) Fx.Background else Color.White, modifier = Modifier.size(22.dp)) }
    }
}

private data class Quad(val icon: ImageVector, val desc: String, val bg: Color, val onClick: () -> Unit)
