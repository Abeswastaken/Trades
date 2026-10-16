package com.abeswastaken.trades.data

import com.abeswastaken.trades.core.engine.AlertEvent
import com.abeswastaken.trades.core.engine.AlertLevel
import com.abeswastaken.trades.core.engine.CheckKind
import com.abeswastaken.trades.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One alert as kept in the inbox. */
data class InboxItem(
    val id: Long,
    val at: Long,
    val key: String,
    val kind: String,
    val level: AlertLevel,
    val title: String,
    val body: String,
    val ticker: String?,
    val check: String,
    val read: Boolean = false,
)

/** One "why is it moving?" answer, kept as a notebook entry. */
data class LessonEntry(
    val at: Long,
    val ticker: String,
    val why: String,
    val driver: String,
    val confidence: String,
    val title: String,
    val text: String,
    val watchFor: String,
    val costUsd: Double,
)

data class AlertStatus(
    val lastAt: Long? = null,
    val lastCheck: String? = null,
    val lastResult: String? = null,
    val nextAt: Long? = null,
    val nextCheck: String? = null,
)

/**
 * The alert inbox, the lesson notebook and the scheduler's status, as small JSON files (no database
 * change). Shared by the app and the background check, which run in the same process.
 */
class AlertStore(private val dir: File) {
    private val inboxFile get() = File(dir, "alerts.json")
    private val lessonsFile get() = File(dir, "lessons.json")
    private val statusFile get() = File(dir, "alert_status.json")

    private val _inbox = MutableStateFlow(loadInbox())
    val inbox: StateFlow<List<InboxItem>> = _inbox
    private val _lessons = MutableStateFlow(loadLessons())
    val lessons: StateFlow<List<LessonEntry>> = _lessons
    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<AlertStatus> = _status

    /** Adds the events not already in the inbox (same key) and returns the new items, newest first. */
    @Synchronized
    fun add(events: List<AlertEvent>, check: CheckKind, now: Long = System.currentTimeMillis()): List<InboxItem> {
        val seen = _inbox.value.map { it.key }.toSet()
        var id = (_inbox.value.maxOfOrNull { it.id } ?: 0L) + 1
        val fresh = events.filter { it.key !in seen }.map { e ->
            InboxItem(id++, now, e.key, e.kind.name, e.level, e.title, e.body, e.ticker, check.label)
        }
        if (fresh.isNotEmpty()) save((fresh + _inbox.value).take(MAX))
        return fresh
    }

    @Synchronized
    fun markAllRead() {
        if (_inbox.value.any { !it.read }) save(_inbox.value.map { it.copy(read = true) })
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(items: List<InboxItem>) {
        _inbox.value = items
        write(inboxFile, JSONArray(items.map { i ->
            JSONObject().put("id", i.id).put("at", i.at).put("key", i.key).put("kind", i.kind).put("level", i.level.name)
                .put("title", i.title).put("body", i.body).put("ticker", i.ticker ?: JSONObject.NULL).put("check", i.check).put("read", i.read)
        }).toString())
    }

    private fun loadInbox(): List<InboxItem> = read(inboxFile) { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            InboxItem(
                o.getLong("id"), o.getLong("at"), o.getString("key"), o.getString("kind"),
                runCatching { AlertLevel.valueOf(o.getString("level")) }.getOrDefault(AlertLevel.NOTICE),
                o.getString("title"), o.getString("body"), if (o.isNull("ticker")) null else o.getString("ticker"),
                o.optString("check"), o.optBoolean("read"),
            )
        }
    }

    @Synchronized
    fun addLesson(l: LessonEntry) {
        val all = (listOf(l) + _lessons.value).take(MAX)
        _lessons.value = all
        write(lessonsFile, JSONArray(all.map(::lessonJson)).toString())
    }

    @Synchronized
    fun restoreLessons(list: List<LessonEntry>) {
        val all = (list + _lessons.value).distinctBy { it.at to it.ticker }.sortedByDescending { it.at }.take(MAX)
        _lessons.value = all
        write(lessonsFile, JSONArray(all.map(::lessonJson)).toString())
    }

    private fun loadLessons(): List<LessonEntry> = read(lessonsFile) { a ->
        (0 until a.length()).mapNotNull { lessonFrom(a.getJSONObject(it)) }
    }

    @Synchronized
    fun setStatus(block: AlertStatus.() -> AlertStatus) {
        val s = _status.value.block()
        _status.value = s
        write(statusFile, JSONObject()
            .put("lastAt", s.lastAt ?: JSONObject.NULL).put("lastCheck", s.lastCheck ?: JSONObject.NULL)
            .put("lastResult", s.lastResult ?: JSONObject.NULL).put("nextAt", s.nextAt ?: JSONObject.NULL)
            .put("nextCheck", s.nextCheck ?: JSONObject.NULL).toString())
    }

    private fun loadStatus(): AlertStatus = runCatching {
        val o = JSONObject(statusFile.readText())
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
        fun long(k: String) = if (o.isNull(k)) null else o.optLong(k)
        AlertStatus(long("lastAt"), str("lastCheck"), str("lastResult"), long("nextAt"), str("nextCheck"))
    }.getOrDefault(AlertStatus())

    private fun write(f: File, text: String) {
        runCatching {
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        }.onFailure { AppLog.e("Writing ${f.name} failed", it) }
    }

    private fun <T> read(f: File, parse: (JSONArray) -> List<T>): List<T> =
        runCatching { if (f.exists()) parse(JSONArray(f.readText())) else emptyList() }
            .onFailure { AppLog.e("Reading ${f.name} failed", it) }.getOrDefault(emptyList())

    companion object {
        private const val MAX = 300

        fun lessonJson(l: LessonEntry): JSONObject = JSONObject().put("at", l.at).put("ticker", l.ticker).put("why", l.why)
            .put("driver", l.driver).put("confidence", l.confidence).put("title", l.title).put("text", l.text)
            .put("watchFor", l.watchFor).put("costUsd", l.costUsd)

        fun lessonFrom(o: JSONObject): LessonEntry? = runCatching {
            LessonEntry(
                o.getLong("at"), o.getString("ticker"), o.getString("why"), o.optString("driver"), o.optString("confidence"),
                o.getString("title"), o.getString("text"), o.optString("watchFor"), o.optDouble("costUsd", 0.0),
            )
        }.getOrNull()
    }
}
