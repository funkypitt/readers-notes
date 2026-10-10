package com.freedomfighter.readersnotes.sync

import com.freedomfighter.readersnotes.data.NotesStore
import com.freedomfighter.readersnotes.data.Settings
import com.freedomfighter.readersnotes.data.encodeSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLDecoder

/** Servers kept in memory: a URL ending with "/" is a folder, any other a file with its text and etag. */
private class FakeDav : Dav {
    val dirs = HashSet<String>()
    val files = HashMap<String, Pair<String, String>>()
    /** Servers that do not answer. */
    val down = HashSet<String>()
    private var counter = 0
    private fun reach(url: String) { if (down.any { url.startsWith(it) }) throw java.io.IOException("unreachable") }

    fun file(url: String, text: String) { files[url] = text to "e${++counter}" }
    /** The names of the files under [folderUrl], subfolders included ("folder/name"). */
    fun names(folderUrl: String) = files.keys.filter { it.startsWith(folderUrl) }.map { URLDecoder.decode(it.removePrefix(folderUrl), "UTF-8") }.toSet()

    override fun list(folderUrl: String): List<RemoteFile> {
        if (folderUrl !in dirs) throw WebDavException("PROPFIND: HTTP 404")
        fun name(url: String) = URLDecoder.decode(url.removePrefix(folderUrl).trimEnd('/'), "UTF-8")
        return files.filter { it.key.startsWith(folderUrl) && '/' !in it.key.removePrefix(folderUrl) }.map { RemoteFile(name(it.key), it.value.second, 0, false) } +
            dirs.filter { it != folderUrl && it.startsWith(folderUrl) && '/' !in it.removePrefix(folderUrl).trimEnd('/') }.map { RemoteFile(name(it), null, 0, true) }
    }
    override fun get(url: String): String = files[url]?.first ?: throw WebDavException("GET: HTTP 404")
    override fun getOrNull(url: String): String? = files[url]?.first
    /** Every URL the sync asked to write, delete or create (not what the tests put there themselves). */
    val changed = ArrayList<String>()
    override fun put(url: String, text: String, ifMatch: String?): String? { changed += url; file(url, text); return files[url]!!.second }
    override fun etagOf(url: String): String? = files[url]?.second
    override fun delete(url: String) { changed += url; remove(url) }
    override fun mkcol(url: String) { changed += url; dirs += url }
    /** A file or a folder taken away by someone else. */
    fun remove(url: String) { files.remove(url); if (dirs.remove(url)) files.keys.removeAll { it.startsWith(url) } }
    override fun exists(url: String): Boolean { reach(url); return url in dirs || url in files }
}

class SyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dav = FakeDav()
    private val a = Settings(server = "https://a.example", folder = "Notes", username = "u", password = "p")
    private val b = a.copy(server = "https://b.example")

    private fun store(dir: File = tmp.newFolder()) = NotesStore(dir)
    private fun sync(store: NotesStore, settings: Settings) = Sync.run(store, settings, " (server copy)", dav)
    private fun NotesStore.write(text: String, folder: String = ""): String { Thread.sleep(2); return create(text, folder) }
    private fun NotesStore.texts() = live().map { text(it.id) }.toSet()

    @Test fun firstSyncUploadsAndASecondOneHasNothingToDo() {
        val store = store()
        store.write("shopping\nbread"); store.write("ideas")
        assertEquals(2, sync(store, a).uploaded)
        assertEquals(setOf("shopping.txt", "ideas.txt"), dav.names(a.folderUrl))
        val again = sync(store, a)
        assertEquals(0, again.uploaded + again.downloaded + again.deleted)
    }

    @Test fun aNoteDeletedOnTheServerIsDeletedHere() {
        val store = store()
        store.write("shopping\nbread"); store.write("ideas")
        sync(store, a)
        dav.delete(a.folderUrl + "ideas.txt")
        assertEquals(1, sync(store, a.copy(server = "https://a.example/")).deleted)   // the same place, written with its last slash
        assertEquals(setOf("shopping\nbread"), store.texts())
    }

    // The loss this guards against: notes synced with one place, absent from the next one, were taken for deleted there.

    @Test fun anotherServerEmptyKeepsEveryNoteAndReceivesThem() {
        val store = store()
        store.write("shopping\nbread"); store.write("ideas"); store.write("letter\ndear all")
        sync(store, a)
        val r = sync(store, b)
        assertEquals(setOf("shopping\nbread", "ideas", "letter\ndear all"), store.texts())
        assertEquals(0, r.deleted); assertEquals(3, r.uploaded)
        assertEquals(setOf("shopping.txt", "ideas.txt", "letter.txt"), dav.names(b.folderUrl))
        assertEquals(setOf("shopping.txt", "ideas.txt", "letter.txt"), dav.names(a.folderUrl))   // the first place is left as it was
        val again = sync(store, b)
        assertEquals(0, again.uploaded + again.downloaded + again.deleted)
    }

    @Test fun anotherFolderHoldingOtherNotesKeepsBoth() {
        val store = store()
        store.write("shopping\nbread"); store.write("ideas")
        sync(store, a)
        val other = a.copy(folder = "Work/Notes")
        dav.mkcol(other.folderUrl)
        dav.file(other.folderUrl + "meeting.txt", "meeting\nmonday")
        dav.file(other.folderUrl + "ideas.txt", "ideas\nof someone else")
        sync(store, other)
        assertEquals(setOf("shopping\nbread", "ideas", "meeting\nmonday", "ideas\nof someone else"), store.texts())
        assertEquals(setOf("shopping.txt", "ideas.txt", "ideas (2).txt", "meeting.txt"), dav.names(other.folderUrl))
        assertEquals("ideas\nof someone else", dav.get(other.folderUrl + "ideas.txt"))
    }

    @Test fun anotherServerKeepsFoldersAndTheirNotes() {
        val store = store()
        val folders = a.copy(useFolders = true)
        store.addFolder("work"); store.addFolder("empty")
        store.write("report", "work"); store.write("ideas")
        sync(store, folders)
        assertEquals(setOf("work/report.txt", "ideas.txt"), dav.names(a.folderUrl))
        sync(store, b.copy(useFolders = true))
        assertEquals(setOf("report", "ideas"), store.texts())
        assertEquals(setOf("empty", "work"), store.folders().map { it.name }.toSet())
        assertEquals("work", store.live().first { store.text(it.id) == "report" }.folder)
        assertEquals(setOf("work/report.txt", "ideas.txt"), dav.names(b.folderUrl))
    }

    @Test fun backToTheFirstPlaceKeepsEveryNoteOnce() {
        val store = store()
        store.write("shopping\nbread")
        sync(store, a)
        store.write("ideas")
        sync(store, b)
        dav.file(a.folderUrl + "shopping.txt", "shopping\nbread")   // untouched but for its etag
        val r = sync(store, a)
        assertEquals(1, r.uploaded); assertEquals(0, r.downloaded)
        assertEquals(listOf("ideas", "shopping\nbread"), store.live().map { store.text(it.id) }.sorted())
        assertEquals(setOf("shopping.txt", "ideas.txt"), dav.names(a.folderUrl))
        val again = sync(store, a)
        assertEquals(0, again.uploaded + again.downloaded + again.deleted)
    }

    @Test fun backToTheFirstPlaceWhereANoteChangedKeepsBothTexts() {
        val store = store()
        store.write("shopping\nbread")
        sync(store, a)
        sync(store, b)
        dav.file(a.folderUrl + "shopping.txt", "shopping\nbread and milk")
        sync(store, a)
        assertEquals(setOf("shopping\nbread", "shopping\nbread and milk"), store.texts())
        assertEquals(setOf("shopping.txt", "shopping (2).txt"), dav.names(a.folderUrl))
    }

    @Test fun aServerThatDoesNotAnswerChangesNothing() {
        val store = store()
        store.write("shopping\nbread"); store.write("ideas")
        sync(store, a)
        dav.down += "https://typo.example"
        assertTrue(runCatching { sync(store, a.copy(server = "https://typo.example")) }.isFailure)
        assertEquals(setOf("shopping\nbread", "ideas"), store.texts())
        val r = sync(store, a)
        assertEquals(0, r.uploaded + r.downloaded + r.deleted)
    }

    @Test fun twoNotesAlikeNeverSyncedStayTwo() {
        val store = store()
        store.write("ideas"); store.write("ideas")
        dav.mkcol(a.folderUrl)
        dav.file(a.folderUrl + "ideas.txt", "ideas")
        sync(store, a)
        assertEquals(2, store.live().size)
        assertEquals(setOf("ideas.txt", "ideas (2).txt"), dav.names(a.folderUrl))
    }

    @Test fun whatWasDeletedHereIsNotDeletedInAnotherPlace() {
        val store = store()
        val id = store.write("shopping\nbread")
        sync(store, a)
        store.delete(id)
        dav.mkcol(b.folderUrl)
        dav.file(b.folderUrl + "shopping.txt", "shopping\nmilk")
        assertEquals(0, sync(store, b).deleted)
        assertEquals(setOf("shopping.txt"), dav.names(b.folderUrl))
        assertEquals(setOf("shopping\nmilk"), store.texts())
    }

    /** Notes synced by a version that did not remember the place: they belong to the place set when the app starts. */
    @Test fun notesSyncedBeforeThePlaceWasRememberedAreNotSentAgain() {
        val dir = tmp.newFolder()
        File(dir, "notes").mkdirs()
        File(dir, "notes/n1.txt").writeText("shopping\nbread")
        File(dir, "notes.json").writeText("""{"notes": [{"id": "n1", "modified": 1, "remoteName": "shopping.txt", "etag": "e1", "dirty": false}]}""")
        dav.mkcol(a.folderUrl)
        dav.file(a.folderUrl + "shopping.txt", "shopping\nbread")
        val store = store(dir)
        val r = sync(store, a)
        assertEquals(0, r.uploaded + r.downloaded + r.deleted)
        sync(store, b)
        assertEquals(setOf("shopping\nbread"), store.texts())
    }

    // Book notes: the subfolder Reader's Books writes in is copied here and never written to.

    private val booksUrl = a.folderUrl + "Reader%27s%20Books/"
    private fun NotesStore.books() = live().filter { it.isBook }
    /** The server as Reader's Books leaves it: its subfolder, one note per book. */
    private fun bookOnServer(name: String, text: String) { dav.dirs += a.folderUrl; dav.dirs += booksUrl; dav.file(bookUrl(name), text) }
    private fun bookUrl(name: String) = booksUrl + encodeSegment(name)
    private fun nothingAskedOfTheBooksFolder() = assertEquals(emptyList<String>(), dav.changed.filter { it.startsWith(booksUrl) })

    @Test fun aBookNoteIsPulledWithFoldersOffAndFoldersStayUnused() {
        val store = store()
        store.write("shopping\nbread")
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nthe trees rang when the wind came")
        val r = sync(store, a)
        assertEquals(1, r.downloaded); assertEquals(1, r.uploaded)
        assertEquals(listOf("The Glass Orchard\n\nthe trees rang when the wind came"), store.books().map { store.text(it.id) })
        assertFalse(store.books().single().dirty)
        assertFalse(store.usesFolders())
        assertEquals(emptyList<String>(), store.folders().map { it.name })
        assertEquals(setOf("shopping.txt", "Reader's Books/The Glass Orchard.txt"), dav.names(a.folderUrl))
        val again = sync(store, a)
        assertEquals(0, again.uploaded + again.downloaded + again.deleted)
        nothingAskedOfTheBooksFolder()
    }

    @Test fun aBookNoteChangedThereIsUpdatedHere() {
        val store = store()
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        sync(store, a)
        val id = store.books().single().id
        dav.file(bookUrl("The Glass Orchard.txt"), "The Glass Orchard\n\nfirst highlight\n\nsecond highlight")
        assertEquals(1, sync(store, a).downloaded)
        assertEquals(id, store.books().single().id)
        assertEquals("The Glass Orchard\n\nfirst highlight\n\nsecond highlight", store.text(id))
        nothingAskedOfTheBooksFolder()
    }

    @Test fun aBookNoteRemovedThereIsRemovedHere() {
        val store = store()
        store.write("ideas")
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        bookOnServer("Slow Rivers.md", "Slow Rivers\n\na bend, then another")
        sync(store, a)
        assertEquals(2, store.books().size)
        dav.remove(bookUrl("Slow Rivers.md"))
        assertEquals(1, sync(store, a).deleted)
        assertEquals(setOf("ideas", "The Glass Orchard\n\nfirst highlight"), store.texts())
        dav.remove(booksUrl)   // the whole subfolder
        assertEquals(1, sync(store, a).deleted)
        assertEquals(setOf("ideas"), store.texts())
        assertFalse(booksUrl in dav.dirs)   // and it is not made again
        nothingAskedOfTheBooksFolder()
    }

    @Test fun nothingDoneHereReachesTheBooksFolder() {
        val store = store()
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        sync(store, a)
        val id = store.books().single().id
        store.save(id, "The Glass Orchard\n\nrewritten here"); store.delete(id); store.move(id, "")
        assertEquals("The Glass Orchard\n\nfirst highlight", store.text(id))
        assertEquals(listOf(id), store.books().map { it.id })
        val mine = store.write("mine")
        store.move(mine, NotesStore.BOOKS_FOLDER)
        assertEquals("", store.get(mine)!!.folder)
        assertEquals("", store.get(store.write("another", NotesStore.BOOKS_FOLDER))!!.folder)
        val r = sync(store, a)
        assertEquals(2, r.uploaded); assertEquals(0, r.downloaded + r.deleted)
        assertEquals(setOf("mine.txt", "another.txt", "Reader's Books/The Glass Orchard.txt"), dav.names(a.folderUrl))
        assertEquals("The Glass Orchard\n\nfirst highlight", dav.get(bookUrl("The Glass Orchard.txt")))
        nothingAskedOfTheBooksFolder()
    }

    @Test fun withFoldersOnTheBooksFolderIsNotOneOfTheFolders() {
        val store = store()
        val folders = a.copy(useFolders = true)
        store.addFolder("work"); store.write("report", "work")
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        dav.mkcol(a.folderUrl + "travel/"); dav.file(a.folderUrl + "travel/packing.txt", "packing\nsocks")
        sync(store, folders)
        assertEquals(listOf("travel", "work"), store.folders().map { it.name })
        assertEquals(emptyList<String>(), store.goneFolders())
        assertEquals(1, store.books().size)
        assertNull(store.addFolder("Reader's Books")); assertNull(store.addFolder("reader's books"))
        assertNull(store.renameFolder("work", "Reader's Books"))
        store.deleteFolder(NotesStore.BOOKS_FOLDER)
        sync(store, folders)
        assertEquals(1, store.books().size)
        assertEquals(setOf("work/report.txt", "travel/packing.txt", "Reader's Books/The Glass Orchard.txt"), dav.names(a.folderUrl))
        nothingAskedOfTheBooksFolder()
    }

    @Test fun anotherPlaceWithoutBooksDropsTheBookNotesAndSendsNoneThere() {
        val store = store()
        store.write("ideas")
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        sync(store, a)
        sync(store, b)
        assertEquals(setOf("ideas"), store.texts())
        assertEquals(setOf("ideas.txt"), dav.names(b.folderUrl))
        assertFalse(store.usesFolders())
    }

    /** An index from a version where that subfolder was a folder like the others, a note of it rewritten and another deleted here. */
    @Test fun aBooksFolderOnceTakenForAFolderIsLeftAsItIsThere() {
        val dir = tmp.newFolder()
        File(dir, "notes").mkdirs()
        File(dir, "notes/n1.txt").writeText("The Glass Orchard\n\nrewritten here")
        File(dir, "notes/n2.txt").writeText("Slow Rivers\n\na bend, then another")
        File(dir, "notes/n3.txt").writeText("written here")
        File(dir, "notes/n4.txt").writeText("Paper Boats\n\nmoved out here")
        File(dir, "notes.json").writeText("""{"notes": [
            {"id": "n1", "modified": 1, "remoteName": "The Glass Orchard.txt", "etag": "e1", "dirty": true, "folder": "Reader's Books", "remoteFolder": "Reader's Books"},
            {"id": "n2", "modified": 2, "remoteName": "Slow Rivers.txt", "etag": "e2", "dirty": false, "deleted": true, "folder": "Reader's Books", "remoteFolder": "Reader's Books"},
            {"id": "n3", "modified": 3, "folder": "Reader's Books"},
            {"id": "n4", "modified": 4, "remoteName": "Paper Boats.txt", "etag": "e3", "dirty": true, "folder": "", "remoteFolder": "Reader's Books"}],
            "folders": [{"name": "Reader's Books", "onServer": true}], "goneFolders": ["Reader's Books"], "place": "${a.folderUrl}"}""")
        bookOnServer("The Glass Orchard.txt", "The Glass Orchard\n\nfirst highlight")
        bookOnServer("Slow Rivers.txt", "Slow Rivers\n\na bend, then another")
        bookOnServer("Paper Boats.txt", "Paper Boats\n\nfolded twice")
        val store = store(dir)
        assertEquals(emptyList<String>(), store.folders().map { it.name })
        sync(store, a.copy(useFolders = true))
        assertEquals(setOf("The Glass Orchard\n\nfirst highlight", "Slow Rivers\n\na bend, then another", "Paper Boats\n\nfolded twice"), store.books().map { store.text(it.id) }.toSet())
        assertEquals(setOf("written here", "Paper Boats\n\nmoved out here"), store.live().filter { !it.isBook }.map { store.text(it.id) }.toSet())
        assertEquals(setOf("written here.txt", "Paper Boats.txt", "Reader's Books/The Glass Orchard.txt", "Reader's Books/Slow Rivers.txt", "Reader's Books/Paper Boats.txt"), dav.names(a.folderUrl))
        assertEquals(emptyList<String>(), store.folders().map { it.name })
        nothingAskedOfTheBooksFolder()
    }
}
