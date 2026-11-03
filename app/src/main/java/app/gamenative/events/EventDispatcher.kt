package app.gamenative.events

import kotlin.reflect.KClass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// written with the help of Claude 3.5
sealed interface Event<T>

class EventDispatcher {
    val listeners = ConcurrentHashMap<KClass<out Event<*>>, CopyOnWriteArrayList<Pair<Any, EventListener<Event<*>, *>>>>()

    open class EventListener<E : Event<T>, T>(
        val listener: (E) -> T,
        val once: Boolean = false,
    )

    inline fun <reified E : Event<T>, T> on(noinline listener: (E) -> T) {
        addListener<E, T>(listener, false)
    }

    inline fun <reified E : Event<T>, T> once(noinline listener: (E) -> T) {
        addListener<E, T>(listener, true)
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Event<T>, T> addListener(
        noinline listener: (E) -> T,
        once: Boolean,
    ) {
        val eventClass = E::class
        val typedListener = Pair(
            listener,
            EventListener<Event<T>, T>({ event ->

                listener(event as E)
            }, once),
        )

        listeners.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }.add(typedListener as Pair<Any, EventListener<Event<*>, *>>)
    }

    inline fun <reified E : Event<T>, T> off(noinline listener: (E) -> T) {
        val eventClass = E::class
        listeners[eventClass]?.removeIf {

            it.first === listener
        }
    }

    inline fun <reified E : Event<*>> clearAllListenersOf() {
        listeners.remove(E::class)
    }

    inline fun <reified E : Event<T>, reified T> emit(event: E, noinline resultAggregator: ((Array<T>) -> T)? = null): T? {
        val eventClass = E::class

        return listeners[eventClass]?.let { eventListeners ->
            // CopyOnWriteArrayList iterators are snapshots; high-rate input needs no result array.
            if (resultAggregator == null) {
                eventListeners.forEach { item ->
                    if (!item.second.once || eventListeners.remove(item)) item.second.listener(event)
                }
                return@let null
            }
            val results = eventListeners.filter { !it.second.once || eventListeners.remove(it) }.map { eventListener ->
                eventListener.second.listener(event) as T
            }.toTypedArray()
            resultAggregator?.let { it(results) }
        }
    }

    /**
     * For the per-input-event hot path (key and motion events): every listener is called exactly like [emit] with an aggregator
     * would (once-listeners included), but nothing is collected: no filtered list, no mapped list, no result array.
     * True when at least one listener returned true.
     */
    inline fun <reified E : Event<Boolean>> emitAny(event: E): Boolean {
        val eventListeners = listeners[E::class] ?: return false
        var any = false
        eventListeners.forEach { item ->
            if (!item.second.once || eventListeners.remove(item)) {
                if (item.second.listener(event) as Boolean) any = true
            }
        }
        return any
    }

    // Java-friendly version that doesn't use reified generics
    @Suppress("UNCHECKED_CAST")
    fun emitJava(event: Event<*>): Any? {
        val eventClass = event::class
        return listeners[eventClass]?.let { eventListeners ->
            val results = eventListeners.filter { !it.second.once || eventListeners.remove(it) }.map { eventListener ->
                eventListener.second.listener(event)
            }.toTypedArray()
            results.firstOrNull()
        }
    }
}
