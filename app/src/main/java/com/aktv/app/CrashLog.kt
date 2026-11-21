package com.aktv.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why did the last run end? A TV that closes the app by itself cannot be diagnosed from a description,
 * so the app writes the reason down and offers it on the next start.
 *
 * Two kinds of death are covered, because they look identical to the user:
 *
 * 1. an uncaught exception - the Java/Kotlin crash, with its stack trace;
 * 2. an unclean exit - the run never reached `onPause`, which is what an out-of-memory kill, a native
 *    crash inside the player or an ANR kill looks like from inside the app. No handler can intercept
 *    those, so the session marker left at start is compared at the next start instead.
 *
 * The note holds no stream URLs, cookies, key ids or keys - only the build, the device, the heap and the
 * stack.
 */
internal object CrashLog {

    private const val NOTE = "last-exit.txt"
    private const val SESSION = "session.txt"
    private const val SHOWN = "exit_note_shown"
    private const val PREFS = "aktv"
    private const val MAX_NOTE = 12_000
    private const val CRASH = "Crash ·"
    private const val STREAK = "launch_streak"

    @Volatile private var installed = false

    /** Records the next uncaught exception. Safe to call from every activity's `onCreate`. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "Crash · ${thread.name}\n${describe(app)}\n${stack(error)}")
            } catch (_: Throwable) {
                // A dying process must still reach the system handler below.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Call first thing in `onCreate`: notes an unclean end of the previous run, then marks this one. */
    fun beginSession(context: Context) {
        val app = context.applicationContext
        val session = File(app.filesDir, SESSION)
        val previous = try { if (session.exists()) session.readText() else "" } catch (_: Exception) { "" }
        if (previous.startsWith("running")) {
            // No onPause last time: the system killed the process. Keep the previous marker, because it
            // carries the moment it started, and add the reason this can be concluded — unless the note
            // already holds a stack trace, which explains far more than "no clean stop" and must not be
            // overwritten on the very launch that is about to show it.
            val existing = try { File(app.filesDir, NOTE).readText() } catch (_: Exception) { "" }
            if (!existing.contains(CRASH)) write(app, "Previous run ended without a clean stop\n$previous${describe(app)}")
        }
        writeFile(session, "running · ${stamp()}\n${describe(app)}")
        // A start that never reaches `onPause` leaves this count standing. Three in a row is a crash loop
        // whatever killed the process - including native and out-of-memory deaths no handler can see.
        runCatching {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(STREAK, prefs.getInt(STREAK, 0) + 1).apply()
        }
    }

    /** How many starts in a row ended before the app reached the background, this one included. */
    fun launchStreak(context: Context): Int =
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(STREAK, 0)
        }.getOrDefault(0)

    /** Call from `onPause`: a run that reaches here was not killed by the system. */
    fun endSession(context: Context) {
        val app = context.applicationContext
        writeFile(File(app.filesDir, SESSION), "stopped · ${stamp()}\n")
        runCatching { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(STREAK, 0).apply() }
    }

    /** Writes a crash the app caught itself, e.g. a `Throwable` from `onCreate` that it could survive. */
    fun record(context: Context, error: Throwable, where: String = "") {
        try {
            val label = if (where.isBlank()) Thread.currentThread().name else "${Thread.currentThread().name} · $where"
            write(context.applicationContext, "Crash · $label\n" + describe(context.applicationContext) + stack(error))
        } catch (_: Throwable) {
            // Diagnostics must never take the app down with them.
        }
    }

    /** The note to offer the user, or null when there is nothing new to show. */
    fun report(context: Context): String? {
        val app = context.applicationContext
        val file = File(app.filesDir, NOTE)
        val text = try { if (file.exists()) file.readText().take(MAX_NOTE) else "" } catch (_: Exception) { "" }
        if (text.isBlank()) return null
        val seen = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SHOWN, null)
        return text.takeIf { seen != fingerprint(it) }
    }

    /** Remembers that this note has been shown, so it is offered once and not on every start. */
    fun acknowledge(context: Context, note: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SHOWN, fingerprint(note)).apply()
    }

    private fun fingerprint(note: String) = note.take(600).hashCode().toString()

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun write(app: Context, body: String) {
        writeFile(File(app.filesDir, NOTE), "${stamp()}\n$body\n\n")
    }

    private fun writeFile(file: File, text: String) {
        try {
            file.writeText(text.take(MAX_NOTE))
        } catch (_: Exception) {
            // Diagnostics must never take the app down with them.
        }
    }

    /** The build, the ABI, the device and the heap - everything needed to recognise a low-memory TV. */
    private fun describe(app: Context): String = buildString {
        append(AppBuildInfo.label(app)).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
        append(" · ").append(Build.MANUFACTURER.take(40)).append(' ').append(Build.MODEL.take(48)).append('\n')
        append("Process ").append(if (Process.is64Bit()) "64-bit" else "32-bit")
        append(" · ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        val runtime = Runtime.getRuntime()
        append("Heap max ").append(runtime.maxMemory() / (1024 * 1024)).append(" MB · used ")
        append((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).append(" MB\n")
        try {
            val info = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            append("Device free ").append(info.availMem / (1024 * 1024)).append(" MB · total ")
            append(info.totalMem / (1024 * 1024)).append(" MB · lowMemory ").append(info.lowMemory).append('\n')
        } catch (_: Exception) {
            // Older firmware may refuse the query; the heap line above is enough.
        }
    }

    private fun stack(error: Throwable): String = StringWriter().also { writer ->
        PrintWriter(writer).use { error.printStackTrace(it) }
    }.toString()
}
