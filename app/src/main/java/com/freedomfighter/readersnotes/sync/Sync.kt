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
 *
 * Folders (1.6.0): each is a subfolder of the synced folder, one level deep. They are synced
 * when the setting is on, or as soon as anything was ever placed in a folder (so turning the
 * setting off never makes notes vanish from the server). Otherwise, as before: the synced
 * folder's own files only. A note moved to another folder goes up under its new path and the
 * old file goes. A folder deleted there disappears here with its notes' files (deleted there);
 * one deleted or renamed here leaves the server once it is empty there.
 */
object Sync {
    class Result(val uploaded: Int, val downloaded: Int, val deleted: Int)

    private fun isNote(name: String) = name.endsWith(".txt", true) || name.endsWith(".md", true)

    /** A note's place on the server: "name" in the synced folder, "folder/name" in a subfolder. */
    private fun key(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    fun run(store: NotesStore, settings: Settings): Result {
        val dav = WebDav(settings.username, settings.password)
        val root = settings.folderUrl
        if (!dav.exists(root)) dav.mkcol(root)
        fun dirUrl(folder: String) = if (folder.isEmpty()) root else root + encodeSegment(folder) + "/"
        fun url(folder: String, name: String) = dirUrl(folder) + encodeSegment(name)

        val entries = dav.list(root)
        val remote = HashMap<String, RemoteFile>()
        entries.filter { !it.isDir && isNote(it.name) }.forEach { remote[it.name] = it }
        val withFolders = settings.useFolders || store.usesFolders()
        val serverDirs = if (withFolders) entries.filter { it.isDir && !it.name.startsWith(".") }.map { it.name }.toSet() else emptySet()
        val gone = store.goneFolders().toSet()
        val present = serverDirs.toMutableSet()
        if (withFolders) {
            for (d in serverDirs) {
                dav.list(dirUrl(d)).filter { !it.isDir && isNote(it.name) }.forEach { remote[key(d, it.name)] = it }
                if (d !in gone) store.folderOnServer(d)
            }
            for (f in store.folders()) {
                if (f.name in serverDirs) continue
                // deleted there: gone here too, unless a note written here since still needs it
                if (f.onServer && store.all().none { !it.deleted && it.folder == f.name && it.dirty }) store.folderGoneThere(f.name)
                else { dav.mkcol(dirUrl(f.name)); store.folderOnServer(f.name); present += f.name }
            }
        }

        var up = 0; var down = 0; var del = 0
        val taken = HashSet<String>()
        val removed = HashSet<String>()   // deleted on the server during this run

        for (note in store.all()) {
            val here = note.remoteName?.let { key(note.remoteFolder, it) }
            val r = here?.let { remote[it] }
            if (note.deleted) {
                if (r != null && (note.etag == null || r.etag == note.etag)) { dav.delete(url(note.remoteFolder, note.remoteName!!)); removed += here; del++ }
                store.purge(note.id)   // if it changed on the server meanwhile, it comes back below as a new note
                continue
            }
            val text = store.text(note.id)
            // nothing in it yet (a dictation still being written): not worth an "untitled.txt" on the server
            if (text.isBlank() && note.remoteName == null) continue
            if (note.dirty) {
                val folder = if (withFolders) note.folder else ""
                var name = NotesStore.fileNameOf(text)
                var target = key(folder, name)
                if (target != here) {
                    // a fresh name must not collide with another server file
                    var i = 2; val base = name.removeSuffix(".txt")
                    while ((remote.containsKey(target) && here != target) || target in taken) { name = "$base ($i).txt"; target = key(folder, name); i++ }
                }
                taken += target
                val unchangedThere = r == null || note.etag == null || r.etag == note.etag
                if (!unchangedThere) {
                    // both sides moved: keep theirs as a second note, ours takes the name
                    val theirs = dav.get(url(note.remoteFolder, note.remoteName!!))
                    if (theirs.trim() != text.trim()) {
                        val copy = theirs.lines().let { l -> if (l.isEmpty()) theirs else (l.first() + " (server copy)") + "\n" + l.drop(1).joinToString("\n") }
                        store.create(copy, note.remoteFolder); down++
                    }
                }
                if (here != null && here != target && r != null) { dav.delete(url(note.remoteFolder, note.remoteName!!)); removed += here }
                if (folder.isNotEmpty() && folder !in present) { dav.mkcol(dirUrl(folder)); store.folderOnServer(folder); present += folder }
                val etag = dav.put(url(folder, name), text) ?: dav.etagOf(url(folder, name))
                store.markSynced(note.id, name, etag, folder); up++
            } else {
                if (here == null) continue
                if (r == null) { store.purge(note.id); del++ }
                else if (r.etag != note.etag) {
                    val theirs = dav.get(url(note.remoteFolder, note.remoteName!!))
                    store.applyRemote(note.id, note.remoteName, r.etag, theirs, if (r.modified > 0) r.modified else System.currentTimeMillis(), note.remoteFolder)
                    down++
                }
                taken += here
            }
        }
        val known = store.all().mapNotNull { n -> n.remoteName?.let { key(n.remoteFolder, it) } }.toSet()
        for ((k, r) in remote) {
            if (k in known || k in removed) continue
            val folder = k.substringBefore('/', "")
            if (folder in gone) continue   // a folder deleted here: its leftovers are not brought back
            val theirs = dav.getOrNull(url(folder, k.substringAfter('/'))) ?: continue   // vanished meanwhile
            store.applyRemote(null, k.substringAfter('/'), r.etag, theirs, if (r.modified > 0) r.modified else System.currentTimeMillis(), folder); down++
        }
        // Folders deleted or renamed here: removed from the server once our notes have left them.
        // One still holding someone else's files stays there, and comes back here next time.
        for (g in gone) {
            if (g in serverDirs && dav.list(dirUrl(g)).none { !it.isDir }) dav.delete(dirUrl(g))
            store.folderRemovedThere(g)
        }
        return Result(up, down, del)
    }
}
