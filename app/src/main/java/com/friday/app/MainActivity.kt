package com.friday.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.app.ui.ConfirmDialog
import com.friday.app.ui.FridayTheme
import com.friday.app.ui.Fx
import com.friday.app.ui.FxTextField
import com.friday.app.ui.HomeScreen
import com.friday.app.ui.PlatformHooks
import com.friday.app.ui.SettingsPage
import com.friday.app.ui.SettingsScreen
import com.google.android.gms.auth.api.identity.Identity
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val fridayViewModel: FridayViewModel by viewModels()

    @Deprecated("Vision result uses the legacy activity-result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7401 && resultCode == RESULT_OK) {
            val visualContext = data?.getStringExtra("vision_result").orEmpty()
            if (visualContext.isNotBlank()) fridayViewModel.send("Analyze this result from my camera. Use it as visual evidence and explain any uncertainty:\n$visualContext")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.rgb(10, 18, 32)
            window.navigationBarColor = android.graphics.Color.rgb(5, 7, 11)
        }
        setContent { FridayTheme { FridayApp(fridayViewModel) } }
    }
}

@Composable
fun FridayApp(vm: FridayViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var profileOpen by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var googleActionError by remember { mutableStateOf<String?>(null) }
    var allFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var folderLabel by remember { mutableStateOf(context.getSharedPreferences("friday_storage", 0).getString("tree_uri", null)?.let { "A folder is linked" } ?: "No folder selected") }

    // Lifecycle: refresh storage permission; close the microphone and pause health checks in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { allFilesGranted = Environment.isExternalStorageManager(); vm.onForeground() }
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.onMicPermissionResult(granted) }
    LaunchedEffect(state.micPermissionRequested) { if (state.micPermissionRequested) micPermission.launch(Manifest.permission.RECORD_AUDIO) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.getSharedPreferences("friday_storage", 0).edit().putString("tree_uri", uri.toString()).apply()
            folderLabel = "Folder access granted"
        }.onFailure { folderLabel = "Folder access failed: ${it.message}" }
    }
    val googleAuthorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val data = result.data
        if (data != null) {
            runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken }
                .onSuccess { token -> if (!token.isNullOrBlank()) vm.syncGoogleContext(token) else googleActionError = "Google returned no access token. Check that the requested scopes are enabled for this OAuth consent screen." }
                .onFailure { error -> googleActionError = "Google authorization failed: ${error.message ?: error.javaClass.simpleName}. Check the Android OAuth package/SHA-1 and Google Cloud test-user settings." }
        } else {
            googleActionError = if (result.resultCode == android.app.Activity.RESULT_CANCELED)
                "Google account/consent flow returned no result. Check Google Play services, confirm a Google account is added to Android, then verify the OAuth Android client uses package com.friday.app and this APK's signing SHA-1."
            else "Google authorization returned no result (code ${result.resultCode}). Check Google Play services and OAuth configuration."
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) FridayNotifications.show(context, "FRIDAY notifications enabled", "You'll receive local task completion and model status updates.", 700)
    }
    var notificationIntroOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        FridayNotifications.createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
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
        previousModelReady = state.modelReady
        previousScanning = state.scanningDocuments
    }

    fun startIntentSafely(intent: Intent) = runCatching { context.startActivity(intent) }.isSuccess
    val openAppSettings = { startIntentSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))); Unit }
    val hooks = PlatformHooks(
        sttAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceSttAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false),
        allFilesGranted = allFilesGranted,
        folderLabel = folderLabel,
        googleError = googleActionError,
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startIntentSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        openTtsSettings = { if (!startIntentSafely(Intent("com.android.settings.TTS_SETTINGS"))) startIntentSafely(Intent(Settings.ACTION_SETTINGS)) },
        openVoiceInputSettings = { if (!startIntentSafely(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) startIntentSafely(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        connectGoogle = {
            googleActionError = null
            Identity.getAuthorizationClient(context).authorize(GoogleContextSync.authorizationRequest())
                .addOnSuccessListener { auth ->
                    if (auth.hasResolution()) {
                        val pending = auth.pendingIntent
                        if (pending != null) googleAuthorizationLauncher.launch(IntentSenderRequest.Builder(pending).build()) else googleActionError = "Google consent screen unavailable"
                    } else if (!auth.accessToken.isNullOrBlank()) vm.syncGoogleContext(auth.accessToken!!)
                    else googleActionError = "Google returned no access token"
                }
                .addOnFailureListener { error -> googleActionError = "Google authorization failed: ${error.message ?: error.javaClass.simpleName}" }
        },
        grantAllFiles = {
            if (!startIntentSafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))) startIntentSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        },
        pickFolder = { folderPicker.launch(null) },
        openDiagnostics = { debugOpen = true },
        openProfileEditor = { profileOpen = true }
    )

    BackHandler(enabled = settingsOpen) { if (settingsPage != null) settingsPage = null else settingsOpen = false }

    Surface(color = Fx.Background, modifier = Modifier.fillMaxSize()) {
        if (settingsOpen) {
            SettingsScreen(settingsPage, state, vm, hooks, onNavigate = { settingsPage = it }, onClose = { settingsOpen = false })
        } else {
            HomeScreen(
                state, vm,
                onOpenSettings = { page -> settingsPage = page; settingsOpen = true },
                onOpenCamera = {
                    val activity = context as? android.app.Activity
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        @Suppress("DEPRECATION") activity?.startActivityForResult(Intent(context, VisionCameraActivity::class.java), 7401)
                    } else activity?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 7402)
                },
                onOpenAppSettings = openAppSettings
            )
        }
    }

    if (notificationIntroOpen) {
        AlertDialog(
            onDismissRequest = { notificationIntroOpen = false }, containerColor = Fx.SurfaceHigh,
            title = { Text("Stay informed") },
            text = { Text("Allow FRIDAY to notify you when the local model is ready, document scans finish, or an operation needs attention. Notifications contain status only, never your chat or document contents.", color = Fx.TextSecondary) },
            confirmButton = { TextButton(onClick = { notificationIntroOpen = false; if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable", color = Fx.Cyan) } },
            dismissButton = { TextButton(onClick = { notificationIntroOpen = false }) { Text("Not now", color = Fx.TextSecondary) } }
        )
    }
    if (debugOpen) DiagnosticsDialog(state, allFilesGranted, folderLabel) { debugOpen = false }
    if (profileOpen) ProfileDialog(state.personalProfile, onSave = { vm.savePersonalProfile(it); profileOpen = false }, onErase = { vm.clearPersonalProfile(); profileOpen = false }, onDismiss = { profileOpen = false })
}

@Composable
private fun DiagnosticsDialog(state: FridayState, allFilesGranted: Boolean, folderLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val report = remember(state, allFilesGranted, folderLabel) { diagnosticsReport(context, state, allFilesGranted, folderLabel) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Fx.SurfaceHigh,
        title = { Column { Text("Diagnostics"); Text("Device • model • voice • worker • permissions", style = MaterialTheme.typography.bodySmall) } },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Surface(color = Fx.Background, shape = RoundedCornerShape(12.dp)) {
                    Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Fx.TextSecondary, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Excludes chat text, documents, contacts and the worker token. Review before sharing.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy", color = Fx.Cyan) }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "FRIDAY diagnostics"); putExtra(Intent.EXTRA_TEXT, report) }, "Share diagnostics")) }) { Text("Share", color = Fx.Cyan) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Fx.TextSecondary) } }
    )
}

private fun diagnosticsReport(context: Context, state: FridayState, allFilesGranted: Boolean, folderLabel: String): String {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    val storage = StatFs(context.filesDir.absolutePath)
    val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
    val networkType = when {
        caps == null -> "offline / unavailable"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
    val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val g = state.generation
    return """
FRIDAY DIAGNOSTICS
Captured: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(java.util.Date())}
APP       ${appInfo.versionName} (${appInfo.longVersionCode}) • target SDK ${context.applicationInfo.targetSdkVersion}
DEVICE    ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
ABI       ${Build.SUPPORTED_ABIS.joinToString()}
MEMORY    ${memory.availMem / 1048576} / ${memory.totalMem / 1048576} MB free • low=${memory.lowMemory}
STORAGE   ${storage.availableBytes / 1048576} MB free • battery $battery% • network $networkType
ENGINE    ${state.backend} • ${state.activeModelLabel}
MODEL     ready=${state.modelReady} • ${state.status} • accel=${state.modelBackendName}
SAMPLING  temp=${g.temperature} topP=${g.topP} topK=${g.topK} max=${g.maxOutputTokens} ctx=${g.contextLength} stream=${g.streaming}
NOTES     ${state.generationNotes.joinToString(" | ").ifBlank { "none" }}
PERF      ${state.performanceStatus}
WORKER    ${state.worker.status} • ${state.worker.url.ifBlank { "not configured" }} • token saved=${state.worker.tokenSaved} • share context=${state.worker.shareContext}
          ${state.worker.detail}
VOICE     state=${state.voice.state} • mode=${state.voiceMode} • language=${state.language.bcp47}
STT       available=${SpeechRecognizer.isRecognitionAvailable(context)} • onDevicePref=${state.preferOnDeviceStt} • offlinePref=${state.preferOfflineStt}
TTS       engine=${state.voiceOutput.engine} speed=${state.voiceOutput.speed} pitch=${state.voiceOutput.pitch}
          kokoro=${state.voiceStatus}
          ${state.voiceEngineNote}
MIC PERM  ${ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}
PERSONA   ${state.activePersonality?.name} • reply language ${state.replyLanguage.displayName}
GOOGLE    connected=${state.googleConnected} • ${state.googleStatus}
DOCUMENTS ${if (allFilesGranted) "broad access" else "no broad access"} • $folderLabel • ${state.localDocuments.size} indexed
CHAT      ${state.messages.size} messages loaded
LAST ERR  ${state.error ?: "none"}
""".trim()
}

@Composable
private fun ProfileDialog(initial: PersonalProfile, onSave: (PersonalProfile) -> Unit, onErase: () -> Unit, onDismiss: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var confirmErase by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Fx.SurfaceHigh,
        title = { Text("Personal profile") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Encrypted with Android Keystore and stored in FRIDAY's private app data.", style = MaterialTheme.typography.bodySmall)
                FxTextField(p.displayName, { p = p.copy(displayName = it) }, "What should FRIDAY call me?")
                FxTextField(p.communicationStyle, { p = p.copy(communicationStyle = it) }, "Communication style", singleLine = false, minLines = 2)
                FxTextField(p.responsePreferences, { p = p.copy(responsePreferences = it) }, "How should answers be structured?", singleLine = false, minLines = 2)
                FxTextField(p.goals, { p = p.copy(goals = it) }, "My goals", singleLine = false, minLines = 2)
                FxTextField(p.workContext, { p = p.copy(workContext = it) }, "Work and technical context", singleLine = false, minLines = 2)
                FxTextField(p.interests, { p = p.copy(interests = it) }, "Interests and projects", singleLine = false, minLines = 2)
                FxTextField(p.customInstructions, { p = p.copy(customInstructions = it) }, "Custom instructions", singleLine = false, minLines = 2)
                FxTextField(p.learnedNotes, { p = p.copy(learnedNotes = it) }, "Notes FRIDAY may remember", singleLine = false, minLines = 2)
                Text("FINE-TUNING DATASET • EXPERIMENTAL", style = MaterialTheme.typography.labelSmall, color = Fx.Cyan)
                Text("Approved training examples as JSONL, one per line. This prepares a dataset for external fine-tuning tools; it does not train the phone model.", style = MaterialTheme.typography.bodySmall)
                FxTextField(p.fineTuneExamples, { p = p.copy(fineTuneExamples = it) }, "Approved examples (JSONL)", singleLine = false, minLines = 4)
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/x-ndjson"; putExtra(Intent.EXTRA_SUBJECT, "FRIDAY fine-tuning dataset"); putExtra(Intent.EXTRA_TEXT, p.fineTuneExamples) }, "Export training examples"))
                }, enabled = p.fineTuneExamples.isNotBlank()) { Text("Export training dataset", color = Fx.Cyan) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p) }) { Text("Save", color = Fx.Cyan) } },
        dismissButton = { Row { TextButton(onClick = { confirmErase = true }) { Text("Erase", color = Fx.Danger) }; TextButton(onClick = onDismiss) { Text("Cancel", color = Fx.TextSecondary) } } }
    )
    if (confirmErase) ConfirmDialog("Erase personal profile?", "Your encrypted profile will be deleted from this phone.", "Erase", onConfirm = { confirmErase = false; onErase() }, onDismiss = { confirmErase = false })
}
