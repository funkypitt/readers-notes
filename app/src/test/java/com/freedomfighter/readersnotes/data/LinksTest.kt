package com.freedomfighter.readersnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private fun uris(text: String) = findLinks(text).map { it.uri }

    @Test fun `a number to call`() = assertEquals(listOf("tel:+41216541234"), uris("dentiste +41 21 654 12 34"))

    @Test fun `dates prices and room numbers are left alone`() =
        assertEquals(emptyList<String>(), uris("le 17.09.2026, salle 12, 45 francs, de 10 à 12"))

    @Test fun `mail and web`() =
        assertEquals(listOf("mailto:anna@example.ch", "https://www.gallaz.ch"), uris("anna@example.ch puis www.gallaz.ch."))

    @Test fun `the same link twice is offered once`() =
        assertEquals(listOf("tel:0216541234"), uris("021 654 12 34 et encore 021 654 12 34"))

    @Test fun `order follows the text`() =
        assertEquals(listOf("mailto:a@b.ch", "tel:+41216541234"), uris("écrire a@b.ch ou appeler +41 21 654 12 34"))

    @Test fun `a note without anything to open`() =
        assertEquals(emptyList<String>(), uris("acheter du pain et relire les notes"))

    @Test fun `at most eight`() =
        assertEquals(8, findLinks((1..12).joinToString(" ") { "a$it@b.ch" }).size)

    @Test fun `what is shown is the text itself`() =
        assertEquals(listOf("+41 21 654 12 34"), findLinks("appeler +41 21 654 12 34 demain").map { it.text })
}
