package com.freedomfighter.readersnotes.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.freedomfighter.readersnotes.DictateService
import com.freedomfighter.readersnotes.R

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(colors.rule, topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
        drawRect(colors.fg, size = Size(size.width * fraction, size.height))
    }
}

/** Where the dictation of [noteId] stands, for a second line: fetching the model, writing, or what went wrong. */
@Composable
fun dictationStatus(noteId: String): String? {
    val live = DictateService.Live
    return when {
        live.workingOn == noteId && live.phase == "model" -> stringResource(R.string.phase_model, live.percent)
        live.workingOn == noteId -> stringResource(R.string.phase_transcribe, live.percent)
        else -> null
    }
}

/**
 * One tap starts the dictation — the microphone is asked for the first time only — and [target]
 * says into which note, called once the microphone is ours: no note is made for a refusal.
 */
@Composable
fun rememberDictate(target: () -> String): () -> Unit {
    val context = LocalContext.current
    val refused = stringResource(R.string.dictate_refused)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (micGranted(context)) DictateService.start(context, target()) else DictateService.Live.message = refused
    }
    return {
        if (micGranted(context)) DictateService.start(context, target())
        else ask.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
}

private fun micGranted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The one control of dictation: « ● dictate », and while the microphone is open the running time,
 * inverted, under a level line — a tap stops. Writing the words takes a moment afterwards; the
 * row is free again meanwhile, so one can go on dictating.
 */
@Composable
fun DictateRow(secondary: String? = null, target: () -> String) {
    val context = LocalContext.current
    val typo = LocalTypo.current
    val live = DictateService.Live
    val tick = rememberTick()
    val dictate = rememberDictate(target)
    Column {
        if (live.recording) {
            Progress(live.level.coerceIn(0f, 1f))
            TextRow("■ " + DictateService.clock(live.elapsedMs), inverted = true, size = typo.title, secondary = secondary) { tick(); DictateService.stop(context) }
        } else {
            TextRow("● " + stringResource(R.string.dictate), size = typo.title, secondary = secondary) { tick(); dictate() }
        }
    }
}
