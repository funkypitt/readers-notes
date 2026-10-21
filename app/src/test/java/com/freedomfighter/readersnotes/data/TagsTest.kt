package com.freedomfighter.readersnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {
    @Test fun `tags are words with a hash, once each, lower case`() =
        assertEquals(listOf("work", "idée", "to-do"), Tags.of("Plan #Work\n#idée and #work again, #to-do."))

    @Test fun `numbers, addresses and words with a hash inside are not tags`() =
        assertEquals(emptyList<String>(), Tags.of("room #12, see https://x.ch/#top and C#"))

    @Test fun `a tag at the very start`() = assertEquals(listOf("a"), Tags.of("#a b"))

    @Test fun `clean makes a tag out of what was typed`() {
        assertEquals("deux-mots", Tags.clean(" #Deux mots "))
        assertEquals(null, Tags.clean("#2026"))
        assertEquals(null, Tags.clean(""))
    }

    @Test fun `add puts the tag on its own line at the end`() =
        assertEquals("Milk\nand eggs\n\n#shop", Tags.add("Milk\nand eggs\n", "shop"))

    @Test fun `add joins a line that holds only tags`() =
        assertEquals("Milk\n\n#shop #week", Tags.add("Milk\n\n#shop", "#Week"))

    @Test fun `add on an empty note and twice`() {
        assertEquals("#a", Tags.add("", "a"))
        assertEquals("Milk #a", Tags.add("Milk #a", "A"))
    }

    @Test fun `remove takes the tag and the space before it`() {
        assertEquals("Milk\n\n#shop", Tags.remove("Milk\n\n#shop #week", "week"))
        assertEquals("Milk\n\n#week", Tags.remove("Milk\n\n#shop #week", "shop"))
        assertEquals("Milk", Tags.remove("Milk\n\n#shop", "shop"))
        assertEquals("Milk and eggs", Tags.remove("Milk #shop and eggs", "shop"))
        assertEquals("Milk", Tags.remove("#shop\nMilk", "shop"))
    }

    @Test fun `remove leaves a tag of another name`() =
        assertEquals("Milk #shopping", Tags.remove("Milk #shopping", "shop"))

    @Test fun `count most used first`() =
        assertEquals(listOf("a" to 2, "b" to 1, "c" to 1), Tags.count(sequenceOf("#b #a", "#c #a")))
}
