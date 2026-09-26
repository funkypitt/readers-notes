package com.freedomfighter.readersnotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readersnotes.ui.EditScreen
import com.freedomfighter.readersnotes.ui.LocalColors
import com.freedomfighter.readersnotes.ui.Nav
import com.freedomfighter.readersnotes.ui.NotesScreen
import com.freedomfighter.readersnotes.ui.ReaderTheme
import com.freedomfighter.readersnotes.ui.Screen
import com.freedomfighter.readersnotes.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as App
        handle(intent)
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                when (val s = nav.current) {
                    Screen.Notes -> if (settings.useFolders) com.freedomfighter.readersnotes.ui.FoldersScreen(nav, app) else NotesScreen(nav, app)
                    is Screen.Folder -> androidx.compose.runtime.key(s.name, s.query) { NotesScreen(nav, app, s.name, inFolders = true, initialQuery = s.query) }
                    // keyed: the launcher can ask for another note while one is open
                    is Screen.Edit -> androidx.compose.runtime.key(s.id) { EditScreen(nav, app, s.id) }
                    Screen.Settings -> SettingsScreen(nav, app)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onResume() {
        super.onResume()
        val app = application as App
        if (app.prefs.settings.value.syncOnOpen) app.sync()
        DictateService.kick(this)   // a dictation whose words were never written, taken up again
    }

    override fun onPause() {
        super.onPause()
        val app = application as App
        if (app.store.all().any { it.dirty || it.deleted }) app.sync()
    }

    /** Shared text becomes a new note; our own provider URI (the launcher's tile) opens that note, a new one, or a new one with the microphone open. */
    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.authority == "com.freedomfighter.readersnotes") {
            val app = application as App
            val id = data.lastPathSegment
            nav.home()
            if (id == "new") nav.push(Screen.Edit(app.store.create()))
            else if (id == "dictate") nav.wantDictate = true
            else if (id != null && app.store.get(id) != null) nav.push(Screen.Edit(id))
            intent.action = null; return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val id = (application as App).store.create(if (subject.isNullOrBlank()) text else "$subject\n\n$text")
            nav.home(); nav.push(Screen.Edit(id))
            intent.action = null
        }
    }

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }
}
