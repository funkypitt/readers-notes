package com.freedomfighter.readersnotes.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.freedomfighter.readersnotes.App
import com.freedomfighter.readersnotes.R
import com.freedomfighter.readersnotes.data.PdfExport
import com.freedomfighter.readersnotes.data.PdfLayout
import com.freedomfighter.readersnotes.data.Pictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « export as PDF » for one note: [start] asks where to save it; once saved, [Sheet] offers the share menu. */
class PdfExporter(val start: (noteId: String) -> Unit, private val saved: MutableState<String?>) {
    @Composable
    fun Sheet() {
        val context = LocalContext.current
        val name = saved.value ?: return
        val shareTitle = stringResource(R.string.share_export)
        TextMenu(stringResource(R.string.export_saved, name), listOf(MenuItem(shareTitle) { PdfExport.share(context, name, shareTitle) }),
            onDismiss = { saved.value = null })
    }
}

@Composable
fun rememberPdfExporter(app: App): PdfExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.pdf_failed)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = noteId ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val text = app.store.text(id)
        val font = app.prefs.settings.value.font
        val file = name
        scope.launch {
            val ok = withContext(Dispatchers.IO) { PdfExport.save(context, app.store.filesDir, uri, file, text, font) }
            if (ok) saved.value = file else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
    return PdfExporter({ id ->
        noteId = id
        name = PdfLayout.fileName(Pictures.titleOr(context, app.store.text(id)))
        runCatching { save.launch(name) }
    }, saved)
}
