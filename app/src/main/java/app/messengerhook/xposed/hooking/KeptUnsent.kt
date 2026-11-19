package app.messengerhook.xposed.hooking

import android.util.Log
import java.io.File

/**
 * Message ids whose unsend was intercepted for the current Messenger build.
 *
 * The hook process runs as Messenger, so the store lives in Messenger's own
 * files directory rather than in RemotePreferences (which are read-only in a
 * hooked app). One id per line, oldest first, bounded like the patch's own
 * `kept_unsent_ids` set.
 *
 * Messenger runs notification handling in more than one process. Every write
 * rewrites the small file, and [refresh] reloads it when the file changed, so
 * an id recorded by the notification process is visible to the UI process.
 * Reads use an immutable snapshot so the hot row getters never take a lock;
 * only the write path and the reload path share the set lock.
 */
class KeptUnsent(dataDir: File?) {

    private val file = dataDir?.let { File(File(it, "messenger_hook"), "kept_unsent.txt") }
    private val lock = Any()
    private val ordered = LinkedHashSet<String>()

    @Volatile
    private var snapshot: Set<String> = emptySet()

    @Volatile
    private var loadedStamp: Long = Long.MIN_VALUE

    @Volatile
    private var checkedAt: Long = 0L

    init {
        load()
    }

    fun contains(id: String?): Boolean = id != null && snapshot.contains(id)

    val isEmpty: Boolean get() = snapshot.isEmpty()

    /**
     * Reloads the file if another Messenger process has written it since the
     * last read. The row getters call this on every bind, so it stats the file
     * at most once a second: a same-process record updates the snapshot at
     * once, and a cross-process record is visible within that second.
     */
    fun refresh() {
        val target = file ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - checkedAt < REFRESH_MILLIS) return
        synchronized(lock) {
            checkedAt = now
            val stamp = stampOf(target)
            if (stamp == 0L || stamp == loadedStamp) return
            load()
        }
    }

    fun record(id: String?) {
        if (id.isNullOrEmpty()) return
        synchronized(lock) {
            if (!ordered.add(id)) return
            trim()
            snapshot = HashSet(ordered)
            persist()
        }
    }

    private fun load() {
        val target = file ?: return
        synchronized(lock) {
            ordered.clear()
            runCatching {
                if (!target.isFile) {
                    snapshot = emptySet()
                    loadedStamp = 0L
                    return
                }
                target.useLines { lines ->
                    lines.forEach { line ->
                        val id = line.trim()
                        if (id.isNotEmpty()) ordered.add(id)
                    }
                }
                trim()
                snapshot = HashSet(ordered)
                loadedStamp = stampOf(target)
            }.onFailure {
                Log.w(TAG, "could not read kept-unsent ids", it)
            }
        }
    }

    private fun trim() {
        while (ordered.size > LIMIT) {
            val eldest = ordered.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private fun persist() {
        val target = file ?: return
        val dir = target.parentFile ?: return
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temp = File(dir, target.name + "." + android.os.Process.myPid() + ".tmp")
            temp.writeText(ordered.joinToString("\n"))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    Log.w(TAG, "could not replace kept-unsent ids at ${target.absolutePath}")
                }
            }
            if (temp.exists()) temp.delete()
            loadedStamp = stampOf(target)
        }.onFailure {
            Log.w(TAG, "could not write kept-unsent ids", it)
        }
    }

    private fun stampOf(target: File): Long = runCatching {
        target.lastModified() * 31 + target.length()
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "MessengerHook"
        private const val LIMIT = 4096
        private const val REFRESH_MILLIS = 1000L
    }
}
