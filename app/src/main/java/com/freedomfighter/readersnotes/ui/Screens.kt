package com.freedomfighter.readersnotes.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.Credentials
import com.freedomfighter.readersnotes.data.CredentialsShare
import com.freedomfighter.readersnotes.data.FontChoice
import com.freedomfighter.readersnotes.data.NotesStore
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
fun NotesScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val settings by app.prefs.settings.collectAsState()
    val all by app.store.notes.collectAsState()
    val status by app.status.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val notes = remember(all, query, nav.version) {
        app.store.live().filter { query.isBlank() || app.store.text(it.id).contains(query, ignoreCase = true) }
    }
    fun newNote() { nav.push(Screen.Edit(app.store.create())) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                if (query.isBlank()) stringResource(R.string.app_title) else "“$query”",
                onBack = if (query.isBlank()) null else ({ query = "" }),
                trailing = "⋯", onTrailing = { menu = true }
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (notes.isEmpty()) item {
                    Small(if (query.isBlank()) stringResource(R.string.empty) else stringResource(R.string.nothing_found), Modifier.padding(horizontal = rowPadH, vertical = rowPadV))
                }
                items(notes, key = { it.id }) { n ->
                    val title = app.store.title(n.id).ifBlank { stringResource(R.string.untitled) }
                    val preview = app.store.preview(n.id)
                    Column(Modifier.fillMaxWidth().pressable(onClick = { nav.push(Screen.Edit(n.id)) }, onLongPress = { noteMenu = n.id })
                        .padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                        T(title, size = typo.title, maxLines = 1)
                        Small(whenLabel(n.modified) + (if (preview.isNotEmpty()) " · $preview" else "") + (if (n.dirty && settings.configured) " · ✎" else ""), maxLines = 1)
                    }
                }
            }
            Rule()
            TextRow(stringResource(R.string.new_note), size = typo.title) { newNote() }
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
                add(MenuItem(stringResource(R.string.delete)) { app.store.delete(id); app.sync() })
            }, onDismiss = { noteMenu = null })
        }
        if (asking) TextPrompt(stringResource(R.string.find), initial = query, confirm = stringResource(R.string.find), onDone = { query = it; asking = false }, onCancel = { asking = false })
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
    var value by remember { mutableStateOf(TextFieldValue(app.store.text(id))) }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val empty = value.text.isEmpty()
    BackHandler { nav.pop() }
    LaunchedEffect(Unit) { if (empty) focus.requestFocus() }
    LaunchedEffect(value.text) { app.store.save(id, value.text) }
    DisposableEffect(Unit) {
        onDispose {
            // an empty note is not worth keeping; a written one goes to the server
            if (app.store.text(id).isBlank()) app.store.delete(id) else if (settings.configured) app.sync()
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
                        Box { if (empty) T(stringResource(R.string.write_here), size = typo.title, color = colors.dim, align = TextAlign.Start); inner() }
                    }
                )
            }
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
