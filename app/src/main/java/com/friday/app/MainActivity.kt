package com.friday.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Ink = Color(0xFF07111F)
private val Panel = Color(0xFF101F32)
private val Cyan = Color(0xFF55D6E8)
private val Muted = Color(0xFF9EB2C7)
data class ChatLine(val role: String, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayApp() }
    }
}

@Composable
fun FridayApp() {
    var prompt by remember { mutableStateOf("") }
    var workerUrl by remember { mutableStateOf("http://192.168.1.100:8765") }
    var status by remember { mutableStateOf("Not connected") }
    var busy by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(ChatLine("assistant", "FRIDAY is ready. Connect your Windows worker to start private, local AI conversations."))) }
    val scope = rememberCoroutineScope()

    fun request(path: String, body: JSONObject? = null, done: (Result<JSONObject>) -> Unit) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val base = workerUrl.trim().trimEnd('/')
                    require(base.startsWith("http://") || base.startsWith("https://")) { "Enter a full URL such as http://192.168.1.20:8765" }
                    val conn = URL(base + path).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 180000
                    conn.requestMethod = if (body == null) "GET" else "POST"
                    conn.setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    if (code !in 200..299) error("Worker HTTP $code: $text")
                    JSONObject(text)
                }
            }
            busy = false
            done(result)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10263A), Ink))).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
                Box(Modifier.size(44.dp).background(Cyan.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("F", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("FRIDAY", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("PERSONAL INTELLIGENCE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(color = if (status.startsWith("Connected")) Color(0xFF143D3C) else Color(0xFF3B2B20), shape = RoundedCornerShape(30.dp)) {
                    Text(if (status.startsWith("Connected")) "● ONLINE" else "● OFFLINE",
                        color = if (status.startsWith("Connected")) Color(0xFF7DE2C3) else Color(0xFFFFC38B),
                        fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Your private AI.", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            Text("Runs on your Windows computer. No cloud API is used.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("WINDOWS WORKER ADDRESS", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = workerUrl, onValueChange = { workerUrl = it }, modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("http://192.168.1.20:8765") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF35516A),
                                focusedContainerColor = Panel, unfocusedContainerColor = Panel))
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = !busy, onClick = {
                            request("/health") { result ->
                                status = result.fold({ "Connected • " + it.optString("model", "Worker ready") }, { "Connection failed" })
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) { Text("Test", color = Ink) }
                    }
                    Text("Phone and PC must share trusted Wi-Fi. Replace the sample IP with your PC's LAN IPv4 address.",
                        color = Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("CONVERSATION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(messages) { line ->
                    Surface(color = if (line.role == "user") Color(0xFF17364A) else Panel, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp)) {
                            Text(if (line.role == "user") "YOU" else "FRIDAY", color = Cyan, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(line.text, color = Color(0xFFE5F0FA), fontSize = 14.sp)
                        }
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan, trackColor = Panel)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message FRIDAY…", color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF35516A), cursorColor = Cyan,
                        focusedContainerColor = Panel, unfocusedContainerColor = Panel),
                    shape = RoundedCornerShape(17.dp), maxLines = 3)
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                    val message = prompt.trim()
                    messages = messages + ChatLine("user", message)
                    prompt = ""
                    request("/v1/chat", JSONObject().put("message", message)) { result ->
                        result.fold(
                            { json ->
                                messages = messages + ChatLine("assistant", json.optString("reply", "No response returned."))
                                status = "Connected • " + json.optString("model", "Local model")
                            },
                            { error ->
                                messages = messages + ChatLine("assistant", "I couldn't reach your local worker. Check its address, that it is running, and that both devices share Wi-Fi. Details: ${error.message}")
                                status = "Connection failed"
                            }
                        )
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Cyan), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(14.dp)) {
                    Text("↑", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("LOCAL NETWORK DEVELOPMENT MODE • Requests go to your configured PC only",
                color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}