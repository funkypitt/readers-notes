package com.freedomfighter.readersnotes.sync

import com.freedomfighter.readersnotes.data.NotesStore
import com.freedomfighter.readersnotes.data.Settings
import com.freedomfighter.readersnotes.data.encodeSegment

/**
 * Two-way sync of a folder of .txt files. Etags decide who moved: a note changed here and
 * untouched there is uploaded; changed there and untouched here is downloaded; changed on
 * both sides keeps the server's text as a second note ("… (server copy)") and uploads ours.
 * Deleted here → deleted there unless it changed there since; deleted there → deleted here
 * unless it changed here since. Anything new on either side crosses over.
 */
object Sync {
    class Result(val uploaded: Int, val downloaded: Int, val deleted: Int)

    private fun isNote(name: String) = name.endsWith(".txt", true) || name.endsWith(".md", true)

    fun run(store: NotesStore, settings: Settings): Result {
        val dav = WebDav(settings.username, settings.password)
        val folder = settings.folderUrl
        if (!dav.exists(folder)) dav.mkcol(folder)
        val remote = dav.list(folder).filter { !it.isDir && isNote(it.name) }.associateBy { it.name }
        var up = 0; var down = 0; var del = 0
        val taken = HashSet<String>()
        val gone = HashSet<String>()   // deleted on the server during this run

        for (note in store.all()) {
            val r = note.remoteName?.let { remote[it] }
            if (note.deleted) {
                if (r != null && (note.etag == null || r.etag == note.etag)) { dav.delete(folder + encodeSegment(note.remoteName!!)); gone += note.remoteName; del++ }
                store.purge(note.id)   // if it changed on the server meanwhile, it comes back below as a new note
                continue
            }
            val text = store.text(note.id)
            // nothing in it yet (a dictation still being written): not worth an "untitled.txt" on the server
            if (text.isBlank() && note.remoteName == null) continue
            if (note.dirty) {
                var name = NotesStore.fileNameOf(text)
                if (name != note.remoteName) {
                    // a fresh name must not collide with another server file
                    var i = 2; val base = name.removeSuffix(".txt")
                    while ((remote.containsKey(name) && note.remoteName != name) || name in taken) { name = "$base ($i).txt"; i++ }
                }
                taken += name
                val unchangedThere = r == null || note.etag == null || r.etag == note.etag
                if (!unchangedThere) {
                    // both sides moved: keep theirs as a second note, ours takes the name
                    val theirs = dav.get(folder + encodeSegment(note.remoteName!!))
                    if (theirs.trim() != text.trim()) {
                        val copy = theirs.lines().let { l -> if (l.isEmpty()) theirs else (l.first() + " (server copy)") + "\n" + l.drop(1).joinToString("\n") }
                        store.create(copy); down++
                    }
                }
                if (note.remoteName != null && note.remoteName != name && r != null) { dav.delete(folder + encodeSegment(note.remoteName)); gone += note.remoteName }
                val url = folder + encodeSegment(name)
                val etag = dav.put(url, text) ?: dav.etagOf(url)
                store.markSynced(note.id, name, etag); up++
            } else {
                if (note.remoteName == null) continue
                if (r == null) { store.purge(note.id); del++ }
                else if (r.etag != note.etag) {
                    val theirs = dav.get(folder + encodeSegment(note.remoteName))
                    store.applyRemote(note.id, note.remoteName, r.etag, theirs, if (r.modified > 0) r.modified else System.currentTimeMillis()); down++
                }
                taken += note.remoteName
            }
        }
        val known = store.all().mapNotNull { it.remoteName }.toSet()
        for ((name, r) in remote) {
            if (name in known || name in gone) continue
            val theirs = dav.getOrNull(folder + encodeSegment(name)) ?: continue   // vanished meanwhile
            store.applyRemote(null, name, r.etag, theirs, if (r.modified > 0) r.modified else System.currentTimeMillis()); down++
        }
        return Result(up, down, del)
    }
}
