package com.friday.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.Identity
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val Night = Color(0xFF020B18)
private val DeepBlue = Color(0xFF06172A)
private val Neon = Color(0xFF00E5F2)
private val SoftCyan = Color(0xFF00AFC4)
private val Muted = Color(0xFF8CA8BC)

class MainActivity : ComponentActivity() {
    private val fridayViewModel: FridayViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(2, 11, 24)
        window.navigationBarColor = android.graphics.Color.rgb(2, 11, 24)
        setContent { FridayApp(fridayViewModel) }
    }
}

@Composable
fun FridayApp(vm: FridayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var setupOpen by remember { mutableStateOf(false) }
    var textOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var googleActionError by remember { mutableStateOf<String?>(null) }
    var clearMemoryConfirm by remember { mutableStateOf(false) }
    val googleAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        if (data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }
                .onSuccess { token ->
                    if (!token.isNullOrBlank()) vm.syncGoogleContext(token)
                    else googleActionError = "Google returned no access token. Check that the requested scopes are enabled for this OAuth consent screen."
                }
                .onFailure { error ->
                    googleActionError = "Google authorization failed: ${error.message ?: error.javaClass.simpleName}. Check the Android OAuth package/SHA-1 and Google Cloud test-user settings."
                }
        } else {
            googleActionError = if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
                "Google account/consent flow returned no result. Check Google Play services, confirm a Google account is added to Android, then verify the OAuth Android client uses package com.friday.app and this APK's signing SHA-1."
            } else {
                "Google authorization returned no result (code ${result.resultCode}). Check Google Play services and OAuth configuration."
            }
        }
    }
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voiceStatus = "LISTENING" }
            override fun onBeginningOfSpeech() { voiceStatus = "HEARING YOU" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voiceStatus = "PROCESSING…" }
            override fun onError(error: Int) { voiceStatus = "VOICE ERROR • $error" }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) vm.send(spoken)
                voiceStatus = ""
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer?.destroy() }
    }


    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && speechRecognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            speechRecognizer.startListening(intent)
        } else voiceStatus = if (speechRecognizer == null) "SPEECH SERVICE UNAVAILABLE" else "MICROPHONE PERMISSION DENIED"
    }

    val latestAssistant = state.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF03172A), Night, Color(0xFF010713))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (state.modelReady) Color(0xFF06323B) else Color(0xFF352A21), shape = RoundedCornerShape(30.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.modelReady) Neon else Color(0xFFFFB36B)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.modelReady) "READY" else "SETUP", color = if (state.modelReady) Neon else Color(0xFFFFC58F), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("FRIDAY", color = Neon, fontSize = 21.sp, fontWeight = FontWeight.Light, letterSpacing = 5.sp)
                Spacer(Modifier.width(4.dp))
                Text("⋮", color = Muted, fontSize = 24.sp, modifier = Modifier.clickable { setupOpen = true }.padding(horizontal = 4.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FridayOrb(active = state.busy || voiceStatus.isNotBlank())
                if (!latestAssistant.isNullOrBlank()) {
                    Surface(
                        color = Color(0xB3091B2D),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(latestAssistant, color = Color(0xFFD8F7FA), fontSize = 13.sp, lineHeight = 19.sp,
                            maxLines = 4, modifier = Modifier.padding(14.dp), textAlign = TextAlign.Center)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    color = Color(0xB307172A),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF12334A)),
                    modifier = Modifier.size(width = 250.dp, height = 205.dp)
                ) {
                    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(
                            Modifier.size(108.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF07566A), Color(0xFF03182B))))
                                .clickable(enabled = !state.busy) { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.fillMaxSize().padding(2.dp).clip(CircleShape).background(Color.Transparent))
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(Neon.copy(alpha = .9f), style = Stroke(width = 2.dp.toPx()))
                                drawCircle(Neon.copy(alpha = .13f), radius = size.minDimension * .38f)
                            }
                            Text("♩", color = Neon, fontSize = 42.sp, fontWeight = FontWeight.Light)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(if (state.busy) "THINKING…" else if (voiceStatus.isNotBlank()) voiceStatus else "Tap to Command",
                            color = Neon, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(if (state.voiceModelReady) "KOKORO OFFLINE VOICE • READY" else if (state.systemVoiceReady) "ANDROID FALLBACK VOICE • READY" else "VOICE ENGINE • INITIALIZING",
                            color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌨", color = Muted, fontSize = 20.sp, modifier = Modifier.clickable { textOpen = true }.padding(10.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("PRIVATE BY DESIGN  •  CLOUD OFF", color = Muted, fontSize = 9.sp, letterSpacing = 1.6.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("⚙", color = Muted, fontSize = 19.sp, modifier = Modifier.clickable { setupOpen = true }.padding(10.dp))
                }
                state.error?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp, textAlign = TextAlign.Center) }
            }
        }
    }

    if (setupOpen) {
        AlertDialog(
            onDismissRequest = { setupOpen = false },
            containerColor = Color(0xFF0B1C2E),
            title = { Text("FRIDAY setup", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ON-DEVICE MODEL", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("Qwen3 0.6B • approximately 614 MB", color = Color.White, fontSize = 14.sp)
                    Text(state.status, color = Muted, fontSize = 12.sp)
                    Text(state.performanceStatus, color = SoftCyan, fontSize = 10.sp, lineHeight = 15.sp)
                    HorizontalDivider(color = Color(0xFF1C354A))
                    Text("VOICE OUTPUT", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(state.voiceStatus, color = Muted, fontSize = 12.sp)
                    Button(onClick = { vm.testVoice() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("Test voice", color = Night) }
                    if (state.voiceDownloading) {
                        LinearProgressIndicator(progress = { state.voiceDownloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neon)
                        Text("Downloading voice model: " + state.voiceDownloadProgress + "%", color = Muted, fontSize = 12.sp)
                    } else if (!state.voiceModelReady) {
                        Button(onClick = { vm.downloadVoiceModel() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                            Text("Download offline Kokoro voice", color = Night)
                        }
                    }
                    if (state.downloading) {
                        LinearProgressIndicator(progress = { state.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neon)
                        Text("Downloading ${state.downloadProgress}%", color = Muted, fontSize = 12.sp)
                    } else if (!state.modelReady) {
                        Button(onClick = { vm.downloadModel() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                            Text("Download local AI model", color = Night)
                        }
                    }
                    state.error?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp) }
                    HorizontalDivider(color = Color(0xFF1C354A))
                    Text("GOOGLE ACCOUNT & PERSONAL CONTEXT", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(state.googleStatus, color = Muted, fontSize = 12.sp)
                    Text("Choose your Google account, then approve read-only access to Drive, Gmail, Calendar and Contacts. Tokens stay in memory only. OAuth must match package com.friday.app and this APK signing SHA-1.", color = Muted, fontSize = 10.sp)
                    Button(onClick = {
                        googleActionError = null
                        Identity.getAuthorizationClient(context).authorize(GoogleContextSync.authorizationRequest())
                            .addOnSuccessListener { auth ->
                                if (auth.hasResolution()) {
                                    val pending = auth.pendingIntent
                                    if (pending != null) googleAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending).build())
                                    else googleActionError = "Google consent screen unavailable"
                                } else if (!auth.accessToken.isNullOrBlank()) vm.syncGoogleContext(auth.accessToken!!)
                                else googleActionError = "Google returned no access token"
                            }
                            .addOnFailureListener { error -> googleActionError = "Google authorization failed: ${error.message ?: error.javaClass.simpleName}" }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("Sign in with Google • Read only", color = Night) }
                    TextButton(onClick = { vm.disconnectGoogle() }) { Text("Delete local Google cache", color = Color(0xFFFF9CA9)) }
                    googleActionError?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp) }
                    HorizontalDivider(color = Color(0xFF1C354A))
                    TextButton(onClick = { clearMemoryConfirm = true }) { Text("Clear local chat history", color = Color(0xFFFF9CA9)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.voiceModelReady) "Speak replies (downloaded Kokoro voice)" else "Speak replies (Android fallback)", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(checked = state.speakReplies, onCheckedChange = vm::setSpeakReplies)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Cloud fallback approval", color = Color.White)
                            Text("Provider not connected; no cloud requests are sent.", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = state.cloudApprovalEnabled, onCheckedChange = vm::setCloudApproval)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { setupOpen = false }) { Text("CLOSE", color = Neon) } }
        )
    }

    if (clearMemoryConfirm) {
        AlertDialog(
            onDismissRequest = { clearMemoryConfirm = false },
            containerColor = Color(0xFF0B1C2E),
            title = { Text("Clear local chat history?", color = Color.White) },
            text = { Text("This permanently deletes FRIDAY's locally stored conversation history from this app. It does not delete the separate Google context cache.", color = Muted) },
            confirmButton = {
                TextButton(onClick = { vm.clearMemory(); clearMemoryConfirm = false }) {
                    Text("CLEAR HISTORY", color = Color(0xFFFF9CA9))
                }
            },
            dismissButton = { TextButton(onClick = { clearMemoryConfirm = false }) { Text("CANCEL", color = Neon) } }
        )
    }

    if (textOpen) {
        AlertDialog(
            onDismissRequest = { textOpen = false },
            containerColor = Color(0xFF0B1C2E),
            title = { Text("Message FRIDAY", color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("Type a command…") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val message = input.trim()
                    if (message.isNotEmpty()) vm.send(message)
                    input = ""
                    textOpen = false
                }, enabled = input.isNotBlank() && state.modelReady && !state.busy) { Text("SEND", color = Neon) }
            },
            dismissButton = { TextButton(onClick = { textOpen = false }) { Text("CANCEL", color = Muted) } }
        )
    }
}

@Composable
private fun FridayOrb(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "friday-orb")
    val pulse by transition.animateFloat(initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(if (active) 900 else 2600), RepeatMode.Reverse), label = "pulse")
    val rotation by transition.animateFloat(initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000), RepeatMode.Restart), label = "rotation")

    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .31f * pulse
        drawCircle(Brush.radialGradient(listOf(Color(0x5530EFFF), Color(0x2222CFEA), Color.Transparent), center, radius * 2.35f), radius * 2.35f, center)
        drawCircle(Brush.radialGradient(listOf(Color(0xAA00DCEB), Color(0x6620B6C7), Color(0x22004C65), Color.Transparent), center, radius * 1.9f), radius * 1.9f, center)
        drawCircle(Color(0x2200EAF5), radius * 1.42f, center, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(Color(0x5535E9F2), radius * 1.08f, center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF35F5F4), Color(0xFF0796A9), Color(0xFF031B30)), center, radius), radius, center)
        drawCircle(Color(0x5535F5FF), radius * .58f, center, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(Color(0x4400EAF5), radius * .16f, Offset(center.x - radius * .24f, center.y - radius * .48f))
        val orbit = radius * 1.48f
        for (i in 0 until 9) {
            val angle = Math.toRadians((rotation + i * 40.0))
            val x = center.x + kotlin.math.cos(angle).toFloat() * orbit
            val y = center.y + kotlin.math.sin(angle).toFloat() * orbit
            val dotColor = if (i % 3 == 0) Color(0xFFB83FAF) else Neon
            drawCircle(dotColor.copy(alpha = if (active) .95f else .55f), if (i % 3 == 0) 5.dp.toPx() else 3.dp.toPx(), Offset(x, y))
            drawCircle(dotColor.copy(alpha = .14f), 10.dp.toPx(), Offset(x, y))
        }
        drawArc(Color(0x3320EAF5), rotation, 135f, false, center - androidx.compose.ui.geometry.Size(radius * 1.62f, radius * 1.62f).let { Offset(it.width / 2f, it.height / 2f) },
            androidx.compose.ui.geometry.Size(radius * 1.62f, radius * 1.62f), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
    }
}
