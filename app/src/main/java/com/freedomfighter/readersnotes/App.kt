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
    lateinit var prefs: Prefs
    lateinit var store: NotesStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    /** One line for the status row: "syncing…", "synced 21:03", or the error. */
    val status = MutableStateFlow("")
    val syncing = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = NotesStore(this)
    }

    fun sync() {
        val s = prefs.settings.value
        if (!s.configured) { status.value = ""; return }
        scope.launch {
            if (syncLock.isLocked) return@launch
            syncLock.withLock {
                syncing.value = true; status.value = "syncing…"
                status.value = try {
                    val r = Sync.run(store, s)
                    "synced " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + listOf(r.uploaded to "↑", r.downloaded to "↓", r.deleted to "−").filter { it.first > 0 }.joinToString("") { " ${it.first}${it.second}" }
                } catch (e: Exception) { e.message ?: "sync failed" }
                syncing.value = false
            }
        }
    }
}
