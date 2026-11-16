package com.friday.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

private val Ink = Color(0xFF07111F)
private val Panel = Color(0xFF101F32)
private val Cyan = Color(0xFF55D6E8)
private val Muted = Color(0xFF9EB2C7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayApp() }
    }
}

@Composable
fun FridayApp() {
    var prompt by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("FRIDAY is online. Your private assistant interface is ready.", "This build is a UI prototype. Connect the Windows worker to enable local AI responses.")) }
    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF10263A), Ink)))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 22.dp)) {
                Box(Modifier.size(46.dp).background(Cyan.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("F", color = Cyan, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("FRIDAY", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("PERSONAL INTELLIGENCE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(color = Color(0xFF143D3C), shape = RoundedCornerShape(30.dp)) {
                    Text("● LOCAL UI", color = Color(0xFF7DE2C3), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Good day.", color = Muted, fontSize = 15.sp)
            Text("How can I help?", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StatusTile("PRIVACY", "Local-first")
                StatusTile("COMPUTE", "Not paired")
            }
            Spacer(Modifier.height(18.dp))
            Text("CONVERSATION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages.size) { i ->
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Text(messages[i], color = Color(0xFFE5F0FA), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(15.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask FRIDAY anything…", color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF35516A),
                        cursorColor = Cyan, focusedContainerColor = Panel, unfocusedContainerColor = Panel
                    ), shape = RoundedCornerShape(18.dp), maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (prompt.isNotBlank()) {
                        messages = messages + "You: $prompt" + "FRIDAY: The interface is ready. Pair your Windows worker to enable local inference."
                        prompt = ""
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Cyan), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(14.dp)) {
                    Text("↑", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Prototype • No cloud requests • No personal data stored", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun RowScope.StatusTile(label: String, value: String) {
    Surface(modifier = Modifier.weight(1f), color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}