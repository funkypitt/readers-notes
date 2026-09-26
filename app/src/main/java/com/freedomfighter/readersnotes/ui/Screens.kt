package com.freedomfighter.readersnotes.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.DictateService
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.Credentials
import com.freedomfighter.readersnotes.data.CredentialsShare
import com.freedomfighter.readersnotes.data.FontChoice
import com.freedomfighter.readersnotes.data.NotesStore
import com.freedomfighter.readersnotes.data.Prefs
import com.freedomfighter.readersnotes.data.findLinks
import com.freedomfighter.readersnotes.data.openLink
import com.freedomfighter.readersnotes.data.TextSize
import com.freedomfighter.readersnotes.data.ThemeMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class Screen {
    data object Notes : Screen()
    /** Folders on: one folder's notes ([name] "" = none), or every note ([name] null). */
    data class Folder(val name: String?, val query: String = "") : Screen()
    data class Edit(val id: String) : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Notes)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
    var version by mutableIntStateOf(0)
    /** Asked from outside (the launcher): a new note, the microphone open. */
    var wantDictate by mutableStateOf(false)
}

fun whenLabel(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (d.toLocalDate()) {
        today -> d.format(DateTimeFormatter.ofPattern("HH:mm"))
        today.minusDays(1) -> "yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy")).lowercase()
    }
}

// ---------------------------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------------------------

