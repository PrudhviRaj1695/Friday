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

private val Night = Color(0xFF070811)
private val DeepBlue = Color(0xFF101426)
private val Neon = Color(0xFFB69CFF)
private val SoftCyan = Color(0xFF8DCBFF)
private val Muted = Color(0xFFB8C0D8)
private val Violet = Color(0xFF6853C7)
private val PinkGlow = Color(0xFFDAA9FF)

class MainActivity : ComponentActivity() {
    private val fridayViewModel: FridayViewModel by viewModels()
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7401 && resultCode == android.app.Activity.RESULT_OK) {
            val visualContext = data?.getStringExtra("vision_result").orEmpty()
            if (visualContext.isNotBlank()) fridayViewModel.send("Analyze this result from my camera. Use it as visual evidence and explain any uncertainty:\n$visualContext")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 2, 8)
        window.navigationBarColor = android.graphics.Color.rgb(5, 2, 8)
        setContent { FridayApp(fridayViewModel) }
    }
}

@Composable
fun FridayApp(vm: FridayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var setupOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var profileDraft by remember { mutableStateOf(PersonalProfile()) }
    var textOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var googleActionError by remember { mutableStateOf<String?>(null) }
    var clearMemoryConfirm by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var personalityNameDraft by remember { mutableStateOf("Custom") }
    var personalityPromptDraft by remember { mutableStateOf("") }
    var behaviorDraft by remember { mutableStateOf(state.additionalBehaviorInstructions) }
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
    var handsFreeEnabled by remember { mutableStateOf(false) }
    var awaitingWakeCommand by remember { mutableStateOf(false) }
    var wakeCommandSubmitted by remember { mutableStateOf(false) }
    val handsFreeNow = rememberUpdatedState(handsFreeEnabled)
    val awaitingCommandNow = rememberUpdatedState(awaitingWakeCommand)
    val modelReadyNow = rememberUpdatedState(state.modelReady)
    val responseLanguageNow = rememberUpdatedState(state.responseLanguage)
    val busyNow = rememberUpdatedState(state.busy)
    val voicePlayingNow = rememberUpdatedState(state.voicePlaying)
    val speechRecognizer = remember {
        // Hands-free mode is deliberately on-device only; never silently fall back to a cloud recognizer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else null
    }
    val startHandsFreeListening: () -> Unit = {
        if (handsFreeNow.value && !busyNow.value && !voicePlayingNow.value && speechRecognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (responseLanguageNow.value == "Telugu") "te-IN" else "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            }
            runCatching { speechRecognizer.startListening(intent) }
                .onFailure { voiceStatus = "VOICE START FAILED • ${it.message ?: "try again"}" }
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (handsFreeNow.value) voiceStatus = if (awaitingCommandNow.value) "FRIDAY IS LISTENING • SPEAK NOW" else "WAITING FOR “FRIDAY”" }
            override fun onBeginningOfSpeech() { voiceStatus = "HEARING YOU" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { voiceStatus = "PROCESSING…" }
            override fun onError(error: Int) {
                if (handsFreeNow.value) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startHandsFreeListening() }, 450)
                } else {
                    voiceStatus = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "MICROPHONE AUDIO ERROR"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MICROPHONE PERMISSION DENIED"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "SPEECH SERVICE BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "ON-DEVICE SPEECH SERVICE ERROR"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "WAITING FOR “FRIDAY”"
                        else -> "SPEECH ERROR • $error"
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (!handsFreeNow.value) {
                    if (spoken.isNotBlank() && modelReadyNow.value) vm.send(spoken)
                    else if (!modelReadyNow.value) voiceStatus = "LOCAL MODEL IS NOT READY"
                    return
                }
                val normalized = spoken.lowercase(Locale.ROOT)
                val stopPhrase = normalized in setOf("stop listening", "friday stop", "cancel listening", "go to sleep", "ఫ్రైడే ఆపు") || Regex("^(?:hey\\s+)?(?:friday|ఫ్రైడే)[,\\s]+(?:stop listening|cancel listening|go to sleep)$").matches(normalized)
                if (stopPhrase) {
                    handsFreeEnabled = false
                    awaitingWakeCommand = false
                    voiceStatus = "HANDS-FREE OFF"
                    return
                }
                if (awaitingCommandNow.value) {
                    awaitingWakeCommand = false
                    if (spoken.isNotBlank() && modelReadyNow.value) {
                        wakeCommandSubmitted = true
                        vm.send(spoken)
                        voiceStatus = "FRIDAY IS THINKING…"
                    } else {
                        voiceStatus = "LOCAL MODEL NOT READY"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startHandsFreeListening() }, 350)
                    }
                    return
                }
                val wake = Regex("\\bfriday\\b|ఫ్రైడే", RegexOption.IGNORE_CASE).find(spoken)
                if (wake != null) {
                    val command = spoken.substring(wake.range.last + 1).trim().trimStart(',', '.', '!', ':')
                    if (command.isBlank()) {
                        awaitingWakeCommand = true
                        voiceStatus = "YES? I'M LISTENING"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startHandsFreeListening() }, 250)
                    } else if (modelReadyNow.value) {
                        wakeCommandSubmitted = true
                        vm.send(command)
                        voiceStatus = "FRIDAY IS THINKING…"
                    } else voiceStatus = "LOCAL MODEL IS NOT READY"
                } else {
                    voiceStatus = "WAITING FOR “FRIDAY”"
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startHandsFreeListening() }, 250)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) voiceStatus = if (handsFreeNow.value) "HOTWORD LISTENING • $partial" else "HEARING • $partial"
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
    }

    // Keep the microphone lifecycle visible and foreground-only; background hotword listening
    // requires a dedicated microphone foreground service and persistent notification.
    DisposableEffect(lifecycleOwner, speechRecognizer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && handsFreeEnabled) {
                handsFreeEnabled = false
                awaitingWakeCommand = false
                speechRecognizer?.cancel()
                voiceStatus = "HANDS-FREE PAUSED • REOPEN FRIDAY TO RESUME"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(handsFreeEnabled, state.busy, state.voicePlaying) {
        if (handsFreeEnabled && !state.busy && !state.voicePlaying) {
            kotlinx.coroutines.delay(350)
            startHandsFreeListening()
            if (wakeCommandSubmitted) {
                wakeCommandSubmitted = false
                awaitingWakeCommand = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && speechRecognizer != null) {
            handsFreeEnabled = true
            awaitingWakeCommand = false
            voiceStatus = "HANDS-FREE ACTIVE • SAY “FRIDAY”"
        } else voiceStatus = if (speechRecognizer == null) "ON-DEVICE SPEECH RECOGNITION NOT AVAILABLE • INSTALL AN OFFLINE SPEECH PACK" else "MICROPHONE PERMISSION DENIED"
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) FridayNotifications.show(context, "FRIDAY notifications enabled", "You'll receive local task completion and model status updates.", 700)
    }
    var notificationIntroOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        FridayNotifications.createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences("friday_settings", 0).getBoolean("notification_prompted", false)) {
            notificationIntroOpen = true
            context.getSharedPreferences("friday_settings", 0).edit().putBoolean("notification_prompted", true).apply()
        }
    }
    var previousModelReady by remember { mutableStateOf(state.modelReady) }
    var previousScanning by remember { mutableStateOf(state.scanningDocuments) }
    LaunchedEffect(state.modelReady, state.scanningDocuments, state.documentStatus, state.error) {
        if (!previousModelReady && state.modelReady) FridayNotifications.show(context, "FRIDAY is ready", "Your on-device AI model has loaded successfully.", 701)
        if (previousScanning && !state.scanningDocuments) FridayNotifications.show(context, "Document scan finished", state.documentStatus, 702)
        if (!state.error.isNullOrBlank()) FridayNotifications.show(context, "FRIDAY needs attention", state.error!!.take(180), 703)
        previousModelReady = state.modelReady
        previousScanning = state.scanningDocuments
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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16051D), Night, Color(0xFF000000))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (state.modelReady) Color(0xFF32103D) else Color(0xFF35202C), shape = RoundedCornerShape(30.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = .55f))) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.modelReady) Neon else Color(0xFFFFB36B)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.modelReady) "READY" else "SETUP", color = if (state.modelReady) Neon else Color(0xFFFFC58F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FRIDAY", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
                    Text("YOUR PRIVATE AI COMPANION", color = Neon, fontSize = 8.sp, letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text("⋮", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { setupOpen = true }.padding(horizontal = 4.dp))
            }

            Box(Modifier.fillMaxWidth().height(188.dp), contentAlignment = Alignment.Center) {
                FridayOrb(active = state.busy || voiceStatus.isNotBlank())
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (voiceStatus.isNotBlank()) voiceStatus else if (state.busy) "THINKING…" else "AT YOUR SERVICE", color = Neon, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    Text(if (state.modelReady) "Ready when you are" else "Your local intelligence awaits", color = Muted, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR CONVERSATION", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Spacer(Modifier.weight(1f))
                Text("PRIVATE • ON-DEVICE", color = Neon, fontSize = 8.sp, letterSpacing = 1.sp)
            }
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
                            color = if (isUser) Color(0xFF292448) else Color(0xFF151A29),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, (if (isUser) PinkGlow else Violet).copy(alpha = .30f)),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(
                                text = message.text
                                    .replace("**", "")
                                    .ifBlank { if (state.busy && !isUser) "FRIDAY is thinking…" else " " },
                                color = Color(0xFFF8EFFB),
                                fontSize = state.chatFontSize.sp,
                                lineHeight = (state.chatFontSize * 1.48f).sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }

            state.error?.let { Text(it, color = Color(0xFFFF9CA9), fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).background(Color(0xFF111626), RoundedCornerShape(28.dp)).border(1.dp, Violet.copy(alpha = .55f), RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        (context as? android.app.Activity)?.startActivityForResult(Intent(context, VisionCameraActivity::class.java), 7401)
                    } else {
                        (context as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 7402)
                    }
                }, enabled = !state.busy) { Text("📷", fontSize = 21.sp) }
                IconButton(onClick = {
                    if (handsFreeEnabled) {
                        handsFreeEnabled = false
                        awaitingWakeCommand = false
                        speechRecognizer?.cancel()
                        voiceStatus = "HANDS-FREE OFF"
                    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(if (handsFreeEnabled) "🔴" else "🎙", fontSize = 22.sp)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message FRIDAY…", fontSize = 13.sp) },
                    maxLines = 4,
                    enabled = state.modelReady && !state.busy,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted
                    )
                )
                Spacer(Modifier.width(6.dp))
                if (state.busy) {
                    Button(
                        onClick = { vm.stopGeneration() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8E205F),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("STOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { val text = input.trim(); if (text.isNotEmpty()) vm.send(text); input = "" },
                        enabled = input.isNotBlank() && state.modelReady,
                        colors = ButtonDefaults.buttonColors(containerColor = Violet, disabledContainerColor = Color(0xFF39213F)),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) { Text("SEND", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { vm.stopSpeaking() },
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB52D86)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8BD6))
                ) {
                    Text("■  STOP VOICE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (voiceStatus.isNotBlank()) voiceStatus else if (state.busy) "GENERATING RESPONSE…" else "LOCAL CHAT • CLOUD OFF", color = Muted, fontSize = 9.sp)
                Text("SETTINGS", color = Neon, fontSize = 10.sp, modifier = Modifier.clickable { setupOpen = true }.padding(8.dp))
            }
        }
    }

    if (notificationIntroOpen) {
        AlertDialog(
            onDismissRequest = { notificationIntroOpen = false },
            containerColor = Color(0xFF0B1C2E),
            title = { Text("Stay informed", color = Color.White) },
            text = { Text("Allow FRIDAY to notify you when the local model is ready, document scans finish, or an operation needs attention. Notifications contain status only, never your chat or document contents.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    notificationIntroOpen = false
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("ENABLE NOTIFICATIONS", color = Neon) }
            },
            dismissButton = { TextButton(onClick = { notificationIntroOpen = false }) { Text("NOT NOW", color = Muted) } }
        )
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
                        DiagnosticTile("MODEL", if (state.modelReady) "Ready" else "Not ready", state.modelReady, Modifier.weight(1f))
                        DiagnosticTile("VOICE", if (state.systemVoiceReady) "Ready" else "Check", state.systemVoiceReady, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticTile("GOOGLE", if (state.googleConnected) "Connected" else "Offline", state.googleConnected, Modifier.weight(1f))
                        DiagnosticTile("STORAGE", if (allFilesGranted || selectedFolderLabel != "No folder selected") "Granted" else "Limited", allFilesGranted || selectedFolderLabel != "No folder selected", Modifier.weight(1f))
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
                    Text("PERSONAL INTELLIGENCE", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Encrypted on-device profile • learns only what you choose to save", color = Muted, fontSize = 10.sp)
                    OutlinedButton(onClick = { profileDraft = state.personalProfile; profileOpen = true }) { Text("Customize how FRIDAY knows and assists me", color = Neon) }
                    HorizontalDivider(color = Color(0xFF30334A))
                    Text("LANGUAGE & VOICE", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Choose the language FRIDAY uses for replies.", color = Muted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("English", "Telugu").forEach { lang ->
                            FilterChip(selected = state.responseLanguage == lang, onClick = { vm.setResponseLanguage(lang) }, label = { Text(if (lang == "Telugu") "తెలుగు" else "English") })
                        }
                    }
                    Text("PERSONALITY STUDIO", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    var personalityMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { personalityMenuOpen = true }) { Text("Active: ${state.activePersonality}", color = Neon) }
                        DropdownMenu(expanded = personalityMenuOpen, onDismissRequest = { personalityMenuOpen = false }) {
                            listOf(
                                "Executive" to "Act as a concise executive advisor. Prioritize decisions, trade-offs, risks and next actions.",
                                "Friendly" to "Be warm, natural, supportive and conversational without being overly agreeable.",
                                "Teacher" to "Teach patiently, explain concepts step by step and use practical examples.",
                                "Engineer" to "Respond like a senior software engineer. Be technically precise, pragmatic and include implementation details when useful."
                            ).forEach { (name, prompt) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { vm.setPersonality(name, prompt); personalityMenuOpen = false; personalityNameDraft = name; personalityPromptDraft = prompt })
                            }
                        }
                    }
                    OutlinedTextField(value = personalityNameDraft, onValueChange = { personalityNameDraft = it }, label = { Text("Custom profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = personalityPromptDraft, onValueChange = { personalityPromptDraft = it }, label = { Text("Custom response instructions") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.setPersonality(personalityNameDraft.ifBlank { "Custom" }, personalityPromptDraft) }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("Save and activate personality", color = Night) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("SAVED RESPONSE PROFILES", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
                        Text("${state.responseProfiles.size}/20", color = Muted, fontSize = 10.sp)
                    }
                    if (state.responseProfiles.isEmpty()) {
                        Text("Save your custom prompt above to create reusable profiles.", color = Muted, fontSize = 10.sp)
                    } else {
                        state.responseProfiles.forEach { saved ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(saved.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.activateResponseProfile(saved); personalityNameDraft = saved.name; personalityPromptDraft = saved.prompt }) { Text("ACTIVATE", color = Neon, fontSize = 10.sp) }
                                TextButton(onClick = { vm.deleteResponseProfile(saved.name) }) { Text("✕", color = Color(0xFFFF9CA9), fontSize = 10.sp) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { vm.saveResponseProfile(personalityNameDraft, personalityPromptDraft) }, enabled = personalityNameDraft.isNotBlank() && personalityPromptDraft.isNotBlank()) {
                        Text("Save current prompt as reusable profile", color = Neon)
                    }
                    HorizontalDivider(color = Color(0xFF30334A))
                    Text("CONTENT & POLICY PREFERENCES", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Mature everyday language", color = Color.White, fontSize = 12.sp)
                            Text("Allows ordinary adult vocabulary and less sanitised fictional dialogue. It does not disable core safety boundaries.", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = state.matureLanguageEnabled, onCheckedChange = vm::setMatureLanguageEnabled)
                    }
                    OutlinedTextField(
                        value = behaviorDraft,
                        onValueChange = { behaviorDraft = it },
                        label = { Text("Additional response / topic preferences") },
                        placeholder = { Text("Example: Be direct, allow harmless technical experiments, avoid unnecessary refusals, explain restrictions briefly…") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { vm.setAdditionalBehaviorInstructions(behaviorDraft) }) { Text("Save behavior preferences", color = Neon) }
                    Text("CREATIVITY • PROMPT-GUIDED", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Creativity: ${"%.1f".format(state.creativity)}  •  This guides response style; it is not native token-sampling temperature.", color = Muted, fontSize = 10.sp)
                    Slider(value = state.creativity, onValueChange = vm::setCreativity, valueRange = 0f..2f)
                    Text("CONTENT STYLE", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Strict", "Balanced", "Relaxed").forEach { style ->
                            FilterChip(selected = state.contentStyle == style, onClick = { vm.setContentStyle(style) }, label = { Text(style, fontSize = 11.sp) })
                        }
                    }
                    Text("Relaxed changes ordinary language style only; core safety protections remain active.", color = Muted, fontSize = 10.sp)
                    Text("CHAT READABILITY", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Message text size: ${state.chatFontSize} sp", color = Muted, fontSize = 11.sp)
                    Slider(value = state.chatFontSize.toFloat(), onValueChange = { vm.setChatFontSize(it.toInt()) }, valueRange = 14f..24f, steps = 9)
                    
                    Text("Your profile shapes tone, answer structure, goals and working context. It does not retrain model weights.", color = Muted, fontSize = 10.sp)
                    HorizontalDivider(color = Color(0xFF1C354A))
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
                    Text("VOICE INPUT • ON-DEVICE FIRST", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) "On-device speech recognition is available on this phone. FRIDAY selects it before the system recognizer." else "On-device speech recognition is not exposed by the installed speech service. Android may use its configured recognition provider.",
                        color = Muted, fontSize = 10.sp
                    )
                    Text("VOICE OUTPUT • KOKORO NEURAL TTS", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Kokoro-82M • Sherpa-ONNX • on-device CPU inference • no cloud voice requests", color = Muted, fontSize = 10.sp)
                    Text(state.voiceStatus, color = Muted, fontSize = 12.sp)
                    if (state.voiceDownloading) {
                        LinearProgressIndicator(progress = { state.voiceDownloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neon)
                        Text("Downloading voice model • ${state.voiceDownloadProgress}%", color = Muted, fontSize = 11.sp)
                    } else if (!state.voiceModelReady) {
                        Button(onClick = { vm.downloadVoiceModel() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                            Text("Download offline neural voice model", color = Night)
                        }
                    } else {
                        Text("Voice: Michael • male American English. This model does not include an Indian-English male accent.", color = Muted, fontSize = 10.sp)
                        Button(onClick = { vm.testVoice() }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text("Test neural voice", color = Night) }
                    }
                    HorizontalDivider(color = Color(0xFF1C354A))
                    Text("NOTIFICATIONS", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Local status alerts only • no message, document or personal content is included.", color = Muted, fontSize = 10.sp)
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                        }
                    }) { Text("Manage notification permission", color = Neon) }
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
                    Text("DOCUMENT INTELLIGENCE • LOCAL ONLY", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(if (allFilesGranted) "Broad shared-storage access: GRANTED" else "Broad shared-storage access: NOT GRANTED", color = Muted, fontSize = 12.sp)
                    Text("Choose a folder or grant shared-storage access. FRIDAY extracts searchable text locally from TXT, Markdown, CSV, JSON, XML, HTML, LOG, PDF and Office Open XML files (DOCX, XLSX, PPTX). It cannot read other apps private data.", color = Muted, fontSize = 10.sp)
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
                        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Neon)) { Text(if (allFilesGranted) "Manage broad storage access" else "Grant broad storage access", color = Night) }
                    Text("Scoped folder access: $selectedFolderLabel", color = Muted, fontSize = 11.sp)
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Select a folder instead", color = Neon) }
                    Button(onClick = { vm.scanLocalDocuments() }, enabled = !state.scanningDocuments, colors = ButtonDefaults.buttonColors(containerColor = Neon)) {
                        Text(if (state.scanningDocuments) "Scanning…" else "Scan phone documents", color = Night)
                    }
                    if (state.scanningDocuments || state.documentProgress > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.scanningDocuments) "DOCUMENT SCAN" else "LAST SCAN", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("${state.documentProgress}%", color = Neon, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { state.documentProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Neon, trackColor = Color(0xFF1C354A))
                        Text("${state.documentsProcessed}/${state.documentsTotal} files • ${state.documentsExtracted} extracted • ${state.documentsFailed} failed", color = Muted, fontSize = 10.sp)
                        if (state.documentCurrentFile.isNotBlank()) Text("Current: ${state.documentCurrentFile}", color = SoftCyan, fontSize = 10.sp, maxLines = 1)
                    }
                    Text(state.documentStatus, color = Muted, fontSize = 11.sp)
                    Text("Ask naturally in chat: \"Summarize my PDF about X\" or \"What does my project plan say about authentication?\" Answers use the best matching local excerpts.", color = SoftCyan, fontSize = 10.sp)
                    state.localDocuments.take(20).forEach { doc ->
                        Text("• ${doc.name}  ·  ${if (doc.content.isNotBlank()) "extracted • searchable" else "not extracted • " + doc.mimeType}", color = Color(0xFFD8F7FA), fontSize = 10.sp)
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

    if (profileOpen) {
        AlertDialog(onDismissRequest = { profileOpen = false }, containerColor = Color(0xFF0B1C2E),
            title = { Text("Personal Intelligence", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Encrypted with Android Keystore and stored in FRIDAY private app data.", color = Muted, fontSize = 11.sp)
                    OutlinedTextField(value = profileDraft.displayName, onValueChange = { profileDraft = profileDraft.copy(displayName = it) }, label = { Text("What should FRIDAY call me?") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = profileDraft.communicationStyle, onValueChange = { profileDraft = profileDraft.copy(communicationStyle = it) }, label = { Text("Communication style") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.responsePreferences, onValueChange = { profileDraft = profileDraft.copy(responsePreferences = it) }, label = { Text("How should answers be structured?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.goals, onValueChange = { profileDraft = profileDraft.copy(goals = it) }, label = { Text("My goals") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.workContext, onValueChange = { profileDraft = profileDraft.copy(workContext = it) }, label = { Text("Work and technical context") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.interests, onValueChange = { profileDraft = profileDraft.copy(interests = it) }, label = { Text("Interests and projects") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.customInstructions, onValueChange = { profileDraft = profileDraft.copy(customInstructions = it) }, label = { Text("Custom instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = profileDraft.learnedNotes, onValueChange = { profileDraft = profileDraft.copy(learnedNotes = it) }, label = { Text("Notes FRIDAY may remember") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("FINE-TUNING DATASET • EXPERIMENTAL", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Add approved training examples as JSONL, one example per line. This prepares a dataset for compatible fine-tuning tools; it does not train the phone model yet.", color = Muted, fontSize = 10.sp)
                    OutlinedTextField(value = profileDraft.fineTuneExamples, onValueChange = { profileDraft = profileDraft.copy(fineTuneExamples = it) }, label = { Text("Approved examples (JSONL)") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply { type = "application/x-ndjson"; putExtra(Intent.EXTRA_SUBJECT, "FRIDAY fine-tuning dataset"); putExtra(Intent.EXTRA_TEXT, profileDraft.fineTuneExamples) }
                        context.startActivity(Intent.createChooser(share, "Export training examples"))
                    }, enabled = profileDraft.fineTuneExamples.isNotBlank()) { Text("Export training dataset", color = Neon) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.savePersonalProfile(profileDraft); profileOpen = false }) { Text("SAVE PROFILE", color = Neon) } },
            dismissButton = { Row { TextButton(onClick = { vm.clearPersonalProfile(); profileDraft = PersonalProfile(); profileOpen = false }) { Text("ERASE", color = Color(0xFFFF9CA9)) }; TextButton(onClick = { profileOpen = false }) { Text("CANCEL", color = Muted) } } }
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
private fun DiagnosticTile(label: String, value: String, healthy: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = if (healthy) Color(0xFF0A2B31) else Color(0xFF30271F),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
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

    Canvas(Modifier.fillMaxHeight().aspectRatio(1f).padding(4.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .31f * pulse
        drawCircle(Brush.radialGradient(listOf(Color(0x66E34BFF), Color(0x332C0A42), Color.Transparent), center, radius * 2.35f), radius * 2.35f, center)
        drawCircle(Brush.radialGradient(listOf(Color(0xAAE65BFF), Color(0x665E168C), Color(0x2224003A), Color.Transparent), center, radius * 1.9f), radius * 1.9f, center)
        drawCircle(Color(0x44F04DFF), radius * 1.42f, center, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(Color(0x77D65BFF), radius * 1.08f, center, style = Stroke(width = 1.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF9BEA), Color(0xFF9C2BC7), Color(0xFF180522)), center, radius), radius, center)
        drawCircle(Color(0x88F7A1FF), radius * .58f, center, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(Color(0x66FFFFFF), radius * .16f, Offset(center.x - radius * .24f, center.y - radius * .48f))
        val orbit = radius * 1.48f
        for (i in 0 until 9) {
            val angle = Math.toRadians((rotation + i * 40.0))
            val x = center.x + kotlin.math.cos(angle).toFloat() * orbit
            val y = center.y + kotlin.math.sin(angle).toFloat() * orbit
            val dotColor = if (i % 3 == 0) PinkGlow else Neon
            drawCircle(dotColor.copy(alpha = if (active) .95f else .55f), if (i % 3 == 0) 5.dp.toPx() else 3.dp.toPx(), Offset(x, y))
            drawCircle(dotColor.copy(alpha = .14f), 10.dp.toPx(), Offset(x, y))
        }
        drawArc(Color(0x3320EAF5), rotation, 135f, false, center - androidx.compose.ui.geometry.Size(radius * 1.62f, radius * 1.62f).let { Offset(it.width / 2f, it.height / 2f) },
            androidx.compose.ui.geometry.Size(radius * 1.62f, radius * 1.62f), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
    }
}
