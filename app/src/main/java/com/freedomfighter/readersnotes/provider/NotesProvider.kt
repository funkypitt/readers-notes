package com.freedomfighter.readersnotes.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.freedomfighter.readersnotes.App

/**
 * The notes for the launcher's "notes" tile (signature-protected): content://…/notes lists
 * them newest first with title and preview; content://…/notes/<id> opened with ACTION_VIEW
 * shows that note.
 */
class NotesProvider : ContentProvider() {
    private val app get() = context!!.applicationContext as App
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        if (MATCHER.match(uri) != NOTES) throw IllegalArgumentException("unknown uri $uri")
        val store = app.store
        return MatrixCursor(arrayOf("_id", "id", "title", "preview", "modified")).apply {
            store.live().forEachIndexed { i, n -> addRow(arrayOf(i, n.id, store.title(n.id), store.preview(n.id), n.modified)) }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.readersnotes.note"
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // a new note from outside (the launcher's tile "+"): text, or empty
        if (MATCHER.match(uri) != NOTES) return null
        val id = app.store.create(values?.getAsString("text").orEmpty())
        return Uri.parse("content://$AUTHORITY/notes/$id")
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.freedomfighter.readersnotes"
        private const val NOTES = 1
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "notes", NOTES) }
    }
}
