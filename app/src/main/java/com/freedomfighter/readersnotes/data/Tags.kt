package com.freedomfighter.readersnotes.data

/**
 * Tags (1.11.0). A tag is a word with # in front, written in the note's text: "#work", "#idée".
 * The note is a plain text file kept in step with other devices, so its tags travel inside it, and
 * any editor shows them. Here and on the desktop the same rule reads them.
 *
 * A tag starts at the beginning of the text or after white space, holds letters, digits, "_" and
 * "-", and has at least one letter (so "#1" or "#2026" is not a tag). Tags are compared without
 * case and kept in lower case.
 */
object Tags {
    private val TAG = Regex("(?<=^|\\s)#([\\p{L}\\p{N}_-]+)")

    /** Looks like a tag: letters and such, at least one letter. */
    private fun isTag(word: String) = word.any { it.isLetter() }

    /** The tags of a text, lower case, in the order they appear, each once. */
    fun of(text: String): List<String> =
        TAG.findAll(text).map { it.groupValues[1].trimEnd('-') }.filter { isTag(it) }.map { it.lowercase() }.distinct().toList()

    /** The tag as the person typed it, made into a tag: "#", spaces and capitals gone; null when nothing is left. */
    fun clean(name: String): String? {
        val t = name.trim().trimStart('#').replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}_-]"), "").trim('-').lowercase()
        return t.takeIf { it.isNotEmpty() && isTag(it) }
    }

    /** A line made only of tags: that is where a tag added from the menu goes. */
    fun isTagLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.split(Regex("\\s+")).all { it.startsWith("#") && it.length > 1 }
    }

    /**
     * The text with [tag] added: on the last line when it holds only tags, else on a new line
     * after a blank one, as a line of its own at the end. Unchanged when the tag is there already.
     */
    fun add(text: String, tag: String): String {
        val t = clean(tag) ?: return text
        if (t in of(text)) return text
        val lines = text.trimEnd().split('\n')
        return when {
            text.isBlank() -> "#$t"
            isTagLine(lines.last()) -> lines.dropLast(1).joinToString("\n") + (if (lines.size > 1) "\n" else "") + lines.last().trimEnd() + " #$t"
            else -> text.trimEnd() + "\n\n#$t"
        }
    }

    /** The text without [tag]: each "#tag" gone with the space before it; a line left empty at the end goes too. */
    fun remove(text: String, tag: String): String {
        val t = clean(tag) ?: return text
        val out = StringBuilder()
        var last = 0
        for (m in TAG.findAll(text)) {
            if (m.groupValues[1].trimEnd('-').lowercase() != t) continue
            val range = m.range
            // the space before the tag goes with it, unless the tag starts a line
            var start = range.first
            while (start > last && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
            out.append(text, last, start)
            last = range.last + 1
            // a tag that started its line: the spaces after it go instead, and the line break when the line is left empty
            if (start == range.first && (start == 0 || text[start - 1] == '\n')) {
                while (last < text.length && (text[last] == ' ' || text[last] == '\t')) last++
                if (last < text.length && text[last] == '\n') last++
            }
        }
        out.append(text, last, text.length)
        // lines emptied at the end are dropped, and the blank line that was before the tag line
        return out.toString().trimEnd(' ', '\t', '\n').let { if (it.isEmpty()) "" else it }
    }

    /** Every tag of these notes, with the number of notes carrying it, most used first then by name. */
    fun count(texts: Sequence<String>): List<Pair<String, Int>> {
        val n = HashMap<String, Int>()
        texts.forEach { text -> of(text).forEach { n[it] = (n[it] ?: 0) + 1 } }
        return n.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
    }

    /** The tags, with # in front, in one line: "#work #idée". */
    fun label(tags: List<String>) = tags.joinToString(" ") { "#$it" }
}
