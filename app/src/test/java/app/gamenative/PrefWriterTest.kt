package app.gamenative

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PrefWriterTest {
    private val size = intPreferencesKey("size")
    private val a = booleanPreferencesKey("a")
    private val b = booleanPreferencesKey("b")
    private lateinit var file: File
    private lateinit var writer: PrefWriter
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before fun open() {
        file = File.createTempFile("prefwriter", ".preferences_pb").apply { delete(); deleteOnExit() }
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
        writer = PrefWriter(store)
    }

    @After fun close() { file.delete() }

    private fun read() = runBlocking { store.data.first() }

    @Test fun theLastWriteOfAKeyWinsNoMatterHowFastTheyComeIn() {
        repeat(2000) { writer.set(size, it) }
        writer.flush()
        assertEquals(1999, read()[size])
    }

    @Test fun writesFromOneThreadKeepTheirOrderAcrossManyKeys() {
        repeat(500) { i -> writer.set(a, i % 2 == 0); writer.set(size, i) }
        writer.flush()
        assertEquals(499, read()[size])
        assertEquals(false, read()[a])   // the last i is 499 (odd): a = false
    }

    @Test fun aBatchIsWrittenTogetherInOrder() {
        writer.batch {
            writer.set(a, true); writer.set(b, true); writer.set(size, 1); writer.set(size, 2)
            assertNull("nothing is written before the batch ends", read()[size])
        }
        writer.flush()
        val prefs = read()
        assertEquals(true, prefs[a]); assertEquals(true, prefs[b]); assertEquals(2, prefs[size])
    }

    @Test fun aNestedBatchJoinsTheOuterOne() {
        writer.batch {
            writer.set(a, true)
            writer.batch { writer.set(b, true) }
            assertNull(read()[b])
        }
        writer.flush()
        assertEquals(true, read()[b])
    }

    @Test fun aBatchAfterASingleWriteStaysInOrder() {
        writer.set(size, 1)
        writer.batch { writer.set(size, 2) }
        writer.set(size, 3)
        writer.flush()
        assertEquals(3, read()[size])
    }
}
