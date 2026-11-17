package com.friday.app

import java.util.Locale
import kotlin.math.ln

data class RetrievedDocumentChunk(
    val documentName: String,
    val location: String,
    val page: Int?,
    val text: String,
    val score: Double
)

/**
 * Lightweight, fully on-device BM25 retrieval.
 *
 * The index is rebuilt from the currently scanned document snapshot. It does not
 * upload documents or use a hosted embedding service.
 */
object LocalDocumentRetriever {
    private val stopWords = setOf(
        "the", "and", "for", "that", "with", "from", "this", "what", "when",
        "where", "which", "about", "your", "you", "are", "was", "were", "have",
        "has", "had", "does", "did", "can", "could", "would", "should", "into",
        "onto", "how", "why", "who", "whom", "their", "there", "them", "then",
        "than", "but", "not", "all", "any", "our", "out", "please", "tell",
        "me", "give", "using", "based", "available", "data"
    )

    private data class Chunk(
        val documentName: String,
        val location: String,
        val page: Int?,
        val text: String
    )

    fun retrieve(query: String, documents: List<LocalDocument>, limit: Int = 5): List<RetrievedDocumentChunk> {
        if (limit <= 0 || query.isBlank()) return emptyList()
        val queryTokens = tokens(query).filter { it !in stopWords }.distinct()
        if (queryTokens.isEmpty()) return emptyList()

        // Identity-number questions must retrieve the exact identifier type.
        // Never let a generic "number" match substitute Aadhaar for PAN (or vice versa).
        val requestedIdentifier = when {
            Regex("\\bpan\\b|permanent\\s+account\\s+(number|no)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) -> "pan"
            Regex("\\baadhaar\\b|\\baadhar\\b|\\buidai\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) -> "aadhaar"
            else -> null
        }
        val chunks = documents.asSequence()
            .filter { it.content.isNotBlank() }
            .flatMap { split(it).asSequence() }
            .filter { chunk ->
                when (requestedIdentifier) {
                    "pan" -> Regex("\\bpan\\b|permanent\\s+account\\s+(number|no)\\b", RegexOption.IGNORE_CASE).containsMatchIn(chunk.text)
                    "aadhaar" -> Regex("\\baadhaar\\b|\\baadhar\\b|\\buidai\\b", RegexOption.IGNORE_CASE).containsMatchIn(chunk.text)
                    else -> true
                }
            }
            .take(12_000)
            .toList()
        if (chunks.isEmpty()) return emptyList()

        val tokenized = chunks.map { tokens(it.text) }
        val avgLength = tokenized.sumOf { it.size }.toDouble() / tokenized.size
        val documentFrequency = queryTokens.associateWith { term -> tokenized.count { term in it } }
        val queryPhrase = normalize(query)
        val scored = chunks.mapIndexedNotNull { index, chunk ->
            val words = tokenized[index]
            if (words.isEmpty()) return@mapIndexedNotNull null
            var score = 0.0
            queryTokens.forEach { term ->
                val tf = words.count { it == term }
                if (tf > 0) {
                    val df = documentFrequency[term] ?: 0
                    val idf = ln(1.0 + (chunks.size - df + 0.5) / (df + 0.5))
                    val denominator = tf + 1.2 * (1.0 - 0.75 + 0.75 * words.size / avgLength.coerceAtLeast(1.0))
                    score += idf * (tf * 2.2 / denominator)
                }
            }
            val titleTokens = tokens(chunk.documentName).toSet()
            score += queryTokens.count { it in titleTokens } * 1.8
            val normalizedChunk = normalize(chunk.text)
            if (queryPhrase.length >= 8 && normalizedChunk.contains(queryPhrase)) score += 3.0
            // Reward matching adjacent query terms without requiring the whole query verbatim.
            val bigrams = queryTokens.zipWithNext()
            score += bigrams.count { (a, b) -> normalizedChunk.contains("$a $b") } * 0.7
            if (score > 0.05) RetrievedDocumentChunk(
                chunk.documentName, chunk.location, chunk.page, chunk.text, score
            ) else null
        }.sortedByDescending { it.score }

        // Keep results useful: avoid filling the entire context with adjacent chunks
        // from one long document when other relevant files exist.
        val selected = mutableListOf<RetrievedDocumentChunk>()
        val perDocument = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        for (item in scored) {
            val key = item.documentName.lowercase(Locale.ROOT)
            val signature = key + "|" + item.page + "|" + item.text.take(120)
            if (!seen.add(signature)) continue
            if ((perDocument[key] ?: 0) >= 2) continue
            selected += item
            perDocument[key] = (perDocument[key] ?: 0) + 1
            if (selected.size >= limit) break
        }
        return selected
    }

    fun formatContext(results: List<RetrievedDocumentChunk>, maxChars: Int = 10_000): String {
        if (results.isEmpty() || maxChars <= 0) return ""
        return buildString {
            results.forEachIndexed { i, item ->
                val source = if (item.page != null) "${item.documentName}, page ${item.page}" else item.documentName
                val block = "[SOURCE ${i + 1}: $source]\n${item.text}\n\n"
                if (length + block.length > maxChars) {
                    val remaining = maxChars - length
                    if (remaining > 80) append(block.take(remaining))
                    return@buildString
                }
                append(block)
            }
        }
    }

    private fun split(doc: LocalDocument): List<Chunk> {
        val text = doc.content
        val pages = Regex("\\[PAGE (\\d+)]").findAll(text).toList()
        if (pages.isEmpty()) return chunksFor(doc, null, text)
        val result = mutableListOf<Chunk>()
        pages.forEachIndexed { i, match ->
            val start = match.range.last + 1
            val end = pages.getOrNull(i + 1)?.range?.first ?: text.length
            result += chunksFor(doc, match.groupValues[1].toIntOrNull(), text.substring(start, end))
        }
        return result
    }

    private fun chunksFor(doc: LocalDocument, page: Int?, text: String): List<Chunk> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val result = mutableListOf<Chunk>()
        val size = 900
        val overlap = 140
        var start = 0
        while (start < clean.length) {
            var end = (start + size).coerceAtMost(clean.length)
            if (end < clean.length) {
                val boundary = clean.lastIndexOf(' ', end)
                if (boundary > start + size / 2) end = boundary
            }
            result += Chunk(doc.name, doc.location, page, clean.substring(start, end).trim())
            if (end == clean.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return result
    }

    // Unicode regex properties are code-point aware and retain Telugu vowel signs,
    // other Indic combining marks, Latin letters, and digits as part of the same token.
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
            .trim()

    private fun tokens(text: String): List<String> =
        normalize(text).split(Regex("\\s+")).filter { it.isNotBlank() && (it.length >= 2 || it.any { ch -> ch.isDigit() }) }
}
