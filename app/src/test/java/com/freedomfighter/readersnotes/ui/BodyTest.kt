package com.freedomfighter.readersnotes.ui

import com.freedomfighter.readersnotes.data.Pictures
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyTest {
    private val p = Pictures.line("drawing-20261104-093012-a3f9.png")
    private val q = Pictures.line("picture-20261104-093013-0000.jpg")

    @Test fun openingANoteChangesNothingInIt() {
        for (t in listOf("", "a", "a\n", "\n", "a\nb", p, "$p\n", "\n$p", "a\n$p", "$p\nb", "a\n\n$p\n\nb", "$p\n$q", "$p\n\n$q", "a\n$p\n$q\nb\n", "  $p  ", "x\n$p\n")) {
            assertEquals("[$t]", t, Body(t).text())
        }
    }

    @Test fun aPictureIsTextOnALineOfItsOwn() {
        val b = Body("hello world", 5)
        b.insertLine(p)
        assertEquals("hello\n$p\n world", b.text())
        val c = Body("", 0); c.insertLine(p); assertEquals(p, c.text())
        val d = Body("end"); d.insertLine(p); assertEquals("end\n$p", d.text())
        // and the cursor is after it: what is written next goes below
        (d.parts[d.at] as Body.Words).let { it.value = it.value.copy(text = "more") }
        assertEquals("end\n$p\nmore", d.text())
    }

    @Test fun removingAPictureJoinsTheTextAround() {
        val b = Body("a\n$p\nb")
        b.remove(1)
        assertEquals("a\nb", b.text())
        val c = Body(p); c.remove(1); assertEquals("", c.text())
    }

    @Test fun aDrawingDrawnOnAgainTakesItsPlace() {
        val b = Body("a\n$p\nb")
        b.replace("drawing-20261104-093012-a3f9.png", "drawing-20261104-100000-ffff.png")
        assertEquals("a\n" + Pictures.line("drawing-20261104-100000-ffff.png") + "\nb", b.text())
    }

    @Test fun theCursorStartsAtTheVeryEnd() {
        val b = Body("a\n$p")
        b.insertWords("said")
        assertEquals("a\n$p\nsaid", b.text())
    }
}
