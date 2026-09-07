package dev.claudewear.spike

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every way the system can reach this app writes one line here. The point of the spike is to
 * read this list on the wrist after holding the button, so the log is on screen, in logcat
 * (`adb logcat -s HermesSpike`), and on disk (`adb shell run-as <pkg> cat files/invocations.log`).
 */
object InvocationLog {
    const val TAG = "HermesSpike"

    data class Entry(val at: String, val source: String, val detail: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var file: File? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun attach(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "invocations.log")
        file = f
        if (f.exists()) {
            _entries.value = f.readLines().takeLast(200).mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
            }
        }
    }

    fun record(source: String, detail: String = "") {
        val entry = Entry(stamp.format(Date()), source, detail)
        Log.i(TAG, "${entry.source} ${entry.detail}")
        _entries.value = (_entries.value + entry).takeLast(200)
        runCatching { file?.appendText("${entry.at}\t${entry.source}\t${entry.detail}\n") }
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.writeText("") }
    }

    /** A compact one-line description of an intent: action, categories, and extra keys. */
    fun describe(intent: Intent?): String {
        if (intent == null) return "intent=null"
        val cats = intent.categories?.joinToString(",") ?: "-"
        val extras = intent.extras?.keySet()?.joinToString(",") ?: "-"
        return "action=${intent.action ?: "-"} cats=$cats extras=$extras flags=0x${Integer.toHexString(intent.flags)}"
    }
}
