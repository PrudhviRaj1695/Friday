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
 * Offline hybrid lexical retrieval. Uses BM25 plus title, phrase, adjacency and
 * lightweight Telugu/English query expansion. It deliberately does not pretend
 * to provide embedding-level semantic search.
 */
object LocalDocumentRetriever {
    private val stopWords = setOf(
        "the", "and", "for", "that", "with", "from", "this", "what", "when",
        "where", "which", "about", "your", "you", "are", "was", "were", "have",
        "has", "had", "does", "did", "can", "could", "would", "should", "into",
        "onto", "how", "why", "who", "whom", "their", "there", "them", "then",
        "than", "but", "not", "all", "any", "our", "out", "please", "tell",
        "me", "give", "using", "based", "available", "data", "document", "documents",
        "file", "files", "pdf", "scan", "scanned", "uploaded", "show", "find",
        "explain", "according", "inside", "within", "from", "my", "in"
    )

    private data class Chunk(
        val documentName: String,
        val location: String,
        val page: Int?,
        val text: String
    )

    // Common cross-language concepts; this is a small lexical bridge, not translation.
    private val aliases = mapOf(
        "పేరు" to listOf("name"),
        "తేదీ" to listOf("date", "dated"),
        "మొత్తం" to listOf("total", "amount"),
        "జీతం" to listOf("salary", "pay"),
        "చిరునామా" to listOf("address"),
        "నంబర్" to listOf("number", "no"),
        "పాలసీ" to listOf("policy"),
        "కవరేజ్" to listOf("coverage"),
        "బిల్లు" to listOf("bill", "invoice"),
        "రసీదు" to listOf("receipt"),
        "name" to listOf("పేరు"),
        "date" to listOf("తేదీ"),
        "amount" to listOf("మొత్తం", "total"),
        "salary" to listOf("జీతం", "pay"),
        "address" to listOf("చిరునామా"),
        "policy" to listOf("పాలసీ"),
        "coverage" to listOf("కవరేజ్")
    )

    fun retrieve(query: String, documents: List<LocalDocument>, limit: Int = 6): List<RetrievedDocumentChunk> {
        if (limit <= 0 || query.isBlank()) return emptyList()
        val originalTerms = tokens(query).filter { it !in stopWords }.distinct()
        if (originalTerms.isEmpty()) return emptyList()
        val queryTerms = (originalTerms + originalTerms.flatMap { aliases[it].orEmpty() })
            .filter { it !in stopWords }.distinct()
        if (queryTerms.isEmpty()) return emptyList()

        // Identity-number questions must retrieve the exact identifier type.
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
            .toList()
        if (chunks.isEmpty()) return emptyList()

        val tokenized = chunks.map { tokens(it.text) }
        val avgLength = tokenized.sumOf { it.size }.toDouble() / tokenized.size
        val documentFrequency = queryTerms.associateWith { term -> tokenized.count { term in it } }
        val normalizedQuery = normalize(query)
        val scored = chunks.mapIndexedNotNull { index, chunk ->
            val words = tokenized[index]
            if (words.isEmpty()) return@mapIndexedNotNull null
            var score = 0.0
            queryTerms.forEach { term ->
                val tf = words.count { it == term }
                if (tf > 0) {
                    val df = documentFrequency[term] ?: 0
                    val idf = ln(1.0 + (chunks.size - df + 0.5) / (df + 0.5))
                    val denominator = tf + 1.2 * (1.0 - 0.75 + 0.75 * words.size / avgLength.coerceAtLeast(1.0))
                    score += idf * (tf * 2.2 / denominator)
                }
            }
            val titleTerms = tokens(chunk.documentName).toSet()
            score += queryTerms.count { it in titleTerms } * 2.4
            val normalizedChunk = normalize(chunk.text)
            if (normalizedQuery.length >= 6 && normalizedChunk.contains(normalizedQuery)) score += 5.0
            val compactQuery = originalTerms.joinToString(" ")
            if (compactQuery.length >= 5 && normalizedChunk.contains(compactQuery)) score += 2.0
            val bigrams = queryTerms.zipWithNext()
            score += bigrams.count { (a, b) -> normalizedChunk.contains("$a $b") } * 0.9
            val matched = queryTerms.count { it in words }
            // Reject weak matches where only one incidental term matched a multi-term query.
            val minimumMatches = if (originalTerms.size >= 3) 2 else 1
            if (score > 0.05 && matched >= minimumMatches) {
                RetrievedDocumentChunk(chunk.documentName, chunk.location, chunk.page, chunk.text, score)
            } else null
        }.sortedByDescending { it.score }

        // Prefer relevant passages across files; cap repeated chunks from one document.
        val selected = mutableListOf<RetrievedDocumentChunk>()
        val perDocument = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        for (item in scored) {
            val key = item.documentName.lowercase(Locale.ROOT)
            val signature = key + "|" + item.page + "|" + normalize(item.text).take(180)
            if (!seen.add(signature)) continue
            if ((perDocument[key] ?: 0) >= 3) continue
            selected += item
            perDocument[key] = (perDocument[key] ?: 0) + 1
            if (selected.size >= limit) break
        }
        return selected
    }

    fun formatContext(results: List<RetrievedDocumentChunk>, maxChars: Int = 14_000): String {
        if (results.isEmpty() || maxChars <= 0) return ""
        return buildString {
            results.forEachIndexed { i, item ->
                val source = if (item.page != null) "${item.documentName}, page ${item.page}" else item.documentName
                val block = "[SOURCE ${i + 1}: $source]\n${item.text}\n\n"
                if (length + block.length > maxChars) return@forEachIndexed
                append(block)
            }
        }.take(maxChars)
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

    /**
     * Paragraph/sentence-aware sliding windows. Prefer paragraph and sentence
     * boundaries; retain a 180-character overlap so definitions and values split
     * across a boundary remain retrievable together.
     */
    private fun chunksFor(doc: LocalDocument, page: Int?, text: String): List<Chunk> {
        val clean = text.replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        if (clean.isBlank()) return emptyList()
        val result = mutableListOf<Chunk>()
        val size = 1_100
        val overlap = 180
        var start = 0
        while (start < clean.length) {
            var end = (start + size).coerceAtMost(clean.length)
            if (end < clean.length) {
                val paragraph = clean.lastIndexOf("\n\n", end)
                val sentence = clean.substring(start, end).let { part ->
                    maxOf(part.lastIndexOf(". "), part.lastIndexOf("? "), part.lastIndexOf("! "), part.lastIndexOf("; "))
                } + start
                val boundary = when {
                    paragraph > start + size / 2 -> paragraph
                    sentence > start + size / 2 -> sentence + 1
                    else -> clean.lastIndexOf(' ', end)
                }
                if (boundary > start + size / 2) end = boundary
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotBlank()) result += Chunk(doc.name, doc.location, page, piece)
            if (end >= clean.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return result
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
            .trim()

    private fun tokens(text: String): List<String> =
        normalize(text).split(Regex("\\s+"))
            .filter { it.isNotBlank() && (it.length >= 2 || it.any { ch -> ch.isDigit() }) }
}
