package com.freedomfighter.readersnotes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.freedomfighter.readersnotes.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pictures and drawings in a note (docs/PICTURES.md, shared with the desktop app): a picture is
 * a line of its own in the text, `![](.pictures/<name>)`; the file sits in `.pictures/` at the root
 * of the synced folder, and here in `filesDir/pictures/`. A file is never rewritten: a drawing
 * drawn on again is a new file, and the note's line changes to it.
 */
object Pictures {
    const val FOLDER = ".pictures"
    private val LINE = Regex("""^!\[[^\]]*]\(\.pictures/([A-Za-z0-9][A-Za-z0-9._-]*\.(?:[pP][nN][gG]|[jJ][pP][eE]?[gG]|[wW][eE][bB][pP]))\)$""")
    /** Files no note holds any more are kept that long, for another device still on its way to say otherwise. */
    const val KEEP_MS = 2L * 24 * 3600 * 1000
    const val PHOTO_SIDE = 1600
    const val DRAWING_SIDE = 2000

    /** The picture's file name when [line] is a picture line, else null. */
    fun nameOf(line: String): String? = LINE.matchEntire(line.trim())?.groupValues?.get(1)
    fun isLine(line: String) = nameOf(line) != null
    fun line(name: String) = "![]($FOLDER/$name)"
    /** The picture names of [text], in order, each once. */
    fun of(text: String): List<String> = text.lineSequence().mapNotNull { nameOf(it) }.distinct().toList()
    /** [text] without its picture lines: what is searched, shared as text, shown as a preview. */
    fun plain(text: String): String = if (!text.contains("](.pictures/")) text else text.lines().filter { !isLine(it) }.joinToString("\n")
    fun isDrawing(name: String) = name.startsWith("drawing-")

    fun newName(drawing: Boolean): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val hex = (0 until 4).map { "0123456789abcdef".random() }.joinToString("")
        return if (drawing) "drawing-$stamp-$hex.png" else "picture-$stamp-$hex.jpg"
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

    /** What the note is called when it has no line of text: its first picture says what it is. */
    fun titleOr(context: Context, text: String): String = NotesStore.titleOf(text).ifBlank {
        val first = of(text).firstOrNull()
        context.getString(when { first == null -> R.string.untitled; isDrawing(first) -> R.string.drawing; else -> R.string.picture })
    }

    // ---- files here ---------------------------------------------------------------------

    fun dir(filesDir: File) = File(filesDir, "pictures").apply { mkdirs() }
    fun file(filesDir: File, name: String) = File(dir(filesDir), name)

    /** Written aside then renamed, so a half-written file never passes for a picture. */
    fun write(filesDir: File, name: String, bytes: ByteArray) {
        val f = file(filesDir, name); val tmp = File(f.parentFile, "$name.part")
        tmp.writeBytes(bytes); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** A picture from the gallery or another app: turned upright, its long side brought to [PHOTO_SIDE], saved as JPEG. Returns its name, or null when it cannot be read. */
    fun importPhoto(context: Context, uri: Uri): String? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull()
        val m = Matrix()
        when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val long = maxOf(raw.width, raw.height)
        if (long > PHOTO_SIDE) { val s = PHOTO_SIDE.toFloat() / long; m.postScale(s, s) }
        var bmp = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        // JPEG has no transparency: a transparent picture goes on white, not on black
        if (bmp.hasAlpha()) {
            val white = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(white).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp = white
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val name = newName(drawing = false)
        write(context.filesDir, name, out.toByteArray())
        name
    }.getOrNull()

    fun saveDrawing(filesDir: File, bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val name = newName(drawing = true)
        write(filesDir, name, out.toByteArray())
        return name
    }

    /** Decoded at most [width] pixels wide (the page's), null when the file is not here (yet). */
    fun load(filesDir: File, name: String, width: Int): Bitmap? {
        val f = file(filesDir, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (width > 0 && bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Pictures here that no note holds and that are older than [KEEP_MS] go. */
    fun cleanUp(filesDir: File, referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        dir(filesDir).listFiles()?.forEach { f ->
            val stale = now - f.lastModified() > KEEP_MS
            if (f.name.endsWith(".part") && stale) f.delete()
            else if (f.name !in referenced && stale) f.delete()
        }
    }
}