@Composable
fun NotesScreen(nav: Nav, app: App, folder: String? = null, inFolders: Boolean = false, initialQuery: String = "") {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    val all by app.store.notes.collectAsState()
    val status by app.status.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf(initialQuery) }
    var asking by remember { mutableStateOf(false) }
    val notes = remember(all, query, nav.version, folder) {
        app.store.live().filter { (folder == null || it.folder == folder) && (query.isBlank() || app.store.text(it.id).contains(query, ignoreCase = true)) }
    }
    val here = folder ?: ""
    fun newNote() { nav.push(Screen.Edit(app.store.create(folder = here))) }
    var moving by remember { mutableStateOf<String?>(null) }
    if (inFolders) BackHandler { if (query.isNotBlank() && initialQuery.isBlank()) query = "" else nav.pop() }
    val live = DictateService.Live
    val dictateNew = rememberDictate { app.store.create(folder = here).also { nav.push(Screen.Edit(it)) } }
    LaunchedEffect(nav.wantDictate) { if (nav.wantDictate) { nav.wantDictate = false; if (!live.recording) dictateNew() } }
    Page {
        Column(Modifier.fillMaxSize()) {
            val place = when { !inFolders -> stringResource(R.string.app_title); folder == null -> stringResource(R.string.all_notes); else -> folder }
            ScreenTitle(
                if (query.isBlank()) place else "“$query”",
                onBack = if (inFolders) ({ if (query.isNotBlank() && initialQuery.isBlank()) query = "" else nav.pop() }) else if (query.isBlank()) null else ({ query = "" }),
                trailing = "⋯", onTrailing = { menu = true }
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (notes.isEmpty()) item {
                    Small(if (query.isBlank()) stringResource(R.string.empty) else stringResource(R.string.nothing_found), Modifier.padding(horizontal = rowPadH, vertical = rowPadV))
                }
                items(notes, key = { it.id }) { n ->
                    val title = app.store.title(n.id).ifBlank { stringResource(R.string.untitled) }
                    val preview = dictationStatus(n.id) ?: app.store.preview(n.id)
                    Column(Modifier.fillMaxWidth().pressable(onClick = { nav.push(Screen.Edit(n.id)) }, onLongPress = { noteMenu = n.id })
                        .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(title, size = typo.title, maxLines = 1)
                        val where = if (inFolders && folder == null && n.folder.isNotEmpty()) " · ${n.folder}" else ""
                        Small(whenLabel(n.modified) + where + (if (preview.isNotEmpty()) " · $preview" else "") + (if (n.dirty && settings.configured) " · ✎" else ""), maxLines = 1)
                    }
                }
            }
            Rule()
            // The two ways into a note, one tap each: write it, or say it.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.new_note), size = typo.title) { newNote() } }
                Box(Modifier.weight(1f)) { DictateRow { app.store.create(folder = here).also { nav.push(Screen.Edit(it)) } } }
            }
            if (live.message.isNotEmpty()) Small(live.message, Modifier.padding(horizontal = rowPadH).padding(bottom = 6.dp), maxLines = 2)
            if (status.isNotEmpty() || !settings.configured) {
                Small(if (settings.configured) status else stringResource(R.string.not_synced), Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp).noRippleClickable { if (settings.configured) app.sync() else nav.push(Screen.Settings) }, maxLines = 1)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, buildList {
            add(MenuItem(stringResource(R.string.new_note)) { newNote() })
            add(MenuItem(stringResource(R.string.find)) { asking = true })
            if (settings.configured) add(MenuItem(stringResource(R.string.sync_now)) { app.sync() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        noteMenu?.let { id ->
            TextMenu(app.store.title(id).ifBlank { stringResource(R.string.untitled) }, buildList {
                addAll(linkItems(context, app.store.text(id)))
                add(MenuItem(stringResource(R.string.share)) { share(context, app.store.text(id)) })
                if (settings.useFolders) add(MenuItem(stringResource(R.string.move_to_folder)) { moving = id })
                add(MenuItem(stringResource(R.string.delete)) { app.store.delete(id); app.sync() })
            }, onDismiss = { noteMenu = null })
        }
        moving?.let { id -> MoveMenu(app, id) { moving = null } }
        if (asking) TextPrompt(stringResource(R.string.find), initial = query, confirm = stringResource(R.string.find), onDone = { query = it; asking = false }, onCancel = { asking = false })
    }
}

/** "Move to": no folder, or one of the folders (the current one marked). */
@Composable
fun MoveMenu(app: App, id: String, onDismiss: () -> Unit) {
    val current = app.store.get(id)?.folder ?: ""
    TextMenu(stringResource(R.string.move_to_folder),
        listOf(MenuItem((if (current.isEmpty()) "● " else "○ ") + stringResource(R.string.no_folder)) { app.store.move(id, ""); app.sync() }) +
            app.store.folders().map { f -> MenuItem((if (current == f.name) "● " else "○ ") + f.name) { app.store.move(id, f.name); app.sync() } },
        onDismiss = onDismiss)
}

/** A folder drawn small, in the text's colour: tab on the top left. [filled] for "all notes". */
@Composable
fun FolderGlyph(filled: Boolean = false, dashed: Boolean = false) {
    val colors = LocalColors.current
    androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 23.dp)) {
        val stroke = 1.6.dp.toPx(); val r = 3.dp.toPx()
        val tabW = size.width * 0.42f; val tab = size.height * 0.18f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(r, 0f); lineTo(tabW - tab * 0.4f, 0f); lineTo(tabW + tab * 0.6f, tab)
            lineTo(size.width - r, tab); quadraticBezierTo(size.width, tab, size.width, tab + r)
            lineTo(size.width, size.height - r); quadraticBezierTo(size.width, size.height, size.width - r, size.height)
            lineTo(r, size.height); quadraticBezierTo(0f, size.height, 0f, size.height - r)
            lineTo(0f, r); quadraticBezierTo(0f, 0f, r, 0f); close()
        }
        val c = if (dashed) colors.dim else colors.fg
        if (filled) drawPath(p, c)
        else drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke,
            pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null))
    }
}

