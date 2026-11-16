package com.friday.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

data class LocalDocument(
    val name: String,
    val location: String,
    val mimeType: String,
    val sizeBytes: Long,
    val content: String = ""
)

object LocalDocumentIndex {
    private const val MAX_FILES = 1500
    private const val MAX_TEXT_BYTES = 180_000L
    private val readableExtensions = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log")

    fun scan(context: Context): List<LocalDocument> {
        val prefs = context.getSharedPreferences("friday_storage", Context.MODE_PRIVATE)
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse)
        val results = mutableListOf<LocalDocument>()
        if (tree != null) {
            runCatching { scanTree(context, tree, results) }
        } else if (Environment.isExternalStorageManager()) {
            val root = Environment.getExternalStorageDirectory()
            scanFiles(root, results)
        } else {
            error("Grant broad shared-storage access or select a folder in FRIDAY Setup first.")
        }
        return results.sortedBy { it.name.lowercase(Locale.ROOT) }.take(MAX_FILES)
    }

    private fun scanTree(context: Context, tree: Uri, out: MutableList<LocalDocument>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        walkTree(context, tree, root, out, 0)
    }

    private fun walkTree(context: Context, tree: Uri, parent: Uri, out: MutableList<LocalDocument>, depth: Int) {
        if (depth > 12 || out.size >= MAX_FILES) return
        val id = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && out.size < MAX_FILES) {
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: "Unnamed"
                val mime = cursor.getString(2) ?: "application/octet-stream"
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(context, tree, child, out, depth + 1)
                } else {
                    val text = if (isReadable(name) && size <= MAX_TEXT_BYTES) {
                        runCatching { context.contentResolver.openInputStream(child)?.bufferedReader()?.use { it.readText().take(30000) }.orEmpty() }.getOrDefault("")
                    } else ""
                    out += LocalDocument(name, child.toString(), mime, size, text)
                }
            }
        }
    }

    private fun scanFiles(root: File, out: MutableList<LocalDocument>) {
        root.walkTopDown().onEnter { dir ->
            !dir.path.contains("/Android/data/") && !dir.path.contains("/Android/obb/") && out.size < MAX_FILES
        }.forEach { file ->
            if (out.size >= MAX_FILES) return
            if (file.isFile && !file.path.contains("/Android/data/") && !file.path.contains("/Android/obb/")) {
                val text = if (isReadable(file.name) && file.length() <= MAX_TEXT_BYTES) {
                    runCatching { file.inputStream().bufferedReader().use { it.readText().take(30000) } }.getOrDefault("")
                } else ""
                out += LocalDocument(file.name, file.absolutePath, mimeFor(file.name), file.length(), text)
            }
        }
    }

    private fun isReadable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in readableExtensions

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/msword"
        else -> "application/octet-stream"
    }
}
