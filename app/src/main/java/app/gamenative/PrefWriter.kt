package app.gamenative

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Writes preferences in call order. Every write goes through one FIFO queue that a single coroutine drains, finishing one DataStore
 * transaction before it starts the next: with a launch per setter on a shared pool, two quick changes of the same key could reach the
 * DataStore in either order and the older value could win. [batch] collects the setters of one profile (e.g. the whole HUD
 * configuration) into a single DataStore transaction.
 */
internal class PrefWriter(private val store: DataStore<Preferences>) {
    private sealed interface Op {
        class Write(val edits: List<(MutablePreferences) -> Unit>) : Op
        class Barrier(val reached: CompletableDeferred<Unit>) : Op
    }

    private val queue = Channel<Op>(Channel.UNLIMITED)
    private val collector = ThreadLocal<MutableList<(MutablePreferences) -> Unit>?>()

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            for (op in queue) {
                when (op) {
                    is Op.Write -> try {
                        store.edit { prefs -> op.edits.forEach { it(prefs) } }
                    } catch (_: Exception) {
                        // a failed write must not stop the writes queued behind it
                    }
                    is Op.Barrier -> op.reached.complete(Unit)
                }
            }
        }
    }

    fun <T> set(key: Preferences.Key<T>, value: T) {
        val edit: (MutablePreferences) -> Unit = { it[key] = value }
        val pending = collector.get()
        if (pending != null) pending.add(edit) else queue.trySend(Op.Write(listOf(edit)))
    }

    /** Everything set inside [block] on this thread is written as one transaction, in the order it was set. */
    fun batch(block: () -> Unit) {
        if (collector.get() != null) { block(); return }          // nested: the outer batch owns the transaction
        val edits = ArrayList<(MutablePreferences) -> Unit>()
        collector.set(edits)
        try { block() } finally { collector.set(null) }
        if (edits.isNotEmpty()) queue.trySend(Op.Write(edits))
    }

    /** Returns once every write requested before this call has reached the store (tests, shutdown). */
    fun flush() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        queue.trySend(Op.Barrier(reached))
        reached.await()
    }
}
