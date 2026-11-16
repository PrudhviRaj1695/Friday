package com.friday.app

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class LocalDocument(
    val name: String,
    val location: String,
    val mimeType: String,
    val sizeBytes: Long,
    val content: String = ""
)

object LocalDocumentIndex {
    private const val MAX_FILES = 1500
    private const val MAX_TEXT_BYTES = 20_000_000L
    private const val MAX_EXTRACTED_CHARS = 180_000
    private val supported = setOf("txt","md","csv","json","xml","html","htm","log","pdf","docx","xlsx","pptx","jpg","jpeg","png","webp")

    fun scan(context: Context): List<LocalDocument> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val prefs = context.getSharedPreferences("friday_storage", Context.MODE_PRIVATE)
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse)
        val results = mutableListOf<LocalDocument>()
        if (tree != null) scanTree(context, tree, results)
        else if (Environment.isExternalStorageManager()) scanFiles(context, Environment.getExternalStorageDirectory(), results)
        else error("Grant broad shared-storage access or select a folder in FRIDAY Setup first.")
        return results.sortedBy { it.name.lowercase(Locale.ROOT) }.take(MAX_FILES)
    }

    private fun scanTree(context: Context, tree: Uri, out: MutableList<LocalDocument>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walkTree(context, tree, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), out, 0)
    }

    private fun walkTree(context: Context, tree: Uri, parent: Uri, out: MutableList<LocalDocument>, depth: Int) {
        if (depth > 12 || out.size >= MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && out.size < MAX_FILES) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: "Unnamed"
                val mime = cursor.getString(2) ?: "application/octet-stream"
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walkTree(context, tree, uri, out, depth + 1)
                else if (isSupported(name)) {
                    val extracted = if (size in 1..MAX_TEXT_BYTES) runCatching { context.contentResolver.openInputStream(uri)?.use { extract(context, name, it) }.orEmpty() }.getOrDefault("") else ""
                    out += LocalDocument(name, uri.toString(), mime, size, extracted.take(MAX_EXTRACTED_CHARS))
                }
            }
        }
    }

    private fun scanFiles(context: Context, root: File, out: MutableList<LocalDocument>) {
        root.walkTopDown().onEnter { dir -> !dir.path.contains("/Android/data/") && !dir.path.contains("/Android/obb/") && out.size < MAX_FILES }.forEach { file ->
            if (out.size >= MAX_FILES) return
            if (file.isFile && !file.path.contains("/Android/data/") && !file.path.contains("/Android/obb/") && isSupported(file.name)) {
                val content = if (file.length() in 1..MAX_TEXT_BYTES) runCatching { file.inputStream().use { extract(context, file.name, it) } }.getOrDefault("") else ""
                out += LocalDocument(file.name, file.absolutePath, mimeFor(file.name), file.length(), content.take(MAX_EXTRACTED_CHARS))
            }
        }
    }

    private fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in supported

    private fun extract(context: Context, name: String, input: InputStream): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg","jpeg","png","webp" -> {
                val bitmap = BitmapFactory.decodeStream(input) ?: return ""
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try { Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text } finally { recognizer.close(); bitmap.recycle() }
            }
            "pdf" -> PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val renderer = PDFRenderer(doc)
                buildString {
                    for (page in 1..doc.numberOfPages.coerceAtMost(80)) {
                        stripper.startPage = page
                        stripper.endPage = page
                        var pageText = stripper.getText(doc).trim()
                        if (pageText.isBlank()) {
                            val bitmap = runCatching { renderer.renderImageWithDPI(page - 1, 130f) }.getOrNull()
                            if (bitmap != null) {
                                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                pageText = try { Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text }
                                    finally { recognizer.close(); bitmap.recycle() }
                            }
                        }
                        append('\n').append("[PAGE ").append(page).append("]\n").append(pageText).append('\n')
                        if (length >= MAX_EXTRACTED_CHARS) break
                    }
                }
            }
            "docx" -> zipText(input, listOf("word/document.xml","word/header1.xml","word/footer1.xml"))
            "xlsx" -> zipText(input, listOf("xl/sharedStrings.xml","xl/worksheets/sheet1.xml","xl/worksheets/sheet2.xml","xl/worksheets/sheet3.xml"))
            "pptx" -> zipText(input, (1..100).map { "ppt/slides/slide$it.xml" })
            "html","htm" -> input.bufferedReader().use { stripMarkup(it.readText()) }
            else -> input.bufferedReader().use { it.readText() }
        }
    }

    private fun zipText(input: InputStream, wanted: List<String>): String {
        val names = wanted.toSet()
        val out = StringBuilder()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in names) {
                    val xml = String(zip.readBytes(), Charsets.UTF_8)
                    out.append('\n').append(stripMarkup(xml)).append('\n')
                }
                zip.closeEntry()
                if (out.length >= MAX_EXTRACTED_CHARS) break
            }
        }
        return out.toString()
    }

    private fun stripMarkup(value: String): String = value
        .replace(Regex("<w:tab[^>]*/>|<a:tab[^>]*/>"), " ")
        .replace(Regex("</(w:p|a:p|row|c|si|p|h[1-6]|div|tr|li)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
        .replace("&quot;","\"").replace("&apos;","'")
        .replace("&#10;","\n").replace(Regex("[\\t\\r ]+")," ")
        .replace(Regex("\\n{3,}"),"\n\n").trim()

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "txt","log" -> "text/plain"; "md" -> "text/markdown"; "csv" -> "text/csv"; "json" -> "application/json"
        "xml" -> "application/xml"; "html","htm" -> "text/html"; "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
