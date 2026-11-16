package com.friday.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val Ink = Color(0xFF07111F)
private val Panel = Color(0xFF101F32)
private val Cyan = Color(0xFF55D6E8)
private val Muted = Color(0xFF9EB2C7)

class MainActivity : ComponentActivity() {
    private val fridayViewModel: FridayViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayApp(fridayViewModel) }
    }
}

@Composable
fun FridayApp(vm: FridayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val input = remember { mutableStateOf("") }
    val voiceStatus = remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voiceStatus.value = "Listening…" }
            override fun onBeginningOfSpeech() { voiceStatus.value = "Hearing you…" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voiceStatus.value = "" }
            override fun onError(error: Int) { voiceStatus.value = "Voice recognition error (" + error + ")" }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) input.value = spoken
                voiceStatus.value = ""
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val spoken = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) input.value = spoken
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer?.destroy() }
    }

    DisposableEffect(context) {
        val tts = TextToSpeech(context) { }
        tts.language = Locale.getDefault()
        ttsRef.value = tts
        onDispose { tts.stop(); tts.shutdown(); ttsRef.value = null }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && speechRecognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer.startListening(intent)
        } else voiceStatus.value = if (speechRecognizer == null) "Speech recognition is unavailable" else "Microphone permission denied"
    }

    val latestAssistant = state.messages.lastOrNull()?.takeIf { it.role == "assistant" && it.text.isNotBlank() }?.text
    LaunchedEffect(latestAssistant, state.speakReplies) {
        if (state.speakReplies && !state.busy && !latestAssistant.isNullOrBlank()) {
            ttsRef.value?.speak(latestAssistant, TextToSpeech.QUEUE_FLUSH, null, "friday-reply")
        }
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10263A), Ink))).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Box(Modifier.size(44.dp).background(Cyan.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("F", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("FRIDAY", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("ON-DEVICE PERSONAL AI", color = Muted, fontSize = 9.sp, letterSpacing = 1.7.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(color = if (state.modelReady) Color(0xFF143D3C) else Color(0xFF3B2B20), shape = RoundedCornerShape(30.dp)) {
                    Text(if (state.modelReady) "● READY" else "● SETUP",
                        color = if (state.modelReady) Color(0xFF7DE2C3) else Color(0xFFFFC38B),
                        fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Your AI. Your device.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text(state.status, color = Muted, fontSize = 11.sp)
            if (!state.modelReady) {
                Spacer(Modifier.height(12.dp))
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Text("ON-DEVICE MODEL", color = Cyan, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Qwen3 0.6B • approximately 586 MB", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Downloads once and stays in private app storage. Keep at least 1.5 GB free for model and runtime cache.", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        if (state.downloading) {
                            LinearProgressIndicator(progress = { state.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Cyan, trackColor = Color(0xFF263B50))
                            Text("Downloading " + state.downloadProgress + "%", color = Muted, fontSize = 11.sp)
                        } else {
                            Button(onClick = { vm.downloadModel() }, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan), shape = RoundedCornerShape(12.dp)) {
                                Text("Download local AI model", color = Ink, fontWeight = FontWeight.Bold)
                            }
                        }
                        state.error?.let { Text(it, color = Color(0xFFFFA6A6), fontSize = 11.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speak replies", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = state.speakReplies, onCheckedChange = vm::setSpeakReplies)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow cloud fallback (approval required)", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = state.cloudApprovalEnabled, onCheckedChange = vm::setCloudApproval)
            }
            Text("Cloud provider is not connected yet. This build sends no messages to cloud services.", color = Muted, fontSize = 9.sp)
            Spacer(Modifier.height(8.dp))
            Text("CONVERSATION • STORED LOCALLY", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.messages) { _, line ->
                    Surface(color = if (line.role == "user") Color(0xFF17364A) else Panel, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(if (line.role == "user") "YOU" else "FRIDAY", color = Cyan, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(if (line.text.isBlank() && state.busy) "Thinking…" else line.text, color = Color(0xFFE5F0FA), fontSize = 14.sp)
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = Color(0xFFFFA6A6), fontSize = 11.sp) }
            if (voiceStatus.value.isNotBlank()) Text(voiceStatus.value, color = Cyan, fontSize = 11.sp)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan, trackColor = Panel)
                Spacer(Modifier.height(5.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input.value, onValueChange = { input.value = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask FRIDAY…", color = Muted) }, maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF35516A), cursorColor = Cyan,
                        focusedContainerColor = Panel, unfocusedContainerColor = Panel), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !state.busy) {
                    Text("🎙", fontSize = 22.sp)
                }
                Button(onClick = {
                    val text = input.value.trim()
                    if (text.isNotEmpty()) { vm.send(text); input.value = "" }
                }, enabled = state.modelReady && !state.busy && input.value.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan), shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("↑", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("LOCAL MEMORY • LOCAL INFERENCE • CLOUD OFF BY DEFAULT", color = Muted, fontSize = 8.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}