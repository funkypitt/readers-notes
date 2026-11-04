package com.freedomfighter.readersnotes.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.freedomfighter.readersnotes.R
import java.io.File
import java.io.OutputStream

/**
 * One note as a PDF to print or send: A4 pages, black on white whatever the colour theme, the
 * note's first line as the heading, then its text and its pictures in their order, a small
 * page number at the foot of each page. Saved where the person chooses, a copy kept for the
 * share menu offered right after.
 */
object PdfExport {
    // A4 in PostScript points
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val SIDE = 64f
    private const val TOP = 64f
    private const val BOTTOM = 72f
    private const val BODY_SIZE = 12f
    private const val HEADING_SIZE = 20f
    private const val SPACING = 1.35f
    private const val GAP = 10f
    private const val MISSING_H = 72f

    private fun dir(context: Context) = File(context.cacheDir, "exports")

    /** Renders [text] to the chosen document and keeps a copy named [name] to share. False when it could not be written. */
    fun save(context: Context, filesDir: File, uri: Uri, name: String, text: String, font: FontChoice): Boolean = runCatching {
        val d = dir(context)
        d.listFiles()?.forEach { it.delete() }
        d.mkdirs()
        val copy = File(d, name)
        copy.outputStream().use { render(context, filesDir, text, font, it) }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> copy.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    fun share(context: Context, name: String, chooserTitle: String) {
        val file = File(dir(context), name)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".credentials", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** The faces of the app's font setting: the text in the weight the app shows it, the heading a step heavier. */
    private fun faces(font: FontChoice): Pair<Typeface, Typeface> = when (font) {
        FontChoice.SANS -> Typeface.create("sans-serif-light", Typeface.NORMAL) to Typeface.create("sans-serif", Typeface.NORMAL)
        FontChoice.SERIF -> Typeface.SERIF to Typeface.create(Typeface.SERIF, Typeface.BOLD)
        FontChoice.MONO -> Typeface.MONOSPACE to Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** Writes the PDF to [out]; returns the number of pages. */
    fun render(context: Context, filesDir: File, text: String, font: FontChoice, out: OutputStream): Int {
        val doc = PdfLayout.doc(text)
        val heading = doc.heading.ifEmpty { Pictures.titleOr(context, text) }
        val missing = context.getString(R.string.picture_missing)
        // first a dry run to count the pages, so that each can say « 2 / 5 »
        val total = lay(null, filesDir, heading, doc.blocks, font, missing, 0)
        val pdf = PdfDocument()
        try {
            lay(pdf, filesDir, heading, doc.blocks, font, missing, total)
            pdf.writeTo(out)
        } finally { pdf.close() }
        return total
    }

    private fun lay(pdf: PdfDocument?, filesDir: File, heading: String, blocks: List<PdfLayout.Block>, font: FontChoice, missing: String, total: Int): Int {
        val (bodyFace, headFace) = faces(font)
        val width = PAGE_W - 2 * SIDE
        val limit = PAGE_H - BOTTOM
        val room = limit - TOP
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = BODY_SIZE; typeface = bodyFace }
        val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = HEADING_SIZE; typeface = headFace }
        val grey = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x80, 0x80, 0x80); textSize = 9f; typeface = bodyFace; textAlign = Paint.Align.CENTER }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xB0, 0xB0, 0xB0); style = Paint.Style.STROKE; strokeWidth = 0.75f }
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        var number = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = TOP
        fun finish() {
            val c = canvas
            if (c != null && total > 0) c.drawText("$number / $total", PAGE_W / 2f, PAGE_H - 36f, grey)
            page?.let { pdf?.finishPage(it) }
            page = null; canvas = null
        }
        fun newPage() {
            finish()
            number++
            page = pdf?.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
            canvas = page?.canvas?.also { it.drawColor(Color.WHITE) }
            y = TOP
        }
        fun layout(s: String, paint: TextPaint) = StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, SPACING).setIncludePad(false).build()
        fun flow(l: StaticLayout) {
            val n = l.lineCount
            val tops = FloatArray(n) { l.getLineTop(it).toFloat() }
            val bottoms = FloatArray(n) { l.getLineBottom(it).toFloat() }
            for ((k, run) in PdfLayout.splitLines(tops, bottoms, limit - y, room).withIndex()) {
                if (k > 0 || run.isEmpty()) newPage()
                if (run.isEmpty()) continue
                val top = tops[run.first]; val bottom = bottoms[run.last]
                canvas?.let { c ->
                    c.save()
                    c.translate(SIDE, y - top)
                    c.clipRect(0f, top, width, bottom)
                    l.draw(c)
                    c.restore()
                }
                y += bottom - top
            }
        }

        newPage()
        flow(layout(heading, head))
        y += GAP * 1.6f
        for (b in blocks) when (b) {
            is PdfLayout.Block.Words -> { flow(layout(b.text, body)); y += GAP * 0.6f }
            is PdfLayout.Block.Pic -> {
                val f = Pictures.file(filesDir, b.name)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (f.exists()) BitmapFactory.decodeFile(f.path, bounds)
                val there = bounds.outWidth > 0 && bounds.outHeight > 0
                val (w, h) = if (there) PdfLayout.pictureSize(bounds.outWidth, bounds.outHeight, width, room) else width to MISSING_H
                if (y + h > limit && y > TOP) newPage()
                y += GAP * 0.4f
                canvas?.let { c ->
                    val r = RectF(SIDE + (width - w) / 2, y, SIDE + (width + w) / 2, y + h)
                    // about 200 pixels to the inch on paper: sharp, and the file stays light
                    val bmp = if (there) Pictures.load(filesDir, b.name, (w * 2.8f).toInt()) else null
                    if (bmp != null) { c.drawBitmap(bmp, null, r, smooth); bmp.recycle() }
                    else {
                        c.drawRect(r, frame)
                        grey.textSize = 10f
                        c.drawText(missing, r.centerX(), r.centerY() - (grey.descent() + grey.ascent()) / 2, grey)
                        grey.textSize = 9f
                    }
                }
                y += h + GAP
            }
        }
        finish()
        return number
    }
}
