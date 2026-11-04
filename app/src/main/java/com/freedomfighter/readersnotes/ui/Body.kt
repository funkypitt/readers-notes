package com.freedomfighter.readersnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.Pictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A note as it is written on: stretches of text, each its own field, and between them the
 * pictures of the note (docs/PICTURES.md). A note without pictures is one stretch of text.
 *
 * The text of the note is rebuilt from the parts, the picture lines between the stretches, and
 * gives back exactly what was read: opening a note changes nothing in it. A stretch the text did
 * not hold (before a first picture, between two, after the last) is there to write in, and counts
 * once something is written in it.
 */
class Body(text: String, cursor: Int = Int.MAX_VALUE) {
    sealed class Part
    class Words(text: String, val real: Boolean) : Part() {
        var value by mutableStateOf(TextFieldValue(text))
        val focus = FocusRequester()
        val counts get() = real || value.text.isNotEmpty()
    }
    class Pic(val name: String, val line: String) : Part()

    val parts = mutableStateListOf<Part>()
    /** The stretch the cursor is in: where dictated words, a picture or a drawing go. */
    var at by mutableStateOf(0)

    init { set(text, cursor) }

    fun text(): String = parts.filter { it !is Words || it.counts }.joinToString("\n") { if (it is Pic) it.line else (it as Words).value.text }

    /** Where each part starts in [text]; a stretch that does not count starts where it would. */
    private fun starts(): IntArray {
        val out = IntArray(parts.size); var pos = 0; var first = true
        parts.forEachIndexed { i, p ->
            val counts = p !is Words || p.counts
            out[i] = if (first) pos else pos + 1
            if (counts) { if (!first) pos += 1; pos += if (p is Pic) p.line.length else (p as Words).value.text.length; first = false }
        }
        return out
    }

    /** The cursor's place in the whole text. */
    fun cursor(): Int {
        val w = parts.getOrNull(at) as? Words ?: return text().length
        return (starts()[at] + w.value.selection.max).coerceIn(0, text().length)
    }

    /** The note becomes [text], the cursor at [cursor] (in the whole text), in the stretch holding it. */
    fun set(text: String, cursor: Int) {
        val made = ArrayList<Part>()
        val buffer = ArrayList<String>()
        var pending = false
        fun flush(force: Boolean) {
            if (pending) made += Words(buffer.joinToString("\n"), real = true)
            else if (force) made += Words("", real = false)
            buffer.clear(); pending = false
        }
        for (line in text.split("\n")) {
            val name = Pictures.nameOf(line)
            if (name != null) { flush(force = true); made += Pic(name, line) }
            else { buffer += line; pending = true }
        }
        flush(force = true)
        parts.clear(); parts.addAll(made)
        val st = starts()
        val i = parts.indices.lastOrNull { parts[it] is Words && st[it] <= cursor } ?: parts.indices.first { parts[it] is Words }
        val w = parts[i] as Words
        val local = (cursor - st[i]).coerceIn(0, w.value.text.length)
        w.value = w.value.copy(selection = TextRange(local))
        at = i
    }

    /** [line] on a line of its own at the cursor; the cursor goes just after it. */
    fun insertLine(line: String) {
        val t = text(); val c = cursor()
        val (out, after) = withLine(t, c, line)
        set(out, after)
    }

    fun remove(index: Int) {
        val st = starts()
        parts.removeAt(index)
        set(text(), st[index].coerceAtLeast(1) - 1)
    }

    fun replace(old: String, new: String) {
        val c = cursor()
        set(text().split("\n").joinToString("\n") { if (Pictures.nameOf(it) == old) Pictures.line(new) else it }, c)
    }

    /** Words dictated at the cursor, a space or nothing around them as the text asks. */
    fun insertWords(words: String) {
        val w = parts.getOrNull(at) as? Words ?: return
        val v = w.value
        val before = v.text.substring(0, v.selection.min)
        val after = v.text.substring(v.selection.max)
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isEmpty() || after.first().isWhitespace()) "" else " "
        val inserted = before + lead + words + trail
        w.value = TextFieldValue(inserted + after, TextRange(inserted.length))
    }

    companion object {
        /** [text] with [line] on a line of its own at [at], and where the cursor goes after it. */
        fun withLine(text: String, at: Int, line: String): Pair<String, Int> {
            val c = at.coerceIn(0, text.length)
            val before = text.substring(0, c); val after = text.substring(c)
            val head = if (before.isEmpty() || before.endsWith("\n")) before else before + "\n"
            val tail = if (after.isEmpty()) "" else if (after.startsWith("\n")) after else "\n" + after
            return (head + line + tail) to (head.length + line.length + 1)
        }
    }
}

/** A picture of the note, as wide as the page; tapping it gives [onTap]. Not here yet: a frame saying so. */
@Composable
fun PictureView(app: App, name: String, onTap: () -> Unit) {
    val colors = LocalColors.current
    val arrived by app.store.pictureTick.collectAsState()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 8.dp)) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bmp by produceState<Bitmap?>(null, name, arrived, px) { value = withContext(Dispatchers.IO) { Pictures.load(app.store.filesDir, name, px) } }
        val b = bmp
        if (b != null) {
            // a tall drawing keeps room on the page for the text around it
            val ratio = b.width.toFloat() / b.height.coerceAtLeast(1)
            val tallest = LocalConfiguration.current.screenHeightDp.dp * 0.55f
            val w = minOf(maxWidth, tallest * ratio)
            Image(b.asImageBitmap(), null, Modifier.width(w).aspectRatio(ratio).border(1.dp, colors.rule).noRippleClickable(onClick = onTap), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxWidth().height(96.dp).border(1.dp, colors.rule).noRippleClickable(onClick = onTap), contentAlignment = Alignment.Center) {
                Small(stringResource(R.string.picture_missing))
            }
        }
    }
}
