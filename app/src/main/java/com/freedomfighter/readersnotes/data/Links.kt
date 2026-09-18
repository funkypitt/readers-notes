package com.freedomfighter.readersnotes.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * What a note holds that can be acted on: a phone number, a mail address, a web address. A note
 * is written in, not read only, so the text itself stays a plain text field (a link under the
 * finger would fight with the cursor); these are offered in the note's ⋯ menu instead.
 */
data class Link(val text: String, val uri: String)

private const val MIN_PHONE_DIGITS = 7      // shorter runs of digits are dates, prices, room numbers…
private const val MAX_PHONE_DIGITS = 15     // E.164

// Our own patterns rather than android.util.Patterns: those read a year as a phone number and a
// sentence's "etc.fr" as a web address, and they are not available to unit tests.
private val EMAIL = Regex("""[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
private val WEB = Regex("""(?:https?://|www\.)[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val PHONE = Regex("""(?<![\w@])\(?\+?\d[\d  ()./\-]{4,}\d(?![\w])""")
private val DATE = Regex("""\d{1,4}[./\-]\d{1,2}[./\-]\d{2,4}""")

/** The phone numbers, mail and web addresses of a text, in order, each one only once. */
fun findLinks(text: String, limit: Int = 8): List<Link> {
    val taken = ArrayList<IntRange>()
    val out = LinkedHashMap<String, Link>()
    fun add(range: IntRange, shown: String, uri: String) {
        if (taken.any { range.first < it.last && it.first < range.last }) return
        taken.add(range)
        out.putIfAbsent(uri, Link(shown, uri))
    }
    for (m in EMAIL.findAll(text)) add(m.range.first..m.range.last + 1, m.value, "mailto:" + m.value)
    for (m in WEB.findAll(text)) {
        val value = m.value.trimEnd('.', ',', ';', ':', ')', '!', '?')
        if (!value.contains('.')) continue
        add(m.range.first..m.range.first + value.length, value, if (value.contains("://")) value else "https://$value")
    }
    for (m in PHONE.findAll(text)) {
        val value = m.value.trim().trimEnd('.', ',', ';', '-', '/')
        val digits = value.count { it.isDigit() }
        if (digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS || DATE.matches(value)) continue
        add(m.range.first..m.range.first + value.length, value, "tel:" + value.replace(" ", "").replace(" ", ""))
    }
    return out.values.sortedBy { text.indexOf(it.text) }.take(limit)
}

/** The intent a link opens: the dialer keeps the number on screen, it is never called. */
fun intentFor(uri: String): Intent = when {
    uri.startsWith("tel:") -> Intent(Intent.ACTION_DIAL, Uri.parse(uri))
    uri.startsWith("mailto:") -> Intent(Intent.ACTION_SENDTO, Uri.parse(uri))
    else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}

fun openLink(context: Context, uri: String): Boolean = try {
    context.startActivity(intentFor(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: ActivityNotFoundException) {
    false
}
