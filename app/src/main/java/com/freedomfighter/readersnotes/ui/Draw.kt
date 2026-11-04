package com.freedomfighter.readersnotes.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.Pictures

/**
 * A sheet to draw on, pen first. A stylus draws with its pressure; its eraser end, or its button
 * held, erases. Once a stylus has touched the sheet, fingers no longer draw on it (the hand resting
 * on the screen leaves no marks). Without a stylus, a finger draws. Erasing paints the paper.
 */
private class Sheet(val bitmap: Bitmap) {
    private val base: Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    private val canvas = android.graphics.Canvas(bitmap)
    /** x, y, width of each point, in the sheet's pixels. */
    class Stroke(val erase: Boolean) { val pts = ArrayList<Float>() }
    val strokes = ArrayList<Stroke>()
    var current: Stroke? = null
    private val paint = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
    private val dot = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    fun begin(erase: Boolean, x: Float, y: Float, w: Float) {
        val s = Stroke(erase); strokes += s; current = s
        add(x, y, w)
    }

    fun add(x: Float, y: Float, w: Float) {
        val s = current ?: return
        val n = s.pts.size
        s.pts += x; s.pts += y; s.pts += w
        paint(s, if (n == 0) 0 else n / 3 - 1)
    }

    fun end() { current = null }
    /** The system took the touch back (a palm): that stroke never happened. */
    fun cancel() { current?.let { strokes.remove(it) }; current = null; replay() }
    fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1); replay() } }

    private fun replay() {
        canvas.drawBitmap(base, 0f, 0f, null)
        for (s in strokes) for (i in 0 until maxOf(1, s.pts.size / 3 - 1)) paint(s, i)
    }

    /** The segment from point [i] to [i]+1 (or the single dot of a stroke of one point). */
    private fun paint(s: Stroke, i: Int) {
        val colour = if (s.erase) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val p = s.pts
        if (p.size < 6) { dot.color = colour; canvas.drawCircle(p[0], p[1], p[2] / 2, dot); return }
        val a = i * 3; val b = a + 3
        if (b + 2 >= p.size) return
        paint.color = colour; paint.strokeWidth = (p[a + 2] + p[b + 2]) / 2
        canvas.drawLine(p[a], p[a + 1], p[b], p[b + 1], paint)
    }
}

