package com.freedomfighter.readersnotes.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    @Test fun buildWritesOnlyOurSection() {
        val root = JSONObject(Credentials.build("https://1.connect.kdrive.infomaniak.com", "Notes", "me@x.ch", "p\"w"))
        assertEquals("readers-credentials", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals(setOf("format", "version", "readers-notes"), root.keys().asSequence().toSet())
        val s = root.getJSONObject("readers-notes")
        assertEquals("me@x.ch", s.getString("username")); assertEquals("p\"w", s.getString("password"))
    }

    @Test fun buildOmitsEmptyValues() {
        val s = JSONObject(Credentials.build("", "Notes", "", "")).getJSONObject("readers-notes")
        assertEquals(setOf("folder"), s.keys().asSequence().toSet())
    }

    @Test fun roundTrip() {
        val i = Credentials.read(Credentials.build("https://srv", "Notes/perso", "u", "pw"))
        assertFalse(i.fromFallback)
        assertEquals(Credentials.Account("https://srv", "Notes/perso", "u", "pw"), i.account)
    }

    @Test fun desktopFileWithOtherSectionsAndUnknownKeys() {
        val desktop = """{"format": "readers-credentials", "version": 1,
            "readers-calendar": {"url": "https://sync.infomaniak.com", "google": {"client_id": "x", "tokens": {"refresh_token": "r"}}},
            "readers-tasks": {"url": "https://caldav.tasks.org/", "username": "t", "password": "tp"},
            "readers-notes": {"server": "https://k", "folder": "Notes", "username": "n", "password": "np", "font": "serif", "future": 3}}"""
        val i = Credentials.read(desktop)
        assertEquals(Credentials.Account("https://k", "Notes", "n", "np"), i.account)
        assertFalse(i.fromFallback)
    }

    @Test fun fallbackTakesRecorderServerAndLoginNotFolder() {
        val i = Credentials.read("""{"format": "readers-credentials", "version": 1, "readers-recorder": {"server": "https://k", "folder": "Recordings", "username": "r", "password": "rp"}}""")
        assertTrue(i.fromFallback)
        assertEquals("https://k", i.account.server); assertNull(i.account.folder); assertEquals("rp", i.account.password)
    }

    @Test fun ownSectionWinsOverFallback() {
        val i = Credentials.read("""{"format": "readers-credentials", "readers-recorder": {"server": "https://r"}, "readers-notes": {"server": "https://n"}}""")
        assertEquals("https://n", i.account.server); assertFalse(i.fromFallback)
    }

    @Test(expected = Credentials.NotCredentials::class) fun refusesForeignJson() { Credentials.read("""{"hello": 1}""") }
    @Test(expected = Credentials.NotCredentials::class) fun refusesNonJson() { Credentials.read("server=x") }
    @Test(expected = Credentials.NothingForUs::class) fun nothingForUs() {
        Credentials.read("""{"format": "readers-credentials", "version": 1, "readers-tasks": {"url": "x"}, "readers-notes": {"font": "serif"}}""")
    }
}
