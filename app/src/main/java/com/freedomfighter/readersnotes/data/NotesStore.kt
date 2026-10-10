package com.freedomfighter.readersnotes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One note = one plain-text file. The first line is the title. The index remembers, for each
 * note, the name and etag it has on the server, whether it changed since, and whether it
 * was deleted here (a tombstone, until the deletion reaches the server).
 *
 * Folders (optional, 1.6.0): [folder] is the note's folder here ("" = none), [remoteFolder] the
 * one it sits in on the server; a folder is a subfolder of the synced folder, one level deep.
 *
 * Book notes (1.8.0): the notes in [NotesStore.BOOKS_FOLDER] are written by Reader's Books on the
 * server and only read here. That subfolder is not one of the reader's folders: it is never in the
 * folder list, and its notes are never changed, moved or deleted from here.
 */
@Serializable
data class Note(
    val id: String,
    val modified: Long,
    val remoteName: String? = null,
    val etag: String? = null,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
    val folder: String = "",
    val remoteFolder: String = ""
) {
    /** A book's highlights, written by Reader's Books: read here, never changed. */
    val isBook: Boolean get() = folder == NotesStore.BOOKS_FOLDER
}

/** A folder; [onServer]: seen on the server or created there, so its absence there means it was deleted there. */
@Serializable
data class NoteFolder(val name: String, val onServer: Boolean = false)

@Serializable
private data class Index(
    val notes: List<Note> = emptyList(),
    val folders: List<NoteFolder> = emptyList(),
    /** Folders deleted or renamed here, to remove from the server once they are empty there. */
    val goneFolders: List<String> = emptyList(),
    /** The folder URL the notes were last synced with; null before any sync. */
    val place: String? = null
)

/** [changed] is called after every change of the index: the provider's observers and the widgets hear of it there. */
class NotesStore(filesDir: File, private val changed: () -> Unit = {}) {
    constructor(context: Context) : this(context.filesDir, notifier(context.applicationContext))

    private val dir = File(filesDir, "notes").apply { mkdirs() }
    private val indexFile = File(filesDir, "notes.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private var index = loadIndex()
    private val _notes = MutableStateFlow(index.notes)
    /** Live notes (no tombstones), newest first. */
    val notes: StateFlow<List<Note>> = _notes
    private val _folders = MutableStateFlow(index.folders)
    val folderList: StateFlow<List<NoteFolder>> = _folders

    private fun loadIndex(): Index = runCatching { json.decodeFromString<Index>(indexFile.readText()) }.getOrDefault(Index()).let { booksApart(it) }
    /**
     * An index written when the books' subfolder was a folder like the others: it leaves the folder
     * lists, and only what came from it stays in it. A note written or moved into it here becomes a
     * note without folder; one moved out of it here is a new note, its file there left alone.
     */
    private fun booksApart(i: Index): Index = i.copy(
        notes = i.notes.map {
            if (it.folder == BOOKS_FOLDER && (it.remoteName == null || it.remoteFolder != BOOKS_FOLDER)) it.copy(folder = "", dirty = true)
            else if (it.folder != BOOKS_FOLDER && it.remoteFolder == BOOKS_FOLDER) it.copy(remoteName = null, remoteFolder = "", etag = null, dirty = true)
            else it
        },
        folders = i.folders.filter { it.name != BOOKS_FOLDER }, goneFolders = i.goneFolders - BOOKS_FOLDER)
    private fun saveIndex(all: List<Note>, folders: List<NoteFolder> = index.folders, gone: List<String> = index.goneFolders, place: String? = index.place) {
        index = Index(all, folders, gone, place)
        val tmp = File(indexFile.parentFile, "notes.json.tmp")
        tmp.writeText(json.encodeToString(index)); if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        _notes.value = all; _folders.value = folders
        changed()
    }

    private fun file(id: String) = File(dir, "$id.txt")
    fun text(id: String): String = runCatching { file(id).readText() }.getOrDefault("")
    fun all(): List<Note> = synchronized(lock) { index.notes }
    fun live(): List<Note> = all().filter { !it.deleted }.sortedByDescending { it.modified }
    fun get(id: String): Note? = all().firstOrNull { it.id == id }

    fun title(id: String): String = titleOf(text(id))
    fun preview(id: String): String = text(id).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).firstOrNull() ?: ""