/** Folders on: the first page is the list of folders, "all notes" first, as in Reader's Scanner. */
@Composable
fun FoldersScreen(nav: Nav, app: App) {
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    val all by app.store.notes.collectAsState()
    val folders by app.store.folderList.collectAsState()
    val status by app.status.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val sorted = remember(folders) { folders.sortedBy { it.name.lowercase() } }
    val live = DictateService.Live
    val dictateNew = rememberDictate { app.store.create().also { nav.push(Screen.Edit(it)) } }
    LaunchedEffect(nav.wantDictate) { if (nav.wantDictate) { nav.wantDictate = false; if (!live.recording) dictateNew() } }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.app_title), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                item(key = "all") { FolderRow(stringResource(R.string.all_notes), all.count { !it.deleted }, filled = true) { nav.push(Screen.Folder(null)) } }
                items(sorted, key = { "f:" + it.name }) { f ->
                    FolderRow(f.name, all.count { !it.deleted && it.folder == f.name }, onLongPress = { folderMenu = f.name }) { nav.push(Screen.Folder(f.name)) }
                }
                item(key = "+") { FolderRow(stringResource(R.string.new_folder), null, dashed = true) { creating = true } }
            }
            Rule()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.new_note), size = typo.title) { nav.push(Screen.Edit(app.store.create())) } }
                Box(Modifier.weight(1f)) { DictateRow { app.store.create().also { nav.push(Screen.Edit(it)) } } }
            }
            if (live.message.isNotEmpty()) Small(live.message, Modifier.padding(horizontal = rowPadH).padding(bottom = 6.dp), maxLines = 2)
            if (status.isNotEmpty() || !settings.configured) {
                Small(if (settings.configured) status else stringResource(R.string.not_synced), Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp).noRippleClickable { if (settings.configured) app.sync() else nav.push(Screen.Settings) }, maxLines = 1)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, buildList {
            add(MenuItem(stringResource(R.string.new_note)) { nav.push(Screen.Edit(app.store.create())) })
            add(MenuItem(stringResource(R.string.new_folder)) { creating = true })
            add(MenuItem(stringResource(R.string.find)) { asking = true })
            if (settings.configured) add(MenuItem(stringResource(R.string.sync_now)) { app.sync() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        folderMenu?.let { f ->
            TextMenu(f, listOf(
                MenuItem(stringResource(R.string.rename)) { renaming = f },
                MenuItem(stringResource(R.string.delete_folder)) { deleting = f }
            ), onDismiss = { folderMenu = null })
        }
        if (creating) TextPrompt(stringResource(R.string.new_folder_name), onDone = { if (app.store.addFolder(it) != null) app.sync(); creating = false }, onCancel = { creating = false })
        renaming?.let { f -> TextPrompt(stringResource(R.string.rename), initial = f, onDone = { app.store.renameFolder(f, it); renaming = null; app.sync() }, onCancel = { renaming = null }) }
        deleting?.let { f ->
            TextMenu(stringResource(R.string.delete_folder_q, f), listOf(
                MenuItem(stringResource(R.string.delete_folder_keep)) { app.store.deleteFolder(f); app.sync() },
                MenuItem(stringResource(R.string.action_cancel)) { }
            ), onDismiss = { deleting = null })
        }
        if (asking) TextPrompt(stringResource(R.string.find), confirm = stringResource(R.string.find), onDone = { asking = false; nav.push(Screen.Folder(null, it)) }, onCancel = { asking = false })
    }
}

@Composable
private fun FolderRow(name: String, count: Int?, filled: Boolean = false, dashed: Boolean = false, onLongPress: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = LocalColors.current
    Row(Modifier.fillMaxWidth().then(if (onLongPress != null) Modifier.pressable(onClick = onClick, onLongPress = onLongPress) else Modifier.noRippleClickable(onClick = onClick))
        .padding(horizontal = rowPadH, vertical = rowPadV * 0.75f), verticalAlignment = Alignment.CenterVertically) {
        FolderGlyph(filled, dashed)
        T(name, Modifier.weight(1f).padding(start = 18.dp), size = LocalTypo.current.title, color = if (dashed) colors.dim else colors.fg, maxLines = 1)
        if (count != null) Small(count.toString(), maxLines = 1)
    }
}

/**
 * One menu line per thing the note holds that can be acted on: "call …", "write to …", "open …".
 * Nothing is added when the note holds none.
 */
@Composable
fun linkItems(context: android.content.Context, text: String): List<MenuItem> {
    val call = stringResource(R.string.link_call)
    val write = stringResource(R.string.link_write)
    val open = stringResource(R.string.link_open)
    val noApp = stringResource(R.string.no_app_for_this)
    return findLinks(text).map { link ->
        val verb = when {
            link.uri.startsWith("tel:") -> call
            link.uri.startsWith("mailto:") -> write
            else -> open
        }
        MenuItem("$verb ${link.text}") {
            if (!openLink(context, link.uri)) android.widget.Toast.makeText(context, noApp, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

fun share(context: android.content.Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, NotesStore.titleOf(text))
    context.startActivity(Intent.createChooser(i, null))
}

// ---------------------------------------------------------------------------------------------
// The note: one text field filling the page, saved as you type
// ---------------------------------------------------------------------------------------------

@Composable
fun EditScreen(nav: Nav, app: App, id: String) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    // the cursor starts at the end: what is dictated into a note just opened goes after what it holds
    var value by remember { mutableStateOf(app.store.text(id).let { TextFieldValue(it, TextRange(it.length)) }) }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val empty = value.text.isEmpty()
    BackHandler { nav.pop() }
    val live = DictateService.Live
    // opened by « dictate »: the keyboard stays down, the page is for what is being said
    LaunchedEffect(Unit) { if (empty && !(live.recording && live.noteId == id)) focus.requestFocus() }
    LaunchedEffect(value.text) { app.store.save(id, value.text) }
    // What was dictated arrives at the cursor, a space or nothing before it as the text around asks.
    LaunchedEffect(live.arrived) {
        val a = live.arrived ?: return@LaunchedEffect
        if (a.noteId != id) return@LaunchedEffect
        live.arrived = null
        val before = value.text.substring(0, value.selection.min)
        val after = value.text.substring(value.selection.max)
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isEmpty() || after.first().isWhitespace()) "" else " "
        val inserted = before + lead + a.text + trail
        value = TextFieldValue(inserted + after, TextRange(inserted.length))
    }
    DisposableEffect(Unit) {
        live.editing = id
        onDispose {
            live.editing = ""
            // words that arrived as the page was closing still belong to the note
            live.arrived?.takeIf { it.noteId == id }?.let { live.arrived = null; DictateService.append(app, id, it.text) }
            // an empty note is not worth keeping — unless its words are still on their way; a written one goes to the server
            if (app.store.text(id).isBlank()) { if (!DictateService.pendingFor(context, id)) app.store.delete(id) }
            else if (settings.configured) app.sync()
        }
    }
    val title = NotesStore.titleOf(value.text)
    Page {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenTitle(title.ifBlank { stringResource(R.string.new_note_title) }, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).noRippleClickable { focus.requestFocus() }) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 14.dp).focusRequester(focus),
                    textStyle = TextStyle(color = colors.fg, fontFamily = typo.family, fontWeight = typo.weight, fontSize = typo.title, lineHeight = typo.title * 1.45f),
                    cursorBrush = SolidColor(colors.fg),
                    decorationBox = { inner ->
                        Box { if (empty) T(stringResource(if (live.recording && live.noteId == id) R.string.speak_here else R.string.write_here), size = typo.title, color = colors.dim, align = TextAlign.Start); inner() }
                    }
                )
            }
            Rule()
            DictateRow(secondary = dictationStatus(id) ?: live.message.ifEmpty { null }) { id }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, buildList {
            // a number, a mail or a web address written in the note: opened from here, because the
            // note itself is a text field where a tap places the cursor
            addAll(linkItems(context, value.text))
            add(MenuItem(stringResource(R.string.share)) { share(context, value.text) })
            add(MenuItem(stringResource(R.string.delete)) { app.store.delete(id); nav.pop(); app.sync() })
        }, onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) }
        ))
    }
}

