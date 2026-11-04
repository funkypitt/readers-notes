package com.freedomfighter.readersnotes.data

/**
 * The pure part of « export as PDF »: how a note is cut into what goes on the pages, and how
 * those pieces are spread over them. No Android here, so the rules are tested on the computer.
 */
object PdfLayout {
    sealed class Block {
        data class Words(val text: String) : Block()
        data class Pic(val name: String) : Block()
    }

    /** The heading and what follows it: the first line of text is the heading (taken out of the body); pictures stay where their lines are. */
    data class Doc(val heading: String, val blocks: List<Block>)

    fun doc(text: String): Doc {
        val lines = text.replace("\r\n", "\n").split("\n")
        val first = lines.indexOfFirst { it.isNotBlank() && !Pictures.isLine(it) }
        val heading = if (first < 0) "" else lines[first].trim()
        val blocks = mutableListOf<Block>()
        val words = StringBuilder()
        var started = false
        fun flush() {
            if (started) {
                val t = words.toString().replace(Regex("^(?:[ \\t]*\\n)+"), "").trimEnd()
                if (t.isNotEmpty()) blocks.add(Block.Words(t))
            }
            words.clear(); started = false
        }
        lines.forEachIndexed { i, line ->
            if (i == first) return@forEachIndexed
            val pic = Pictures.nameOf(line)
            if (pic != null) { flush(); blocks.add(Block.Pic(pic)) }
            else { if (started) words.append('\n'); words.append(line); started = true }
        }
        flush()
        return Doc(heading, blocks)
    }

    /** A picture [w]×[h] brought to the text's width [maxW], then made smaller if taller than [maxH]; the proportions kept. */
    fun pictureSize(w: Int, h: Int, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (w <= 0 || h <= 0) return maxW to 0f
        var pw = maxW
        var ph = maxW * h / w
        if (ph > maxH) { ph = maxH; pw = maxH * w / h }
        return pw to ph
    }

    /**
     * The lines of a text (their [tops] and [bottoms], measured from the text's top) cut into the
     * runs that fit: the first run in the [firstRoom] left on the current page, the others in a
     * whole page each ([pageRoom]). A first run that is empty means: not even one line fits here,
     * start on a new page. A line taller than a page still gets a page of its own.
     */
    fun splitLines(tops: FloatArray, bottoms: FloatArray, firstRoom: Float, pageRoom: Float): List<IntRange> {
        val n = tops.size
        val runs = mutableListOf<IntRange>()
        var start = 0
        var room = firstRoom
        while (start < n) {
            var end = start
            while (end < n && bottoms[end] - tops[start] <= room) end++
            if (end == start && room < pageRoom) { runs.add(IntRange.EMPTY); room = pageRoom; continue }
            if (end == start) end = start + 1   // taller than a page: alone, cut by the page's edge
            runs.add(start until end)
            start = end
            room = pageRoom
        }
        return runs
    }

    /** The exported file's name: the app's, then the note's title, without the signs file systems refuse. */
    fun fileName(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim()
        return if (clean.isEmpty()) "readers-notes.pdf" else "readers-notes-$clean.pdf"
    }
}
