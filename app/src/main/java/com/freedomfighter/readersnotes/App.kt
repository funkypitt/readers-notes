package com.freedomfighter.readersnotes

import android.app.Application
import com.freedomfighter.readersnotes.data.NotesStore
import com.freedomfighter.readersnotes.data.Prefs
import com.freedomfighter.readersnotes.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class App : Application() {
    // lazy: the content provider can be queried before Application.onCreate has run
    val prefs: Prefs by lazy { Prefs(this) }
    val store: NotesStore by lazy { NotesStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    /** One line for the status row: "syncing…", "synced 21:03", or the error. */
    val status = MutableStateFlow("")
    val syncing = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate(); prefs; store; com.freedomfighter.readersnotes.data.CredentialsShare.cleanUp(this)
        // notes synced before the store remembered where: it was with the server and folder set at this start
        prefs.settings.value.let { if (it.configured && store.place() == null) store.syncingWith(it.folderUrl) }
    }

    fun sync() {
        val s = prefs.settings.value
        if (!s.configured) { status.value = ""; return }
        scope.launch {
            if (syncLock.isLocked) return@launch
            syncLock.withLock {
                syncing.value = true; status.value = getString(R.string.sync_syncing)
                status.value = try {
                    val r = Sync.run(store, s, getString(R.string.server_copy))
                    getString(R.string.sync_synced, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) + listOf(r.uploaded to "↑", r.downloaded to "↓", r.deleted to "−").filter { it.first > 0 }.joinToString("") { " ${it.first}${it.second}" }
                } catch (e: Exception) { syncError(e) }
                syncing.value = false
            }
        }
    }

    /** The failure in the reader's language; the system's own words (English, often) only after it. */
    private fun syncError(e: Exception): String = when (e) {
        is com.freedomfighter.readersnotes.sync.WebDavAuthException -> getString(R.string.sync_wrong_login)
        is com.freedomfighter.readersnotes.sync.WebDavMethodException -> getString(R.string.sync_method_unsupported, e.method)
        else -> e.message?.let { getString(R.string.sync_failed_detail, it) } ?: getString(R.string.sync_failed)
    }
}
