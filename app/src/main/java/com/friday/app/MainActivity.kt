package com.friday.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var debugOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var googleActionError by remember { mutableStateOf<String?>(null) }
    var clearMemoryConfirm by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var selectedFolderLabel by remember { mutableStateOf(context.getSharedPreferences("friday_storage", 0).getString("tree_uri", null)?.let { "A folder is linked" } ?: "No folder selected") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allFilesGranted = Environment.isExternalStorageManager() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.getSharedPreferences("friday_storage", 0).edit().putString("tree_uri", uri.toString()).apply()
            selectedFolderLabel = "Folder access granted"
        }.onFailure { selectedFolderLabel = "Folder access failed: ${it.message}" }
    }
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
            override fun onError(error: Int) {
                voiceStatus = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "MICROPHONE AUDIO ERROR"
                    SpeechRecognizer.ERROR_CLIENT -> "SPEECH SERVICE CLIENT ERROR"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MICROPHONE PERMISSION DENIED"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "SPEECH SERVICE NETWORK ERROR"
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO SPEECH RECOGNIZED • TRY AGAIN"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "SPEECH SERVICE BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "SPEECH SERVICE SERVER ERROR"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO SPEECH DETECTED"
                    else -> "SPEECH ERROR • $error"
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    if (state.modelReady) vm.send(spoken) else voiceStatus = "LOCAL MODEL IS NOT READY"
                    if (state.modelReady) voiceStatus = ""
                } else voiceStatus = "NO SPEECH RECOGNIZED • TAP MIC AND TRY AGAIN"
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

    val chatListState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val debugReport = remember(state, voiceStatus, allFilesGranted, selectedFolderLabel) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val storage = StatFs(context.filesDir.absolutePath)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val networkType = when {
            caps == null -> "offline / unavailable"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        """
FRIDAY FULL DIAGNOSTICS
Captured: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(java.util.Date())}
APP
Package: ${context.packageName}
Version: ${appInfo.versionName} (${appInfo.longVersionCode})
Target SDK: ${context.applicationInfo.targetSdkVersion}
DEVICE
Manufacturer: ${Build.MANUFACTURER}
Model: ${Build.MODEL}
Device: ${Build.DEVICE}
Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
ABI: ${Build.SUPPORTED_ABIS.joinToString()}
Low RAM device: ${activityManager.isLowRamDevice}
Memory available: ${memory.availMem / 1024 / 1024} MB
Memory total: ${memory.totalMem / 1024 / 1024} MB
Memory low: ${memory.lowMemory}
App storage free: ${storage.availableBytes / 1024 / 1024} MB
App storage total: ${storage.totalBytes / 1024 / 1024} MB
Battery: $battery%
Network: $networkType
MODEL / INFERENCE
Selected model: ${state.selectedModelId}
Model status: ${state.status}
Model ready: ${state.modelReady}
Busy: ${state.busy}
Downloading: ${state.downloading} (${state.downloadProgress}%)
Prompt processing: ${state.promptProcessingStatus}
Generation: ${state.generationStatus}
Overall performance: ${state.performanceStatus}
VOICE
Android TTS status: ${state.voiceStatus}
Android TTS ready: ${state.systemVoiceReady}
Speech input: $voiceStatus
Speech recognition available: ${android.speech.SpeechRecognizer.isRecognitionAvailable(context)}
MICROPHONE PERMISSION: ${androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED}
GOOGLE / CONNECTORS
Google connected: ${state.googleConnected}
Google status: ${state.googleStatus}
LOCAL DOCUMENTS
Storage access: ${if (allFilesGranted) "broad access granted" else "broad access not granted"}
Folder access: $selectedFolderLabel
Indexed files: ${state.localDocuments.size}
Text-readable files: ${state.localDocuments.count { it.content.isNotBlank() }}
Document scan: ${state.documentStatus}
CHAT / ERRORS
Chat messages loaded: ${state.messages.size}
Last error: ${state.error ?: "none"}
        """.trimIndent()
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) chatListState.animateScrollToItem(state.messages.lastIndex)
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF03172A), Night, Color(0xFF010713))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (state.modelReady) Color(0xFF06323B) else Color(0xFF352A21), shape = RoundedCornerShape(30.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.modelReady) Neon else Color(0xFFFFB36B)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.modelReady) "READY" else "SETUP", color = if (state.modelReady) Neon else Color(0xFFFFC58F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("FRIDAY", color = Neon, fontSize = 21.sp, fontWeight = FontWeight.Light, letterSpacing = 5.sp)
                Spacer(Modifier.width(12.dp))
                Text("DEBUG", color = Muted, fontSize = 10.sp, modifier = Modifier.clickable { debugOpen = true }.padding(4.dp))
                Text("⋮", color = Muted, fontSize = 24.sp, modifier = Modifier.clickable { setupOpen = true }.padding(horizontal = 4.dp))
            }

            Row(Modifier.fillMaxWidth().height(112.dp), verticalAlignment = Alignment.CenterVertically) {
                FridayOrb(active = state.busy || voiceStatus.isNotBlank())
            }

            Text("CONVERSATION", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            LazyColumn(
                state = chatListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text("Your conversation will appear here. Tap the microphone to speak or type below.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                }
                items(state.messages) { message ->
                    val isUser = message.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            color = if (isUser) Color(0xFF07566A) else Color(0xFF0B1C2E),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(
                                text = message.text.ifBlank { if (state.busy && !isUser) "FRIDAY is thinking…" else " " },
                                color = Color(0xFFE3F8FA),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }

            state.error?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !state.busy) {
                    Text("🎙", fontSize = 22.sp)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message FRIDAY…", fontSize = 13.sp) },
                    maxLines = 4,
                    enabled = state.modelReady && !state.busy,
                    shape = RoundedCornerShape(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { val text = input.trim(); if (text.isNotEmpty()) vm.send(text); input = "" },
                    enabled = input.isNotBlank() && state.modelReady && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Neon),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) { Text("SEND", color = Night, fontSize = 11.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (voiceStatus.isNotBlank()) voiceStatus else if (state.busy) "GENERATING RESPONSE…" else "LOCAL CHAT • CLOUD OFF", color = Muted, fontSize = 9.sp)
                Text("SETUP", color = Neon, fontSize = 10.sp, modifier = Modifier.clickable { setupOpen = true }.padding(8.dp))
            }
        }
    }

    if (debugOpen) {
        AlertDialog(
            onDismissRequest = { debugOpen = false },
            containerColor = Color(0xFF0B1C2E),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Diagnostics center", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Device • model • performance • permissions", color = Muted, fontSize = 10.sp)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticTile("MODEL", if (state.modelReady) "Ready" else "Not ready", state.modelReady)
                        DiagnosticTile("VOICE", if (state.systemVoiceReady) "Ready" else "Check", state.systemVoiceReady)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticTile("GOOGLE", if (state.googleConnected) "Connected" else "Offline", state.googleConnected)
                        DiagnosticTile("STORAGE", if (allFilesGranted || selectedFolderLabel != "No folder selected") "Granted" else "Limited", allFilesGranted || selectedFolderLabel != "No folder selected")
                    }
                    Text("LATENCY", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(state.promptProcessingStatus, color = Color.White, fontSize = 11.sp)
                    Text(state.generationStatus, color = Color.White, fontSize = 11.sp)
                    Text(state.performanceStatus, color = SoftCyan, fontSize = 11.sp)
                    Text("LIVE DEVICE SNAPSHOT", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("RAM, storage, battery, network, Android version, app version, model state, permissions, voice recognition and document-index counts are included in the report.", color = Muted, fontSize = 11.sp)
                    Text("REPORT PREVIEW", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Surface(color = Color(0xFF061423), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(debugReport, color = Color(0xFFD8F7FA), fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(12.dp))
                    }
                    Text("Privacy: report excludes chat text, email contents, contacts and document contents. Review before sharing.", color = Muted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(debugReport)) }) { Text("COPY", color = Neon) }
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "FRIDAY diagnostics")
                            putExtra(Intent.EXTRA_TEXT, debugReport)
                        }
                        context.startActivity(Intent.createChooser(share, "Share diagnostics"))
                    }) { Text("SHARE", color = Neon) }
                }
            },
            dismissButton = { TextButton(onClick = { debugOpen = false }) { Text("CLOSE", color = Muted) } }
        )
    }

    if (setupOpen) {
        AlertDialog(
            onDismissRequest = { setupOpen = false },
            containerColor = Color(0xFF0B1C2E),
            title = { Text("FRIDAY setup", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("LOCAL MODEL LIBRARY", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    val selectedModel = FridayViewModel.MODEL_OPTIONS.firstOrNull { it.id == state.selectedModelId } ?: FridayViewModel.MODEL_OPTIONS.first()
                    Text(selectedModel.name + " • " + selectedModel.expectedSize, color = Color.White, fontSize = 14.sp)
                    Text(selectedModel.description, color = Muted, fontSize = 11.sp)
                    Text("License: " + selectedModel.license, color = Muted, fontSize = 10.sp)
                    Box {
                        OutlinedButton(onClick = { modelMenuOpen = true }, enabled = !state.busy && !state.downloading) {
                            Text("Choose a local model", color = Neon)
                        }
                        DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                            FridayViewModel.MODEL_OPTIONS.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.name)
                                            Text(option.expectedSize + " • " + if (vm.isModelInstalled(option.id)) "Downloaded" else "Not downloaded", fontSize = 11.sp, color = Muted)
                                        }
                                    },
                                    onClick = { modelMenuOpen = false; vm.selectModel(option.id) },
                                    enabled = !state.busy && !state.downloading
                                )
                            }
                        }
                    }
                    Text(state.status, color = Muted, fontSize = 12.sp)
                    Text(state.performanceStatus, color = SoftCyan, fontSize = 10.sp, lineHeight = 15.sp)
                    OutlinedButton(onClick = { vm.benchmarkSelectedModel() }, enabled = state.modelReady && !state.busy && !state.downloading) { Text("Benchmark selected model", color = Neon) }
                    if (state.downloading) {
                        LinearProgressIndicator(progress = { state.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neon)
                        Text("Downloading " + state.downloadProgress + "%", color = Muted, fontSize = 12.sp)
                    } else if (!vm.isModelInstalled(state.selectedModelId)) {
                        Button(onClick = { vm.downloadModel() }, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                            Text("Download selected model", color = Night)
                        }
                    } else if (!state.modelReady) {
                        Text("Model is installed and will load when selected.", color = Muted, fontSize = 11.sp)
                    }
                    Text("Models stay on this phone in app-private storage. Download only one at a time; larger models need more free storage and RAM.", color = Muted, fontSize = 10.sp)
                    HorizontalDivider(color = Color(0xFF1C354A))
                    Text("VOICE OUTPUT • ANDROID SYSTEM TTS", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(state.voiceStatus, color = Muted, fontSize = 12.sp)
                    Text("Uses the speech engine and voices installed on your phone. No Kokoro download or extra voice model.", color = Muted, fontSize = 10.sp)
                    Button(onClick = { vm.testVoice() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("Test Android voice", color = Night) }
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
                    Text("PHONE STORAGE ACCESS", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(if (allFilesGranted) "Broad shared-storage access: GRANTED" else "Broad shared-storage access: NOT GRANTED", color = Muted, fontSize = 12.sp)
                    Text("This special access can expose most shared files, but not other apps private data. FRIDAY will not index files until you explicitly start a document feature. Google Play restricts this permission.", color = Muted, fontSize = 10.sp)
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
                        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text(if (allFilesGranted) "Manage broad storage access" else "Grant broad storage access", color = Night) }
                    Text("Scoped folder access: $selectedFolderLabel", color = Muted, fontSize = 11.sp)
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Select a folder instead", color = Neon) }
                    Button(onClick = { vm.scanLocalDocuments() }, enabled = !state.scanningDocuments, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                        Text(if (state.scanningDocuments) "Scanning…" else "Scan phone documents", color = Night)
                    }
                    Text(state.documentStatus, color = Muted, fontSize = 11.sp)
                    state.localDocuments.take(20).forEach { doc ->
                        Text("• ${doc.name}  ·  ${if (doc.content.isNotBlank()) "text indexed" else doc.mimeType}", color = Color(0xFFD8F7FA), fontSize = 10.sp)
                    }
                    if (state.localDocuments.size > 20) Text("…and ${state.localDocuments.size - 20} more", color = Muted, fontSize = 10.sp)
                    HorizontalDivider(color = Color(0xFF1C354A))
                    Text("GOOGLE ACCOUNT & PERSONAL CONTEXT", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(if (state.googleConnected) "CONNECTED • local snapshot available" else state.googleStatus, color = if (state.googleConnected) Neon else Muted, fontSize = 12.sp)
                    Text(if (state.googleConnected) "Google authorization is active for this local snapshot. Refresh to fetch the latest approved data. FRIDAY only injects matching cached records into relevant prompts." else "Choose your Google account and approve read-only access to Drive, Gmail, Calendar and Contacts. OAuth must match package com.friday.app and this APK signing SHA-1.", color = Muted, fontSize = 10.sp)
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
                    }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text(if (state.googleConnected) "Refresh Google data" else "Connect Google • Read only", color = Night) }
                    TextButton(onClick = { vm.disconnectGoogle() }) { Text("Delete local Google cache", color = Color(0xFFFF9CA9)) }
                    googleActionError?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp) }
                    HorizontalDivider(color = Color(0xFF1C354A))
                    TextButton(onClick = { clearMemoryConfirm = true }) { Text("Clear local chat history", color = Color(0xFFFF9CA9)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.voiceModelReady) "Speak replies (Android system voice)" else "Speak replies (Android fallback)", color = Color.White, modifier = Modifier.weight(1f))
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
private fun DiagnosticTile(label: String, value: String, healthy: Boolean) {
    Surface(
        color = if (healthy) Color(0xFF0A2B31) else Color(0xFF30271F),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(value, color = if (healthy) Neon else Color(0xFFFFC58F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
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
