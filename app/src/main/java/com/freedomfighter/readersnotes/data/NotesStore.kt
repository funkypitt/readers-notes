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
)

/** A folder; [onServer]: seen on the server or created there, so its absence there means it was deleted there. */
@Serializable
data class NoteFolder(val name: String, val onServer: Boolean = false)

@Serializable
private data class Index(
    val notes: List<Note> = emptyList(),
    val folders: List<NoteFolder> = emptyList(),
    /** Folders deleted or renamed here, to remove from the server once they are empty there. */
    val goneFolders: List<String> = emptyList()
)

class NotesStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "notes.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private var index = loadIndex()
    private val _notes = MutableStateFlow(index.notes)
    /** Live notes (no tombstones), newest first. */
    val notes: StateFlow<List<Note>> = _notes
    private val _folders = MutableStateFlow(index.folders)
    val folderList: StateFlow<List<NoteFolder>> = _folders

    private fun loadIndex(): Index = runCatching { json.decodeFromString<Index>(indexFile.readText()) }.getOrDefault(Index())
    private val changeUri = android.net.Uri.parse("content://com.freedomfighter.readersnotes/notes")
    private val resolver = context.contentResolver
    private val appContext = context.applicationContext
    private fun saveIndex(all: List<Note>, folders: List<NoteFolder> = index.folders, gone: List<String> = index.goneFolders) {
        index = Index(all, folders, gone)
        val tmp = File(indexFile.parentFile, "notes.json.tmp")
        tmp.writeText(json.encodeToString(index)); if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        _notes.value = all; _folders.value = folders
        resolver.notifyChange(changeUri, null); runCatching { com.freedomfighter.readersnotes.widget.NotesWidgets.refresh(appContext) }
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
        saveIndex(all() + Note(id = id, modified = System.currentTimeMillis(), folder = folder))
        id
    }

    // ---- folders ----------------------------------------------------------------------

    fun folders(): List<NoteFolder> = synchronized(lock) { index.folders.sortedBy { it.name.lowercase() } }
    fun goneFolders(): List<String> = synchronized(lock) { index.goneFolders }
    fun count(folder: String?): Int = live().count { folder == null || it.folder == folder }

    /** Returns the name kept (made safe for a file system), or null when empty or taken. */
    fun addFolder(name: String): String? = synchronized(lock) {
        val n = folderNameOf(name) ?: return null
        if (index.folders.any { it.name.equals(n, ignoreCase = true) }) return null
        saveIndex(all(), index.folders + NoteFolder(n), index.goneFolders - n)
        n
    }

    /** A note to another folder ("" = none): it goes up again under its new path at the next sync. */
    fun move(id: String, folder: String) = synchronized(lock) {
        saveIndex(all().map { if (it.id == id && it.folder != folder) it.copy(folder = folder, dirty = true) else it })
    }

    /** Its notes follow; the old one leaves the server once emptied there. */
    fun renameFolder(old: String, name: String): String? = synchronized(lock) {
        val n = folderNameOf(name) ?: return null
        if (n == old) return n
        if (index.folders.any { it.name.equals(n, ignoreCase = true) && it.name != old }) return null
        val gone = if (index.folders.any { it.name == old && it.onServer }) index.goneFolders + old else index.goneFolders
        saveIndex(all().map { if (it.folder == old) it.copy(folder = n, dirty = true) else it },
            index.folders.filter { it.name != old } + NoteFolder(n), gone - n)
        n
    }

    /** The folder goes; its notes stay, in "all notes" only. */
    fun deleteFolder(name: String) = synchronized(lock) {
        val gone = if (index.folders.any { it.name == name && it.onServer }) index.goneFolders + name else index.goneFolders
        saveIndex(all().map { if (it.folder == name) it.copy(folder = "", dirty = true) else it }, index.folders.filter { it.name != name }, gone)
    }

    // folder side of the sync
    fun folderOnServer(name: String) = synchronized(lock) {
        val has = index.folders.any { it.name == name }
        saveIndex(all(), if (has) index.folders.map { if (it.name == name) it.copy(onServer = true) else it } else index.folders + NoteFolder(name, true))
    }
    fun folderGoneThere(name: String) = synchronized(lock) { saveIndex(all(), index.folders.filter { it.name != name }) }
    fun folderRemovedThere(name: String) = synchronized(lock) { saveIndex(all(), index.folders, index.goneFolders - name) }
    /** Anything placed in a folder, here or there: the subfolders are then synced whatever the setting says. */
    fun usesFolders(): Boolean = synchronized(lock) { index.folders.isNotEmpty() || index.goneFolders.isNotEmpty() || index.notes.any { it.folder.isNotEmpty() || it.remoteFolder.isNotEmpty() } }

    /** Called on every keystroke (cheap: one small file). */
    fun save(id: String, text: String) = synchronized(lock) {
        if (file(id).exists() && file(id).readText() == text) return@synchronized
        file(id).writeText(text)
        saveIndex(all().map { if (it.id == id) it.copy(modified = System.currentTimeMillis(), dirty = true) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        val n = get(id) ?: return@synchronized
        if (n.remoteName == null) { file(id).delete(); saveIndex(all().filter { it.id != id }) }
        else saveIndex(all().map { if (it.id == id) it.copy(deleted = true, modified = System.currentTimeMillis()) else it })
    }

    // ---- sync side --------------------------------------------------------------------

    fun purge(id: String) = synchronized(lock) { file(id).delete(); saveIndex(all().filter { it.id != id }) }
    fun markSynced(id: String, remoteName: String, etag: String?, remoteFolder: String = "") = synchronized(lock) {
        saveIndex(all().map { if (it.id == id) it.copy(remoteName = remoteName, remoteFolder = remoteFolder, etag = etag, dirty = false) else it })
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
