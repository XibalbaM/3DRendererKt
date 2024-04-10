import fr.xibalba.math.Vec2
import kotlin.reflect.KClass

class EventManager {
    val eventListeners = mutableListOf<EventListeners<*>>()

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Event> subscribe(noinline listener: (T) -> Unit): Long {
        val index = eventListeners.indexOfFirst { it.event == T::class }
        if (index == -1) {
            eventListeners += EventListeners(T::class, mutableListOf(0L to listener))
            return 0
        } else {
            val eventListeners = eventListeners[index] as EventListeners<T>
            val id = eventListeners.listeners.maxOf { it.first } + 1
            eventListeners.listeners.addLast(id to listener)
            return id
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun unsubscribe(event: KClass<out Event>, id: Long) {
        val index = eventListeners.indexOfFirst { it.event == event }
        if (index != -1) {
            val eventListeners = eventListeners[index] as EventListeners<Event>
            eventListeners.listeners.removeIf { it.first == id }
        }
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Event> fire(event: T): Boolean {
        val index = eventListeners.indexOfFirst { it.event == T::class }
        if (index != -1) {
            val eventListeners = eventListeners[index] as EventListeners<T>
            eventListeners.listeners.forEach {
                it.second(event)
                if (event is CancellableEvent && event.cancelled) return false
            }
        }
        return true
    }
}

data class EventListeners<T : Event>(val event: KClass<T>, val listeners: MutableList<Pair<Long, (T) -> Unit>>)

open class Event

open class CancellableEvent : Event() {
    var cancelled = false
    fun cancel() {
        cancelled = true
    }
}

open class WindowEvent : Event() {
    class Resize(val oldSize: Vec2<Int>, val newSize: Vec2<Int>) : WindowEvent()
    class Close : WindowEvent()
}