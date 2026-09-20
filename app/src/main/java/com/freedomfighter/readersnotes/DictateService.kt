package com.freedomfighter.readersnotes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readers.speech.audio.Pcm
import com.freedomfighter.readers.speech.audio.Resample
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readers.speech.whisper.Paragraphs
import com.freedomfighter.readers.speech.whisper.Prompts
import com.freedomfighter.readers.speech.whisper.Segment
import com.freedomfighter.readers.speech.whisper.Vad
import com.freedomfighter.readers.speech.whisper.WhisperSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dictation, on the model of Reader's Recorder's memo: a foreground service (type microphone)
 * keeps the microphone open with the screen off, and whisper.cpp writes what was said — on the
 * phone, nothing leaves it. Here the sound is only a means: it is kept as raw samples in
 * `files/dictation` until its text has reached the note, then deleted. A dictation whose
 * transcription was cut short (the process killed, the model not fetched yet) is still there at
 * the next opening and is taken up again.
 *
 * The text goes where the cursor is when the note is open, to the end of the note otherwise.
 */
class DictateService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lock: PowerManager.WakeLock? = null
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private val recording = AtomicBoolean(false)
    private var file: File? = null
    private var startedAt = 0L
    private var working = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when (intent?.action) {
            ACTION_START -> { goForeground(mic = allowed); if (recorder == null) start(intent.getStringExtra(EXTRA_NOTE) ?: "") }
            ACTION_STOP -> { goForeground(mic = recorder != null); stop() }
            else -> goForeground(mic = recorder != null)
        }
        pushNotification()
        work()
        return START_NOT_STICKY
    }

    // ---- recording ----

    @SuppressLint("MissingPermission")
    private fun start(noteId: String) {
        if (noteId.isEmpty() || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        // 16 kHz is what whisper reads; a phone that refuses it records at 48 kHz, resampled later.
        val made = RATES.firstNotNullOfOrNull { rate ->
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@firstNotNullOfOrNull null
            val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }.getOrNull()
            if (r?.state == AudioRecord.STATE_INITIALIZED) r to rate else { r?.release(); null }
        }
        if (made == null) { Live.message = getString(R.string.dictate_no_mic); return }
        val (r, rate) = made
        val f = File(dir(this), "$noteId-${System.currentTimeMillis()}.$rate.rec")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadersNotes:dictate").apply { setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L) }
        recorder = r; file = f
        startedAt = SystemClock.elapsedRealtime()
        recording.set(true)
        Live.message = ""; Live.noteId = noteId; Live.elapsedMs = 0L; Live.level = 0f; Live.recording = true
        r.startRecording()
        reader = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(rate / 10)
            val bytes = ByteArray(buf.size * 2)
            BufferedOutputStream(f.outputStream()).use { out ->
                while (recording.get()) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var peak = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                    }
                    out.write(bytes, 0, 2 * n)
                    Live.level = peak / 32768f
                }
            }
        }.apply { start() }
        scope.launch { while (isActive && recording.get()) { Live.elapsedMs = SystemClock.elapsedRealtime() - startedAt; delay(250) } }
        pushNotification()
    }

    private fun stop() {
        val r = recorder ?: return
        recording.set(false)
        runCatching { reader?.join(2_000) }
        runCatching { r.stop() }; runCatching { r.release() }
        recorder = null; reader = null
        val f = file; file = null
        // A tap by mistake is not a dictation. Renamed only now: the queue never sees a file still being written.
        val kept = f != null && f.length() >= MIN_BYTES && f.renameTo(File(f.parentFile, f.name.removeSuffix(".rec") + ".pcm"))
        if (!kept) f?.delete()
        Live.recording = false; Live.level = 0f; Live.elapsedMs = 0L
        if (!kept) dropIfEmpty(Live.noteId)
    }

    // ---- transcription ----

    private fun work() {
        if (working) return
        working = true
        scope.launch {
            try {
                while (true) {
                    val f = queue(this@DictateService).firstOrNull() ?: break
                    val noteId = f.name.substringBefore('-')
                    Live.workingOn = noteId; Live.percent = 0
                    val text = try {
                        withContext(Dispatchers.Default) { transcribe(f) }
                    } catch (e: Exception) {
                        // The sound stays: the next opening tries again (no network for the model, say).
                        Live.message = (e.message ?: e.javaClass.simpleName).take(120)
                        break
                    }
                    f.delete()
                    deliver(noteId, text)
                }
            } finally {
                Live.workingOn = ""; Live.phase = ""; Live.percent = 0
                working = false
                // A dictation that ended while this pass was closing starts another one.
                if (queue(this@DictateService).isNotEmpty() && Live.message.isEmpty()) work() else finish()
            }
        }
    }

    private fun transcribe(f: File): String {
        val app = application as App
        val s = app.prefs.settings.value
        val rate = f.name.removeSuffix(".pcm").substringAfterLast('.').toIntOrNull() ?: 16_000
        // The quality asked for; the careful model when it is the one already on the phone.
        val wanted = Models.byKey(s.dictationModel)
        val model = if (!Models.isDownloaded(this, wanted) && Models.isDownloaded(this, Models.HIGH)) Models.HIGH else wanted
        if (!Models.isDownloaded(this, model)) {
            Live.phase = "model"
            Models.download(this, model, onProgress = { Live.percent = it.coerceIn(0, 100) })
        }
        val handle = Models.open(this, model) ?: error(getString(R.string.dictate_no_model))
        val lang = s.dictationLanguage.ifBlank { null }
        Live.phase = "transcribe"; Live.percent = 0
        val segments = ArrayList<Segment>()
        val total = (f.length() / 2).coerceAtLeast(1)
        handle.use { WhisperSession(handle.path, Vad.modelPath(this)).use { session ->
            DataInputStream(f.inputStream().buffered()).use { input ->
                var done = 0L
                while (true) {
                    val piece = readPiece(input, rate * PIECE_SECONDS)
                    if (piece.isEmpty()) break
                    val pcm = if (rate == 16_000) piece else Resample.to(Pcm(piece, rate), 16_000).samples
                    val startMs = done * 1000 / rate
                    val prompt = if (segments.isEmpty()) Prompts.style(lang) else Prompts.forPiece(lang, segments.takeLast(12).joinToString(" ") { it.text })
                    val segs = session.run(pcm, lang, prompt) { p ->
                        Live.percent = (((done + piece.size * p / 100.0) / total) * 100).toInt().coerceIn(0, 99)
                    } ?: error("stopped")
                    segs.forEach { segments += it.copy(startMs = it.startMs + startMs, endMs = it.endMs + startMs) }
                    done += piece.size
                }
            }
        } }
        return Paragraphs.build(segments, "").trim()
    }

    private fun readPiece(input: DataInputStream, samples: Int): FloatArray {
        val bytes = ByteArray(samples * 2)
        var got = 0
        try {
            while (got < bytes.size) { val n = input.read(bytes, got, bytes.size - got); if (n < 0) break; got += n }
        } catch (_: EOFException) { }
        val n = got / 2
        return FloatArray(n) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
    }

    /** At the cursor when the note is open; at the end of the note otherwise. */
    private fun deliver(noteId: String, text: String) {
        if (text.isBlank()) { Live.message = getString(R.string.dictate_no_speech); dropIfEmpty(noteId); return }
        Live.message = ""
        if (Live.editing == noteId) Live.arrived = Arrival(noteId, text) else append(application as App, noteId, text)
    }

    /** A note opened by « dictate » and left before a word reached it is not worth keeping. */
    private fun dropIfEmpty(noteId: String) {
        val app = application as App
        if (Live.editing == noteId || pendingFor(this, noteId)) return
        if (app.store.get(noteId)?.deleted == false && app.store.text(noteId).isBlank()) app.store.delete(noteId)
    }

    private fun finish() {
        if (working || recorder != null) return
        runCatching { if (lock?.isHeld == true) lock?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        if (recorder != null) stop()
        scope.cancel()
        runCatching { if (lock?.isHeld == true) lock?.release() }
        super.onDestroy()
    }

    // ---- notification ----

    private fun goForeground(mic: Boolean) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun pushNotification() = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.dictation), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
        if (recorder != null) {
            b.setContentTitle(getString(R.string.dictating)).setUsesChronometer(true).setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt)).setShowWhen(true)
            b.addAction(0, getString(R.string.stop), PendingIntent.getService(this, 1, Intent(this, DictateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else b.setContentTitle(getString(R.string.transcribing_plain)).setShowWhen(false)
        return b.build()
    }

    /** What was written, on its way to the open note. */
    class Arrival(val noteId: String, val text: String)

    /** In-memory mirror of the dictation, observed by the screens. */
    object Live {
        var recording by mutableStateOf(false)
        /** The note being dictated into. */
        var noteId by mutableStateOf("")
        var elapsedMs by mutableLongStateOf(0L)
        var level by mutableFloatStateOf(0f)
        /** The note whose words are being written. */
        var workingOn by mutableStateOf("")
        var phase by mutableStateOf("")   // model | transcribe
        var percent by mutableIntStateOf(0)
        /** Said once under the row: nothing heard, or what went wrong. */
        var message by mutableStateOf("")
        /** The note open in the editor, which takes the text at its cursor. */
        var editing by mutableStateOf("")
        var arrived by mutableStateOf<Arrival?>(null)
    }

    companion object {
        const val ACTION_START = "com.freedomfighter.readersnotes.DICTATE_START"
        const val ACTION_STOP = "com.freedomfighter.readersnotes.DICTATE_STOP"
        const val EXTRA_NOTE = "note"
        private val RATES = listOf(16_000, 48_000)
        private const val PIECE_SECONDS = 300
        private const val MIN_BYTES = 16_000L   // half a second
        private const val CHANNEL_ID = "dictation"
        private const val NOTIF_ID = 1

        fun dir(ctx: Context): File = File(ctx.filesDir, "dictation").apply { mkdirs() }
        /** Finished dictations waiting for their words, oldest first. */
        fun queue(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.name.endsWith(".pcm") }?.sortedBy { it.name.substringAfter('-') } ?: emptyList()
        /** Whether words are still owed to [noteId]: being said, or waiting to be written. */
        fun pendingFor(ctx: Context, noteId: String): Boolean =
            (Live.recording && Live.noteId == noteId) || queue(ctx).any { it.name.startsWith("$noteId-") }

        fun start(ctx: Context, noteId: String) = ContextCompat.startForegroundService(ctx, Intent(ctx, DictateService::class.java).setAction(ACTION_START).putExtra(EXTRA_NOTE, noteId))
        fun stop(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, DictateService::class.java).setAction(ACTION_STOP))

        /** From the activity coming to the front: take up what an earlier run left unwritten. */
        fun kick(ctx: Context) {
            // what a killed process left half-said is still a dictation
            dir(ctx).listFiles()?.filter { it.name.endsWith(".rec") && !(Live.recording) }?.forEach {
                if (it.length() >= MIN_BYTES) it.renameTo(File(it.parentFile, it.name.removeSuffix(".rec") + ".pcm")) else it.delete()
            }
            if (queue(ctx).isNotEmpty() && Live.workingOn.isEmpty()) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DictateService::class.java)) }
        }

        /** Dictated words for a note that is not open: after what it holds; a note since deleted is made again. */
        fun append(app: App, noteId: String, text: String) {
            val n = app.store.get(noteId)
            if (n == null || n.deleted) app.store.create(text)
            else { val old = app.store.text(noteId).trimEnd(); app.store.save(noteId, if (old.isEmpty()) text else "$old\n\n$text") }
            app.sync()
        }

        fun clock(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
    }
}
