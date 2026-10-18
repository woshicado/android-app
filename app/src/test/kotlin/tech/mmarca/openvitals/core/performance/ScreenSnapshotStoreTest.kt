package tech.mmarca.openvitals.core.performance

import android.content.ComponentCallbacks2
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenSnapshotStoreTest {
    private val week = ScreenSnapshotKey<String>("activities", "week")
    private val month = ScreenSnapshotKey<String>("activities", "month")
    private val steps = ScreenSnapshotKey<String>("activity", "week")

    @Test
    fun `a snapshot comes back only under the fingerprint it was saved with`() = runTest {
        var grants = "granted"
        val store = ScreenSnapshotStore(fingerprint = { grants })
        store.put(week, store.fingerprint(), "walk")

        assertEquals("walk", store.get(week, store.fingerprint()))
        assertNull(store.get(month, store.fingerprint()))
        grants = "revoked"
        assertNull(store.get(week, store.fingerprint()))
        grants = "granted"
        // Dropped, not kept for a fingerprint that may come back.
        assertNull(store.get(week, store.fingerprint()))
    }

    // A write of this app's own moves the fingerprint; a rate limit or a failure counts a fallback.
    @Test
    fun `a read whose fingerprint moved, or that saw a fallback, is not saved`() = runTest {
        var writes = 0
        var fallbacks = 0L
        val store = ScreenSnapshotStore(fingerprint = { writes }, fallbacks = { fallbacks })

        val beforeWrite = store.fingerprint()
        writes++
        store.put(week, beforeWrite, "walk")
        assertNull(store.get(week, store.fingerprint()))

        val beforeFallback = store.fingerprint()
        fallbacks++
        store.put(week, beforeFallback, "walk")
        assertNull(store.get(week, store.fingerprint()))
    }

    // A fallback in another read says nothing about a snapshot already saved.
    @Test
    fun `a fallback after the save does not hide the snapshot`() = runTest {
        var fallbacks = 0L
        val store = ScreenSnapshotStore(fallbacks = { fallbacks })
        store.put(week, store.fingerprint(), "walk")

        fallbacks++

        assertEquals("walk", store.get(week, store.fingerprint()))
    }

    @Test
    fun `a fingerprint that cannot be read draws and saves nothing`() = runTest {
        var broken = false
        val store = ScreenSnapshotStore(fingerprint = { check(!broken) { "PackageManager" }; "granted" })
        store.put(week, store.fingerprint(), "walk")
        broken = true

        val read = store.fingerprint()

        assertNull(store.get(week, read))
        store.put(month, read, "run")
        broken = false
        assertNull(store.get(month, store.fingerprint()))
    }

    @Test
    fun `a read during which memory ran low is not saved`() = runTest {
        val store = ScreenSnapshotStore()
        val read = store.fingerprint()

        store.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        store.put(week, read, "walk")

        assertNull(store.get(week, store.fingerprint()))
    }

    @Test
    fun `past capacity the least recently read snapshot goes`() = runTest {
        val store = ScreenSnapshotStore(capacity = 2)
        val read = store.fingerprint()
        store.put(week, read, "week")
        store.put(month, read, "month")
        store.get(week, read)

        store.put(steps, read, "steps")

        assertEquals("week", store.get(week, read))
        assertNull(store.get(month, read))
        assertEquals("steps", store.get(steps, read))
    }

    @Test
    fun `memory pressure clears the store, leaving the app does not`() = runTest {
        val store = ScreenSnapshotStore()
        val read = store.fingerprint()
        store.put(week, read, "week")

        store.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals("week", store.get(week, read))

        store.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull(store.get(week, read))

        val again = store.fingerprint()
        store.put(week, again, "week")
        assertEquals("week", store.get(week, again))
        store.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertNull(store.get(week, again))
    }
}