// ---------------------------------------------------------------------------------------------
// Settings: the account, the look
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val status by app.status.collectAsState()
    var prompt by remember { mutableStateOf<String?>(null) }   // server | folder | username | password
    val context = LocalContext.current
    var credMessage by remember { mutableStateOf("") }
    val notCredentials = stringResource(R.string.credentials_not_a_file)
    val nothingForUs = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val imported = stringResource(R.string.credentials_imported)
    val importedFrom = stringResource(R.string.credentials_imported_from, "Reader's Recorder")
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        credMessage = try {
            val got = Credentials.read(CredentialsShare.readText(context, uri))
            val a = got.account; val cur = app.prefs.settings.value
            // the same as typing the account by hand, then a sync
            app.prefs.setAccount(a.server ?: cur.server, a.folder ?: cur.folder, a.username ?: cur.username, a.password ?: cur.password)
            app.sync()
            if (got.fromFallback) importedFrom else imported
        } catch (e: Credentials.NotCredentials) { notCredentials
        } catch (e: Credentials.NothingForUs) { nothingForUs
        } catch (e: Exception) { e.message ?: notCredentials }
    }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Small(stringResource(R.string.account_hint), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 6)
                TextRow(s.server.ifBlank { stringResource(R.string.server) }, secondary = stringResource(R.string.server)) { prompt = "server" }
                TextRow(s.username.ifBlank { stringResource(R.string.username) }, secondary = stringResource(R.string.username)) { prompt = "username" }
                TextRow(if (s.password.isBlank()) stringResource(R.string.password) else "••••••••", secondary = stringResource(R.string.password)) { prompt = "password" }
                TextRow(s.folder, secondary = stringResource(R.string.folder)) { prompt = "folder" }
                TextRow(if (s.syncOnOpen) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.sync_on_open)) { app.prefs.setSyncOnOpen(!s.syncOnOpen) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (s.useFolders) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.folders_setting)) { app.prefs.setUseFolders(!s.useFolders) }
                Small(stringResource(R.string.folders_hint), Modifier.padding(horizontal = rowPadH).padding(bottom = 8.dp), maxLines = 6)
                if (s.configured) TextRow(stringResource(R.string.sync_now), secondary = status.ifBlank { null }) { app.sync() }
                val shareTitle = stringResource(R.string.export_credentials)
                if (s.configured) TextRow(shareTitle, secondary = stringResource(R.string.export_credentials_hint)) {
                    CredentialsShare.share(context, Credentials.build(s.server, s.folder, s.username, s.password), shareTitle)
                }
                TextRow(stringResource(R.string.import_credentials), secondary = credMessage.ifBlank { null }) {
                    credMessage = ""
                    pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
                Rule(Modifier.padding(vertical = 8.dp))
                // Dictation. Two rows rather than one that switches, so the advice stands beside its
                // option: a note is a few sentences, the careful model costs seconds and halves the
                // mistakes (measured 2026-09-18: 10 to 11 % of words wrong against 4 to 5 %).
                Small(stringResource(R.string.dictation_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp, bottom = 4.dp), maxLines = 4)
                val chosen = Models.byKey(s.dictationModel)
                val downloading by Models.downloading.collectAsState()
                listOf(Models.HIGH, Models.NORMAL).forEach { m ->
                    val state = when { Models.isDownloaded(context, m) -> ""; downloading >= 0 && m == chosen -> " · " + stringResource(R.string.phase_model, downloading); else -> " · " + stringResource(R.string.model_not_yet) }
                    val note = if (m == Models.HIGH) " · " + stringResource(R.string.recommended) + " · " + stringResource(R.string.quality_high_hint) else ""
                    TextRow(stringResource(if (m == Models.HIGH) R.string.quality_high else R.string.quality_normal), inverted = m == chosen,
                        secondary = m.mb.toString() + " MB" + note + state) { app.prefs.setDictationModel(m.key) }
                }
                TextRow(if (s.dictationLanguage.isBlank()) stringResource(R.string.language_auto) else java.util.Locale(s.dictationLanguage).getDisplayLanguage(java.util.Locale.getDefault()).lowercase(),
                    secondary = stringResource(R.string.language)) {
                    val all = Prefs.languages()
                    app.prefs.setDictationLanguage(all[(all.indexOf(s.dictationLanguage).coerceAtLeast(0) + 1) % all.size])
                }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.text_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
                TextRow(stringResource(R.string.credits)) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        prompt?.let { which ->
            val title = when (which) { "server" -> stringResource(R.string.server); "username" -> stringResource(R.string.username); "password" -> stringResource(R.string.password); else -> stringResource(R.string.folder) }
            val initial = when (which) { "server" -> s.server; "username" -> s.username; "password" -> s.password; else -> s.folder }
            TextPrompt(title, initial = initial, password = which == "password", onDone = { v ->
                when (which) {
                    "server" -> app.prefs.setAccount(v, s.folder, s.username, s.password)
                    "username" -> app.prefs.setAccount(s.server, s.folder, v, s.password)
                    "password" -> app.prefs.setAccount(s.server, s.folder, s.username, v)
                    else -> app.prefs.setAccount(s.server, v, s.username, s.password)
                }
                prompt = null
            }, onCancel = { prompt = null })
        }
    }
}
