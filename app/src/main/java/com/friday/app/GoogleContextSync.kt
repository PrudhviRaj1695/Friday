package com.friday.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google data is fetched only after an explicit user action and OAuth consent.
 * Access tokens are never persisted. Only compact context summaries are cached
 * in app-private storage for local inference.
 */
object GoogleContextSync {
    val scopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.readonly"),
        Scope("https://www.googleapis.com/auth/gmail.readonly"),
        Scope("https://www.googleapis.com/auth/calendar.events.readonly"),
        Scope("https://www.googleapis.com/auth/contacts.readonly")
    )

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    fun sync(context: Context, accessToken: String): String {
        require(accessToken.isNotBlank()) { "Google authorization returned no access token" }
        val sections = mutableListOf<String>()
        sections += fetchDrive(accessToken)
        sections += fetchGmail(accessToken)
        sections += fetchCalendar(accessToken)
        sections += fetchContacts(accessToken)
        val summary = sections.filter { it.isNotBlank() }.joinToString("\n\n")
        File(context.filesDir, "google_context.txt").writeText(summary)
        context.getSharedPreferences("friday_google", Context.MODE_PRIVATE)
            .edit().putLong("last_sync_epoch_ms", System.currentTimeMillis())
            .putBoolean("connected", true).apply()
        return summary
    }

    fun cachedContext(context: Context): String =
        runCatching { File(context.filesDir, "google_context.txt").takeIf { it.exists() }?.readText().orEmpty() }
            .getOrDefault("")

    private fun get(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Google API returned HTTP $code: ${body.take(240)}" }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun fetchDrive(token: String): String = runCatching {
        val fields = enc("files(name,mimeType,modifiedTime,webViewLink)")
        val json = get("https://www.googleapis.com/drive/v3/files?pageSize=20&orderBy=modifiedTime%20desc&q=trashed%3Dfalse&fields=$fields", token)
        val files = json.optJSONArray("files")
        val lines = (0 until (files?.length() ?: 0)).mapNotNull { i ->
            files?.optJSONObject(i)?.let { "• ${it.optString("name")} [${it.optString("mimeType")}] · ${it.optString("modifiedTime")}" }
        }
        "GOOGLE DRIVE — recent files\n" + if (lines.isEmpty()) "No files returned." else lines.joinToString("\n")
    }.getOrElse { "GOOGLE DRIVE — unavailable: ${it.message}" }

    private fun fetchGmail(token: String): String = runCatching {
        val json = get("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=15", token)
        val messages = json.optJSONArray("messages")
        val lines = (0 until (messages?.length() ?: 0)).mapNotNull { i ->
            val id = messages?.optJSONObject(i)?.optString("id") ?: return@mapNotNull null
            runCatching {
                val item = get("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date", token)
                val headers = item.optJSONObject("payload")?.optJSONArray("headers")
                val values = (0 until (headers?.length() ?: 0)).associate { n ->
                    val h = headers?.optJSONObject(n)
                    h?.optString("name").orEmpty().lowercase() to h?.optString("value").orEmpty()
                }
                "• ${values["subject"].orEmpty()} — ${values["from"].orEmpty()} · ${values["date"].orEmpty()}\n  ${item.optString("snippet")}"
            }.getOrNull()
        }
        "GMAIL — recent message summaries\n" + if (lines.isEmpty()) "No messages returned." else lines.joinToString("\n")
    }.getOrElse { "GMAIL — unavailable: ${it.message}" }

    private fun fetchCalendar(token: String): String = runCatching {
        val json = get("https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=20&singleEvents=true&orderBy=startTime", token)
        val events = json.optJSONArray("items")
        val lines = (0 until (events?.length() ?: 0)).mapNotNull { i ->
            events?.optJSONObject(i)?.let {
                val start = it.optJSONObject("start")?.optString("dateTime")
                    ?: it.optJSONObject("start")?.optString("date").orEmpty()
                "• ${it.optString("summary", "Untitled event")} · $start"
            }
        }
        "GOOGLE CALENDAR — events\n" + if (lines.isEmpty()) "No events returned." else lines.joinToString("\n")
    }.getOrElse { "GOOGLE CALENDAR — unavailable: ${it.message}" }

    private fun fetchContacts(token: String): String = runCatching {
        val json = get("https://people.googleapis.com/v1/people/me/connections?pageSize=100&personFields=names,emailAddresses,phoneNumbers", token)
        val people = json.optJSONArray("connections")
        val lines = (0 until (people?.length() ?: 0)).mapNotNull { i ->
            people?.optJSONObject(i)?.let { person ->
                val name = person.optJSONArray("names")?.optJSONObject(0)?.optString("displayName").orEmpty()
                val email = person.optJSONArray("emailAddresses")?.optJSONObject(0)?.optString("value").orEmpty()
                val phone = person.optJSONArray("phoneNumbers")?.optJSONObject(0)?.optString("value").orEmpty()
                if (name.isBlank() && email.isBlank() && phone.isBlank()) null else "• $name $email $phone".trim()
            }
        }
        "GOOGLE CONTACTS — contacts\n" + if (lines.isEmpty()) "No contacts returned." else lines.joinToString("\n")
    }.getOrElse { "GOOGLE CONTACTS — unavailable: ${it.message}" }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