    fun create(text: String = "", folder: String = ""): String = synchronized(lock) {
        val id = System.currentTimeMillis().toString(36) + (1000..9999).random()
        file(id).writeText(text)
        // nothing is written in the books' folder from here
        saveIndex(all() + Note(id = id, modified = System.currentTimeMillis(), folder = if (folder == BOOKS_FOLDER) "" else folder))
        id
    }

    // ---- folders ----------------------------------------------------------------------

    fun folders(): List<NoteFolder> = synchronized(lock) { index.folders.sortedBy { it.name.lowercase() } }
    fun goneFolders(): List<String> = synchronized(lock) { index.goneFolders }
    fun count(folder: String?): Int = live().count { folder == null || it.folder == folder }

    /** Returns the name kept (made safe for a file system), or null when empty or taken. */
    fun addFolder(name: String): String? = synchronized(lock) {
        val n = folderNameOf(name) ?: return null
        if (n.equals(BOOKS_FOLDER, ignoreCase = true)) return null
        if (index.folders.any { it.name.equals(n, ignoreCase = true) }) return null
        saveIndex(all(), index.folders + NoteFolder(n), index.goneFolders - n)
        n
    }

    /** A note to another folder ("" = none): it goes up again under its new path at the next sync. A book note stays where it is, and nothing joins it. */
    fun move(id: String, folder: String) = synchronized(lock) {
        if (folder == BOOKS_FOLDER || get(id)?.isBook == true) return@synchronized
        saveIndex(all().map { if (it.id == id && it.folder != folder) it.copy(folder = folder, dirty = true) else it })
    }

    /** Its notes follow; the old one leaves the server once emptied there. */
    fun renameFolder(old: String, name: String): String? = synchronized(lock) {
        val n = folderNameOf(name) ?: return null
        if (n == old) return n
        if (old == BOOKS_FOLDER || n.equals(BOOKS_FOLDER, ignoreCase = true)) return null
        if (index.folders.any { it.name.equals(n, ignoreCase = true) && it.name != old }) return null
        val gone = if (index.folders.any { it.name == old && it.onServer }) index.goneFolders + old else index.goneFolders
        saveIndex(all().map { if (it.folder == old) it.copy(folder = n, dirty = true) else it },
            index.folders.filter { it.name != old } + NoteFolder(n), gone - n)
        n
    }

    /** The folder goes; its notes stay, in "all notes" only. */
    fun deleteFolder(name: String) = synchronized(lock) {
        if (name == BOOKS_FOLDER) return@synchronized
        val gone = if (index.folders.any { it.name == name && it.onServer }) index.goneFolders + name else index.goneFolders
        saveIndex(all().map { if (it.folder == name) it.copy(folder = "", dirty = true) else it }, index.folders.filter { it.name != name }, gone)
    }

    // folder side of the sync
    fun folderOnServer(name: String) = synchronized(lock) {
        if (name == BOOKS_FOLDER) return@synchronized
        val has = index.folders.any { it.name == name }
        saveIndex(all(), if (has) index.folders.map { if (it.name == name) it.copy(onServer = true) else it } else index.folders + NoteFolder(name, true))
    }
    fun folderGoneThere(name: String) = synchronized(lock) { saveIndex(all(), index.folders.filter { it.name != name }) }
    fun folderRemovedThere(name: String) = synchronized(lock) { saveIndex(all(), index.folders, index.goneFolders - name) }
    /** Anything placed in a folder, here or there: the subfolders are then synced whatever the setting says. The book notes do not count. */
    fun usesFolders(): Boolean = synchronized(lock) { index.folders.isNotEmpty() || index.goneFolders.isNotEmpty() || index.notes.any { !it.isBook && (it.folder.isNotEmpty() || it.remoteFolder.isNotEmpty()) } }

