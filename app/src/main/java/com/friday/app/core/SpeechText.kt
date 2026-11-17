package com.friday.app.core

/** Converts chat Markdown into text that sounds natural when spoken, and chunks it for streaming TTS. */
object SpeechText {
    fun toSpeakable(markdown: String, language: AppLanguage = AppLanguage.ENGLISH): String {
        val codeNote = if (language == AppLanguage.TELUGU) "కోడ్ స్క్రీన్‌పై చూపించాను." else "I've shown the code on screen."
        val parts = Markdown.parse(markdown).mapNotNull { b ->
            when (b) {
                is MdBlock.Code -> codeNote
                is MdBlock.Table -> null
                MdBlock.Rule -> null
                is MdBlock.Heading -> plain(b.text).let { if (it.endsWith('.') || it.endsWith(':')) it else "$it." }
                is MdBlock.ListItem -> plain(b.text).let { if (it.matches(Regex(".*[.!?।:]$"))) it else "$it." }
                is MdBlock.Paragraph -> plain(b.text)
                is MdBlock.Quote -> plain(b.text)
            }
        }
        // Collapse repeated "code on screen" notes.
        val deduped = parts.filterIndexed { i, s -> !(s == codeNote && i > 0 && parts[i - 1] == codeNote) }
        return deduped.joinToString(" ")
            .replace(Regex("https?://\\S+"), if (language == AppLanguage.TELUGU) "లింక్" else "link")
            .replace(Regex("[*_#>|`~]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun plain(text: String) = Markdown.inline(text).joinToString("") { it.text }.trim()

    /**
     * Splits text into sentence-sized chunks so speech can start after the first sentence is
     * synthesized instead of after the whole answer. Long sentences are split at commas/spaces.
     */
    fun chunks(text: String, maxChars: Int = 220): List<String> {
        val sentences = Regex("(?<=[.!?।])\\s+|\\n+").split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val pending = StringBuilder()
        fun push(s: String) {
            if (pending.isNotEmpty() && pending.length + 1 + s.length > maxChars) { out += pending.toString(); pending.clear() }
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(s)
            if (pending.length >= 60) { out += pending.toString(); pending.clear() }
        }
        for (s in sentences) {
            if (s.length <= maxChars) { push(s); continue }
            var rest = s
            while (rest.length > maxChars) {
                val window = rest.substring(0, maxChars)
                val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(' ')).firstOrNull { it > maxChars / 3 } ?: maxChars
                push(rest.substring(0, cut + 1).trim())
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) push(rest)
        }
        if (pending.isNotEmpty()) out += pending.toString()
        return out
    }
}

/** Restricts plain-HTTP worker connections to private network addresses. */
object LanAddressPolicy {
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val parts = h.split(".")
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = parts[0].toInt() to parts[1].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (h.contains(":")) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }

    sealed interface Result {
        data class Ok(val normalizedUrl: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /** Accepts "192.168.1.20", "192.168.1.20:8765" or a full URL. Plain HTTP is only allowed to LAN hosts. */
    fun normalize(input: String, defaultPort: Int = 8765): Result {
        var raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return Result.Invalid("Enter the Windows worker address, for example 192.168.1.20:8765.")
        if (!raw.contains("://")) raw = "http://$raw"
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return Result.Invalid("That address is not a valid URL.")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("Only http:// and https:// addresses are supported.")
        val host = uri.host ?: return Result.Invalid("The address is missing a host name or IP.")
        if (scheme == "http" && !isPrivateHost(host)) return Result.Invalid("Plain HTTP is only allowed to private network addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x, Tailscale 100.64-127.x.x). Use https:// for anything else.")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else defaultPort
        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        return Result.Ok("$scheme://$hostPart:$port")
    }
}
