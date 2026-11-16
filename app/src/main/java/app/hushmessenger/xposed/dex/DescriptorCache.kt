package app.hushmessenger.xposed.dex

import android.util.Log
import java.io.File

/**
 * Descriptor cache keyed by Messenger's versionCode.
 *
 * The cache lives in Messenger's own data directory, because the hook process
 * runs as Messenger and can write there. RemotePreferences are read-only inside
 * a hooked app, so they cannot hold a cache built at runtime; they only carry
 * the user's switches and the rescan token written by the settings app.
 *
 * A Messenger update changes versionCode, so the new version starts with no
 * cache and the file for the old version is removed on the next save.
 *
 * `scanned` records which queries have been attempted, so a query that
 * legitimately finds nothing does not force a rescan on every cold boot.
 */
class DescriptorCache(dataDir: File?, versionCode: Long) {

    private val directory = dataDir?.let { File(it, "hush_xposed") }
    private val file = directory?.let { File(it, "descriptors-$versionCode.txt") }

    data class Entry(
        val resolved: Map<String, List<MethodRef>>,
        val scanned: Set<String>,
        val rescanToken: Long,
    )

    fun read(): Entry? {
        val target = file ?: return null
        val text = runCatching {
            if (target.isFile) target.readText() else null
        }.onFailure {
            Log.w(TAG, "could not read descriptor cache", it)
        }.getOrNull() ?: return null
        return decode(text)
    }

    fun write(
        resolved: Map<String, List<MethodRef>>,
        scanned: Set<String>,
        rescanToken: Long,
    ) {
        val dir = directory ?: return
        val target = file ?: return
        val temp = File(dir, target.name + "." + android.os.Process.myPid() + ".tmp")
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            prune(dir, target.name)
            temp.writeText(encode(resolved, scanned, rescanToken))
            // rename(2) replaces an existing target atomically on Android's
            // filesystems, so readers never observe a partial cache. The retry
            // covers filesystems that refuse to replace an existing file.
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    Log.w(TAG, "could not replace descriptor cache at ${target.absolutePath}")
                }
            }
        }.onFailure {
            Log.w(TAG, "could not write descriptor cache", it)
        }
        if (temp.exists()) temp.delete()
    }

    /** Keeps only the current version's cache; pid-suffixed temp files are cleaned up by their writer. */
    private fun prune(dir: File, keep: String) {
        dir.listFiles()?.forEach { old ->
            if (VERSIONED.matches(old.name) && old.name != keep) old.delete()
        }
    }

    companion object {
        private const val TAG = "HushXposed"
        private const val SCHEMA = "3"
        private val VERSIONED = Regex("""^descriptors-\d+\.txt$""")

        fun encode(
            resolved: Map<String, List<MethodRef>>,
            scanned: Set<String>,
            rescanToken: Long,
        ): String = buildString {
            append("schema=").append(SCHEMA).append('\n')
            append("rescan=").append(rescanToken).append('\n')
            append("scanned=").append(scanned.sorted().joinToString(",")).append('\n')
            resolved.forEach { (key, refs) ->
                if (refs.isEmpty()) return@forEach
                append(key).append('=').append(refs.joinToString(";") { it.encode() }).append('\n')
            }
        }

        fun decode(blob: String): Entry? {
            var schema: String? = null
            var rescan = 0L
            var scanned = emptySet<String>()
            val out = mutableMapOf<String, List<MethodRef>>()

            blob.lineSequence().forEach { line ->
                val at = line.indexOf('=')
                if (at <= 0) return@forEach
                val key = line.substring(0, at).trim()
                val value = line.substring(at + 1)
                when (key) {
                    "schema" -> schema = value.trim()
                    "rescan" -> rescan = value.trim().toLongOrNull() ?: 0L
                    "scanned" -> scanned = value.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    else -> {
                        val refs = value.split(';').mapNotNull { MethodRef.decode(it) }
                        if (refs.isNotEmpty()) out[key] = refs
                    }
                }
            }

            if (schema != SCHEMA) return null
            return Entry(out, scanned, rescan)
        }
    }
}
