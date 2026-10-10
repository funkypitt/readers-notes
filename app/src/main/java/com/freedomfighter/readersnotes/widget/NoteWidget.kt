package com.freedomfighter.readersnotes.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.NotesStore
import com.freedomfighter.readersnotes.ui.LocalColors
import com.freedomfighter.readersnotes.ui.LocalTypo
import com.freedomfighter.readersnotes.ui.Page
import com.freedomfighter.readersnotes.ui.ReaderTheme
import com.freedomfighter.readersnotes.ui.ScreenTitle
import com.freedomfighter.readersnotes.ui.Small
import com.freedomfighter.readersnotes.ui.T
import com.freedomfighter.readersnotes.ui.TextRow
import com.freedomfighter.readersnotes.ui.noRippleClickable
import com.freedomfighter.readersnotes.ui.rowPadH
import com.freedomfighter.readersnotes.ui.rowPadV
import com.freedomfighter.readersnotes.ui.whenLabel

/**
 * One note of the reader's choice, kept in view like a post-it: the title, then the text.
 * Tapping it opens the note in the editor; every keystroke there redraws the widget.
 * Which note each widget shows is kept in the "widgets" preferences.
 */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { render(context, mgr, it) } }
    override fun onDeleted(context: Context, ids: IntArray) { prefs(context).edit().apply { ids.forEach { remove(key(it)) } }.apply() }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        private fun key(widgetId: Int) = "note_$widgetId"
        fun noteOf(context: Context, widgetId: Int): String? = prefs(context).getString(key(widgetId), null)
        fun bind(context: Context, widgetId: Int, noteId: String) = prefs(context).edit().putString(key(widgetId), noteId).apply()

        /** The text without its title line (the first non-empty one), leading blank lines dropped. */
        fun bodyOf(text: String): String {
            val lines = text.lines()
            val titleAt = lines.indexOfFirst { it.isNotBlank() }
            if (titleAt < 0) return ""
            return lines.drop(titleAt + 1).dropWhile { it.isBlank() }.joinToString("\n").trimEnd()
        }

        fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_note)
            WidgetUi.paint(views, context, intArrayOf(R.id.widget_title, R.id.widget_text))
            val store = (context.applicationContext as App).store
            val id = noteOf(context, widgetId)
            val note = id?.let { store.get(it) }?.takeIf { !it.deleted }
            if (note == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_note_gone))
                views.setTextViewText(R.id.widget_text, "")
                views.setOnClickPendingIntent(R.id.widget_root, WidgetUi.activity(context, Intent(Intent.ACTION_MAIN).setClassName(context.packageName, "${context.packageName}.MainActivity"), widgetId))
            } else {
                val text = store.text(note.id)
                views.setTextViewText(R.id.widget_title, NotesStore.titleOf(text).ifBlank { context.getString(R.string.untitled) })
                views.setTextViewText(R.id.widget_text, bodyOf(text))
                views.setOnClickPendingIntent(R.id.widget_root, WidgetUi.activity(context, NotesWidgets.open(note.id), widgetId))
            }
            mgr.updateAppWidget(widgetId, views)
        }
    }
}

/** Asked by the launcher when the widget is placed (and again from its menu): which note to show. */
class NoteWidgetConfigure : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = application as App
        val current = NoteWidget.noteOf(this, widgetId)

        fun choose(noteId: String, thenWrite: Boolean) {
            NoteWidget.bind(this, widgetId, noteId)
            NoteWidget.render(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            if (thenWrite) startActivity(NotesWidgets.open(noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }

        setContent {
            val settings by app.prefs.settings.collectAsState()
            val all by app.store.notes.collectAsState()
            ReaderTheme(settings) {
                val view = LocalView.current
                val colors = LocalColors.current
                val typo = LocalTypo.current
                LaunchedEffect(colors.isDark) {
                    val c = WindowInsetsControllerCompat(window, view)
                    c.isAppearanceLightStatusBars = !colors.isDark
                    c.isAppearanceLightNavigationBars = !colors.isDark
                }
                val notes = remember(all) { app.store.live() }
                Page {
                    Column(Modifier.fillMaxSize()) {
                        ScreenTitle(stringResource(R.string.widget_note_pick), onBack = { finish() })
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                            item(key = "+") { TextRow(stringResource(R.string.new_note), size = typo.title) { choose(app.store.create(), thenWrite = true) } }
                            items(notes, key = { it.id }) { n ->
                                val title = app.store.title(n.id).ifBlank { stringResource(R.string.untitled) }
                                val preview = app.store.preview(n.id)
                                val where = if (n.isBook) " · " + stringResource(R.string.books) else if (settings.useFolders && n.folder.isNotEmpty()) " · ${n.folder}" else ""
                                Column(Modifier.fillMaxWidth().noRippleClickable { choose(n.id, thenWrite = false) }
                                    .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                                    T((if (n.id == current) "● " else "") + title, size = typo.title, maxLines = 1)
                                    Small(whenLabel(n.modified, stringResource(R.string.yesterday)) + where + (if (preview.isNotEmpty()) " · $preview" else ""), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
