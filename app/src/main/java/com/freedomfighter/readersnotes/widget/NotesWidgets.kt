package com.freedomfighter.readersnotes.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.Note
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Standard home-screen widgets for any launcher: the latest note, or the latest notes. */
object NotesWidgets {
    private const val PKG = "com.freedomfighter.readersnotes"
    fun notes(context: Context): List<Note> = (context.applicationContext as App).store.live()
    fun open(id: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://$PKG/notes/$id")).setClassName(PKG, "$PKG.MainActivity")
    fun sub(context: Context, n: Note): String {
        val store = (context.applicationContext as App).store
        val d = Instant.ofEpochMilli(n.modified).atZone(ZoneId.systemDefault()); val today = LocalDate.now()
        val when_ = when (d.toLocalDate()) { today -> d.format(DateTimeFormatter.ofPattern("HH:mm")); today.minusDays(1) -> "yesterday"; else -> d.format(DateTimeFormatter.ofPattern("d MMM")).lowercase() }
        val preview = store.preview(n.id)
        return if (preview.isEmpty()) when_ else "$when_ · $preview"
    }

    fun renderLine(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_line)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_title, R.id.widget_plus), intArrayOf(R.id.widget_sub))
        val store = (context.applicationContext as App).store
        val first = notes(context).firstOrNull()
        if (first == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.empty_short)); views.setTextViewText(R.id.widget_sub, context.getString(R.string.app_title))
            views.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(context, open("new"), 1))
        } else {
            views.setTextViewText(R.id.widget_title, store.title(first.id).ifBlank { context.getString(R.string.untitled) })
            views.setTextViewText(R.id.widget_sub, sub(context, first))
            views.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(context, open(first.id), 1))
        }
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, open("new"), 2))
        mgr.updateAppWidget(id, views)
    }

    fun renderList(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_plus), intArrayOf(R.id.widget_caption, R.id.widget_empty))
        views.setTextViewText(R.id.widget_caption, context.getString(R.string.app_title))
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.empty_short))
        val svc = Intent(context, ListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_list, WidgetUi.activity(context, Intent(Intent.ACTION_VIEW).setClassName(PKG, "$PKG.MainActivity"), 3))
        views.setOnClickPendingIntent(R.id.widget_caption, WidgetUi.activity(context, Intent(Intent.ACTION_MAIN).setClassName(PKG, "$PKG.MainActivity"), 1))
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, open("new"), 2))
        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    fun refresh(context: Context) = WidgetUi.refresh(context, LineWidget::class.java, ListWidget::class.java)
}

class LineWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { NotesWidgets.renderLine(context, mgr, it) } }
}

class ListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { NotesWidgets.renderList(context, mgr, it) } }
}

class ListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items: List<Note> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() { items = NotesWidgets.notes(applicationContext) }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(i: Int): RemoteViews {
            val n = items[i]; val store = (applicationContext as App).store
            val v = RemoteViews(packageName, R.layout.widget_item)
            val (_, fg, dim) = WidgetUi.colors(applicationContext)
            v.setTextViewText(R.id.item_title, store.title(n.id).ifBlank { getString(R.string.untitled) }); v.setTextColor(R.id.item_title, fg)
            v.setTextViewText(R.id.item_sub, NotesWidgets.sub(applicationContext, n)); v.setTextColor(R.id.item_sub, dim)
            v.setOnClickFillInIntent(R.id.item_root, Intent().setData(Uri.parse("content://com.freedomfighter.readersnotes/notes/${n.id}")))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = items.getOrNull(i)?.id?.hashCode()?.toLong() ?: i.toLong()
        override fun hasStableIds() = true
    }
}
