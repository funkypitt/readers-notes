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
                    Screen.Notes -> NotesScreen(nav, app)
                    is Screen.Edit -> EditScreen(nav, app, s.id)
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
    }

    override fun onPause() {
        super.onPause()
        val app = application as App
        if (app.store.all().any { it.dirty || it.deleted }) app.sync()
    }

    /** Shared text becomes a new note. */
    private fun handle(intent: Intent?) {
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