    /** Called on every keystroke (cheap: one small file). */
    fun save(id: String, text: String) = synchronized(lock) {
        if (get(id)?.isBook == true) return@synchronized
        if (file(id).exists() && file(id).readText() == text) return@synchronized
        file(id).writeText(text)
        saveIndex(all().map { if (it.id == id) it.copy(modified = System.currentTimeMillis(), dirty = true) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        val n = get(id) ?: return@synchronized
        if (n.isBook) return@synchronized
        if (n.remoteName == null) { file(id).delete(); saveIndex(all().filter { it.id != id }) }
        else saveIndex(all().map { if (it.id == id) it.copy(deleted = true, modified = System.currentTimeMillis()) else it })
    }

    // ---- sync side --------------------------------------------------------------------

    fun purge(id: String) = synchronized(lock) { file(id).delete(); saveIndex(all().filter { it.id != id }) }
    fun markSynced(id: String, remoteName: String, etag: String?, remoteFolder: String = "") = synchronized(lock) {
        saveIndex(all().map { if (it.id == id) it.copy(remoteName = remoteName, remoteFolder = remoteFolder, etag = etag, dirty = false) else it })
    }
    /**
     * The sync is about to run with [place] (the folder URL). Another server or folder than last
     * time: what is remembered of the old one says nothing about this one, and a note missing there
     * was not deleted there. So every note goes up again as new, and none is removed from here.
     */
    fun syncingWith(place: String) = synchronized(lock) {
        if (index.place == place) return@synchronized
        if (index.place != null) forgetServer()
        saveIndex(all(), place = place)
    }
    fun place(): String? = synchronized(lock) { index.place }
    private fun forgetServer() {
        // the book notes are a copy of what the old place held: they go, and come from the new one if it has any
        all().filter { it.deleted || it.isBook }.forEach { file(it.id).delete() }
        saveIndex(all().filter { !it.deleted && !it.isBook }.map { it.copy(remoteName = null, remoteFolder = "", etag = null, dirty = true) },
            index.folders.map { it.copy(onServer = false) }, emptyList())
    }
    /** A file from the server, new here or changed there. */
    fun applyRemote(id: String?, remoteName: String, etag: String?, text: String, modified: Long, folder: String = ""): String = synchronized(lock) {
        val nid = id ?: (System.currentTimeMillis().toString(36) + (1000..9999).random())
        file(nid).writeText(text)
        val note = Note(id = nid, modified = modified, remoteName = remoteName, etag = etag, dirty = false, folder = folder, remoteFolder = folder)
        saveIndex(if (id == null) all() + note else all().map { if (it.id == id) note else it })
        nid
    }

    companion object {
        /** The subfolder of the synced folder where Reader's Books writes its notes, one per book. */
        const val BOOKS_FOLDER = "Reader's Books"
        private fun notifier(appContext: Context): () -> Unit {
            val changeUri = android.net.Uri.parse("content://com.freedomfighter.readersnotes/notes")
            val resolver = appContext.contentResolver
            return { resolver.notifyChange(changeUri, null); runCatching { com.freedomfighter.readersnotes.widget.NotesWidgets.refresh(appContext) } }
        }
        fun titleOf(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
        /** A folder name the server and a desktop will both accept; null when nothing is left. */
        fun folderNameOf(name: String): String? =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').trimStart('.').takeIf { it.isNotEmpty() }?.take(60)
        /** A file name the server and a desktop will both accept. */
        fun fileNameOf(text: String): String {
            val base = titleOf(text).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "untitled" }
            return "$base.txt"
        }
    }
}
