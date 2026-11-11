package com.freedomfighter.readersnotes.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageSettingTest {
    @Test fun `never chosen is found by listening`() = assertEquals("", Prefs.languageSetting(null))

    @Test fun `a language picked by hand stays`() = assertEquals("en", Prefs.languageSetting("en"))

    @Test fun `listening picked by hand stays`() = assertEquals("", Prefs.languageSetting(""))

    @Test fun `the language row starts with listening`() = assertEquals("", Prefs.languages().first())
}
