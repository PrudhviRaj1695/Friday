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

object LocalDocumentRetriever {
    private val stopWords = setOf("the","and","for","that","with","from","this","what","when","where","which","about","your","you","are","was","were","have","has","had","does","did","can","could","would","should","into","onto","how","why","who","whom","their","there","them","then","than","but","not","all","any","our","out")

    fun retrieve(query: String, documents: List<LocalDocument>, limit: Int = 5): List<RetrievedDocumentChunk> {
        val terms = tokens(query).filter { it !in stopWords }.distinct()
        if (terms.isEmpty()) return emptyList()
        val chunks = documents.filter { it.content.isNotBlank() }.flatMap { doc -> split(doc) }
        if (chunks.isEmpty()) return emptyList()
        val tokenized = chunks.map { tokens(it.text) }
        val df = terms.associateWith { term -> tokenized.count { term in it } }
        val avgLength = tokenized.map { it.size }.average().coerceAtLeast(1.0)
        return chunks.mapIndexedNotNull { index, chunk ->
            val words = tokenized[index]
            var score = 0.0
            terms.forEach { term ->
                val tf = words.count { it == term }
                if (tf > 0) {
                    val idf = ln(1.0 + (chunks.size - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                    val denominator = tf + 1.2 * (1.0 - 0.75 + 0.75 * words.size / avgLength)
                    score += idf * (tf * 2.2 / denominator)
                }
            }
            val titleTerms = tokens(chunk.documentName).toSet()
            score += terms.count { it in titleTerms } * 1.5
            if (score > 0.0) RetrievedDocumentChunk(chunk.documentName, chunk.location, chunk.page, chunk.text, score) else null
        }.sortedByDescending { it.score }.distinctBy { it.documentName + "|" + it.page + "|" + it.text.take(100) }.take(limit)
    }

    fun formatContext(results: List<RetrievedDocumentChunk>, maxChars: Int = 10_000): String {
        if (results.isEmpty()) return ""
        return buildString {
            results.forEachIndexed { i, item ->
                val source = if (item.page != null) "${item.documentName}, page ${item.page}" else item.documentName
                append("[SOURCE ${i + 1}: ").append(source).append("]\n")
                append(item.text).append("\n\n")
                if (length >= maxChars) return@buildString
            }
        }.take(maxChars)
    }

    private data class Chunk(val documentName: String, val location: String, val page: Int?, val text: String)

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
        val size = 1100
        val overlap = 180
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

    private fun tokens(text: String): List<String> = text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
}
