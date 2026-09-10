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
 */
@Serializable
data class Note(
    val id: String,
    val modified: Long,
    val remoteName: String? = null,
    val etag: String? = null,
    val dirty: Boolean = true,
    val deleted: Boolean = false
)

@Serializable
private data class Index(val notes: List<Note> = emptyList())

class NotesStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "notes.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val _notes = MutableStateFlow(loadIndex())
    /** Live notes (no tombstones), newest first. */
    val notes: StateFlow<List<Note>> = _notes

    private fun loadIndex(): List<Note> = runCatching { json.decodeFromString<Index>(indexFile.readText()).notes }.getOrDefault(emptyList())
    private val changeUri = android.net.Uri.parse("content://com.freedomfighter.readersnotes/notes")
    private val resolver = context.contentResolver
    private val appContext = context.applicationContext
    private fun saveIndex(all: List<Note>) { indexFile.writeText(json.encodeToString(Index(all))); _notes.value = all; resolver.notifyChange(changeUri, null); runCatching { com.freedomfighter.readersnotes.widget.NotesWidgets.refresh(appContext) } }

    private fun file(id: String) = File(dir, "$id.txt")
    fun text(id: String): String = runCatching { file(id).readText() }.getOrDefault("")
    fun all(): List<Note> = synchronized(lock) { _notes.value }
    fun live(): List<Note> = all().filter { !it.deleted }.sortedByDescending { it.modified }
    fun get(id: String): Note? = all().firstOrNull { it.id == id }

    fun title(id: String): String = titleOf(text(id))
    fun preview(id: String): String = text(id).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.drop(1).firstOrNull() ?: ""

    fun create(text: String = ""): String = synchronized(lock) {
        val id = System.currentTimeMillis().toString(36) + (1000..9999).random()
        file(id).writeText(text)
        saveIndex(all() + Note(id = id, modified = System.currentTimeMillis()))
        id
    }

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
    fun markSynced(id: String, remoteName: String, etag: String?) = synchronized(lock) {
        saveIndex(all().map { if (it.id == id) it.copy(remoteName = remoteName, etag = etag, dirty = false) else it })
    }
    /** A file from the server, new here or changed there. */
    fun applyRemote(id: String?, remoteName: String, etag: String?, text: String, modified: Long): String = synchronized(lock) {
        val nid = id ?: (System.currentTimeMillis().toString(36) + (1000..9999).random())
        file(nid).writeText(text)
        val note = Note(id = nid, modified = modified, remoteName = remoteName, etag = etag, dirty = false)
        saveIndex(if (id == null) all() + note else all().map { if (it.id == id) note else it })
        nid
    }

    companion object {
        fun titleOf(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: ""
        /** A file name the server and a desktop will both accept. */
        fun fileNameOf(text: String): String {
            val base = titleOf(text).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "untitled" }
            return "$base.txt"
        }
    }
}
