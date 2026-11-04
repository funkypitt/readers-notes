package com.freedomfighter.readersnotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private val p = Pictures.line("drawing-20261104-093012-a3f9.png")
    private val q = Pictures.line("picture-20261104-093013-0000.jpg")

    @Test fun theFirstLineIsTheHeadingAndThePicturesStayInPlace() {
        val d = PdfLayout.doc("\n  Shopping  \nmilk\n\nbread\n$p\nafter\n$q")
        assertEquals("Shopping", d.heading)
        assertEquals(listOf(
            PdfLayout.Block.Words("milk\n\nbread"),
            PdfLayout.Block.Pic("drawing-20261104-093012-a3f9.png"),
            PdfLayout.Block.Words("after"),
            PdfLayout.Block.Pic("picture-20261104-093013-0000.jpg")), d.blocks)
    }

    @Test fun aPictureFirstThenTheTitle() {
        val d = PdfLayout.doc("$p\nTitle\n\n\nbody\n\n")
        assertEquals("Title", d.heading)
        assertEquals(listOf(PdfLayout.Block.Pic("drawing-20261104-093012-a3f9.png"), PdfLayout.Block.Words("body")), d.blocks)
    }

    @Test fun aNoteOfPicturesOnlyHasNoHeadingOfItsOwn() {
        val d = PdfLayout.doc("$p\n\n$q")
        assertEquals("", d.heading)
        assertEquals(2, d.blocks.size)
        assertEquals(PdfLayout.Doc("", emptyList()), PdfLayout.doc(""))
    }

    @Test fun picturesTakeTheWidthButNeverMoreThanAPage() {
        assertEquals(400f to 300f, PdfLayout.pictureSize(800, 600, 400f, 700f))
        assertEquals(400f to 300f, PdfLayout.pictureSize(80, 60, 400f, 700f))
        // tall: brought down to the page's height, proportions kept
        val (w, h) = PdfLayout.pictureSize(1000, 4000, 400f, 700f)
        assertEquals(700f, h, 0.001f); assertEquals(175f, w, 0.001f)
    }

    private fun lines(n: Int, h: Float) = FloatArray(n) { it * h } to FloatArray(n) { (it + 1) * h }

    @Test fun textFlowsAcrossPages() {
        val (t, b) = lines(25, 10f)
        // 35 left on this page: 3 lines; then 10 a page
        assertEquals(listOf(0..2, 3..12, 13..22, 23..24), PdfLayout.splitLines(t, b, 35f, 100f))
        // all fits
        assertEquals(listOf(0..24), PdfLayout.splitLines(t, b, 1000f, 1000f))
    }

    @Test fun notEvenOneLineFitsHere() {
        val (t, b) = lines(3, 10f)
        val runs = PdfLayout.splitLines(t, b, 5f, 100f)
        assertTrue(runs[0].isEmpty())
        assertEquals(listOf(0..2), runs.drop(1))
        // a line taller than the page gets a page of its own rather than looping
        assertEquals(listOf(0..0), PdfLayout.splitLines(floatArrayOf(0f), floatArrayOf(500f), 100f, 100f).filter { !it.isEmpty() })
        assertEquals(emptyList<IntRange>(), PdfLayout.splitLines(FloatArray(0), FloatArray(0), 10f, 100f))
    }

    @Test fun fileNamesSayWhichAppAndWhichNote() {
        assertEquals("readers-notes-Shopping list.pdf", PdfLayout.fileName("Shopping list"))
        assertEquals("readers-notes-a b c.pdf", PdfLayout.fileName("a/b: c?"))
        assertEquals("readers-notes.pdf", PdfLayout.fileName("  ///  "))
        assertEquals("readers-notes-Été à Zürich.pdf", PdfLayout.fileName("Été à Zürich"))
        assertTrue(PdfLayout.fileName("x".repeat(200)).length <= "readers-notes-.pdf".length + 60)
    }
}