/**
 * [picture] null: a new sheet the size of the page. Otherwise that drawing (or picture), to go
 * on drawing on. Leaving gives the new file's name to [onSaved], or nothing when the sheet was
 * left untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun DrawScreen(nav: Nav, app: App, picture: String?, onSaved: (String) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var eraser by remember { mutableStateOf(false) }
    var penSeen by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var pointer by remember { mutableIntStateOf(-1) }
    val unreadable = stringResource(R.string.picture_unreadable)

    fun leave() {
        val s = sheet
        if (s != null && s.strokes.isNotEmpty()) onSaved(Pictures.saveDrawing(app.store.filesDir, s.bitmap))
        nav.pop()
    }
    BackHandler { leave() }

    // the sheet is made once the page knows its size: a new one fills it, an old one comes as it was
    fun make(page: IntSize) {
        if (sheet != null || page.width <= 0 || page.height <= 0) return
        val bmp = if (picture != null) {
            Pictures.load(app.store.filesDir, picture, 0)?.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            val k = minOf(1f, Pictures.DRAWING_SIDE.toFloat() / maxOf(page.width, page.height))
            Bitmap.createBitmap((page.width * k).toInt().coerceAtLeast(1), (page.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        }
        if (bmp == null) { android.widget.Toast.makeText(view.context, unreadable, android.widget.Toast.LENGTH_SHORT).show(); nav.pop(); return }
        sheet = Sheet(bmp)
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.drawing), onBack = { leave() }, trailing = "↶", onTrailing = { sheet?.undo(); tick++ })
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { size = it; make(it) }) {
                val s = sheet
                // the sheet fitted in the page, centred; the touches are brought back to its pixels
                val k = if (s == null) 1f else minOf(size.width.toFloat() / s.bitmap.width, size.height.toFloat() / s.bitmap.height)
                val ox = if (s == null) 0f else (size.width - s.bitmap.width * k) / 2
                val oy = if (s == null) 0f else (size.height - s.bitmap.height * k) / 2
                val base = with(density) { 2.6f * this.density } / k   // a pen line about 2.6 dp wide
                Canvas(Modifier.fillMaxSize().pointerInteropFilter { ev ->
                    val sh = sheet ?: return@pointerInteropFilter false
                    fun width(i: Int, h: Int = -1): Float {
                        val tool = ev.getToolType(i)
                        val p = if (h < 0) ev.getPressure(i) else ev.getHistoricalPressure(i, h)
                        return when {
                            sh.current?.erase == true -> base * 9
                            tool == MotionEvent.TOOL_TYPE_STYLUS -> base * (0.35f + 1.3f * p.coerceIn(0f, 1f))
                            else -> base * 1.15f
                        }
                    }
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val tool = ev.getToolType(0)
                            val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                            if (!pen && penSeen) { pointer = -1; return@pointerInteropFilter true }   // the hand resting on the sheet
                            if (pen) { penSeen = true; view.requestUnbufferedDispatch(ev) }
                            pointer = ev.getPointerId(0)
                            val erase = eraser || tool == MotionEvent.TOOL_TYPE_ERASER || (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
                            sh.begin(erase, (ev.x - ox) / k, (ev.y - oy) / k, if (erase) base * 9 else width(0))
                            tick++
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val i = ev.findPointerIndex(pointer)
                            if (i < 0 || sh.current == null) return@pointerInteropFilter true
                            for (h in 0 until ev.historySize) sh.add((ev.getHistoricalX(i, h) - ox) / k, (ev.getHistoricalY(i, h) - oy) / k, width(i, h))
                            sh.add((ev.getX(i) - ox) / k, (ev.getY(i) - oy) / k, width(i))
                            tick++
                        }
                        MotionEvent.ACTION_UP -> { sh.end(); pointer = -1; tick++ }
                        MotionEvent.ACTION_CANCEL -> { if (pointer >= 0) sh.cancel(); pointer = -1; tick++ }
                        MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == pointer) { sh.end(); pointer = -1; tick++ }
                    }
                    true
                }) {
                    @Suppress("UNUSED_EXPRESSION") tick   // read: every new point redraws
                    val sh = sheet ?: return@Canvas
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save(); nc.translate(ox, oy); nc.scale(k, k)
                        nc.drawBitmap(sh.bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                        nc.restore()
                    }
                }
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { Side(end = false) { TextRow(stringResource(R.string.pen), inverted = !eraser, size = LocalTypo.current.title) { eraser = false } } }
                Box(Modifier.weight(1f)) { Side(start = false) { TextRow(stringResource(R.string.eraser), inverted = eraser, size = LocalTypo.current.title) { eraser = true } } }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/** A drawing just saved, [name], into its note: a new note, in place of the one drawn on, or at the cursor the note was left with. */
fun placeDrawing(nav: Nav, app: App, s: Screen.Draw, name: String) {
    val line = Pictures.line(name)
    if (s.noteId == null) { app.store.create(line, s.folder); app.sync(); return }
    val text = app.store.text(s.noteId)
    if (s.picture != null && Pictures.of(text).contains(s.picture)) {
        app.store.save(s.noteId, text.split("\n").joinToString("\n") { if (Pictures.nameOf(it) == s.picture) line else it })
        return
    }
    val at = nav.cursor?.takeIf { it.first == s.noteId }?.second ?: text.length
    val (out, after) = Body.withLine(text, at, line)
    app.store.save(s.noteId, out)
    nav.cursor = s.noteId to after
}
