package tech.mmarca.openvitals.core.performance

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * What a screen last loaded for a query, in memory for the life of the process, so a
 * screen reopened moments later draws at once instead of empty.
 *
 * A snapshot is never the answer. The screen shows it while it reads Health Connect as
 * it always does, and the live result replaces it. Nothing is written to disk.
 *
 * A snapshot is only handed back under the [fingerprint] it was read with, and saved only
 * when the fingerprint did not change during the read: a grant revoked since, a write of
 * this app's own or a rate limit that emptied the read never shows. Memory pressure
 * clears the store.
 */
class ScreenSnapshotStore(
    private val fingerprint: suspend () -> Any = { Unit },
    /** Reads that answered a fallback, or crossed a trip to the background, so far; one during a read keeps its result out. */
    private val fallbacks: () -> Long = { 0L },
    private val capacity: Int = DefaultCapacity,
) : ComponentCallbacks2 {

    /**
     * What a load read under: the fingerprint it looks up and saves with, the fallbacks so
     * far, and the clears so far: a trim during the read asked for the memory back.
     */
    data class Read(val fingerprint: Any, val fallbacks: Long, val clears: Long)

    private class Entry(val fingerprint: Any, val value: Any)

    private val entries = object : LinkedHashMap<ScreenSnapshotKey<*>, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ScreenSnapshotKey<*>, Entry>?): Boolean =
            size > capacity
    }

    /**
     * Read once per load, before the reads: what [get] and [put] are given. A fingerprint
     * that cannot be read matches nothing, so nothing is drawn or saved under it.
     */
    suspend fun fingerprint(): Read {
        // The counters first: anything during the fingerprint's own IPC counts against the read.
        val fallbacksSoFar = fallbacks()
        val clearsSoFar = synchronized(entries) { clears }
        return Read(runCatchingCancellable { fingerprint.invoke() }.getOrElse { Unreadable }, fallbacksSoFar, clearsSoFar)
    }

    /** [key]'s snapshot, if it was read under [read]'s fingerprint; one read under another is dropped. */
    @Suppress("UNCHECKED_CAST")
    fun <V : Any> get(key: ScreenSnapshotKey<V>, read: Read): V? = synchronized(entries) {
        if (read.fingerprint === Unreadable) return null
        val entry = entries[key] ?: return null
        if (entry.fingerprint != read.fingerprint) {
            entries.remove(key)
            return null
        }
        entry.value as V
    }

    /**
     * Saves [value] unless the fingerprint moved during its read, or a read anywhere fell
     * back meanwhile: an emptied answer is not kept, and neither is the older one.
     */
    suspend fun <V : Any> put(key: ScreenSnapshotKey<V>, read: Read, value: V) {
        // Declined, the older snapshot goes too: this read is newer than it.
        if (read.fingerprint === Unreadable || fallbacks() != read.fallbacks || fingerprint() != read) return remove(key)
        synchronized(entries) {
            // A trim during the check above asked for the memory back too.
            if (clears == read.clears) entries[key] = Entry(read.fingerprint, value)
        }
    }

    /** Drops [key]'s snapshot, after a read of it failed or could not be kept. */
    fun remove(key: ScreenSnapshotKey<*>) {
        synchronized(entries) { entries.remove(key) }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            clears++
        }
    }

    private var clears = 0L

    override fun onTrimMemory(level: Int) {
        // Not on UI_HIDDEN, so a glance at another app keeps them until the process is cached.
        // Android 14 and later send only UI_HIDDEN and BACKGROUND.
        val runningLow = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (runningLow || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clear()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = clear()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private companion object {
        /** A few periods of two screens. A year of workouts with routes is not small. */
        const val DefaultCapacity = 6

        /** The mark of a fingerprint that could not be read: nothing is drawn or saved under it, and get keeps the entry. */
        val Unreadable = Any()
    }
}

/**
 * The query a snapshot answers, typed by what it holds. One [screen] always holds one type,
 * and [query] carries everything the result depends on besides the fingerprint.
 */
data class ScreenSnapshotKey<V : Any>(val screen: String, val query: Any)
