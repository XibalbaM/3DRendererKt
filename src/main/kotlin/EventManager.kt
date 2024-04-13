package fr.xibalba.renderer

import fr.xibalba.math.Vec2
import fr.xibalba.renderer.utils.getAllFunAnnotatedWith
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class EventManager {
    val eventListeners = mutableListOf<EventListeners<*>>()

    init {
        detectAnnotations()
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Event> subscribe(noinline listener: (T) -> Unit): Long {
        return subscribe(T::class, listener as (Event) -> Unit)
    }

    @Suppress("UNCHECKED_CAST")
    fun subscribe(event: KClass<out Event>, listener: (Event) -> Unit): Long {
        val index = eventListeners.indexOfFirst { it.event == event }
        if (index == -1) {
            eventListeners += EventListeners(event, mutableListOf(0L to listener))
            return 0
        } else {
            val eventListeners = eventListeners[index] as EventListeners<Event>
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
        eventListeners
            .filter { T::class.isSubclassOf(it.event) }
            .forEach { eventListener ->
                (eventListener as EventListeners<T>).listeners.forEach {
                    it.second(event)
                    if (event is CancellableEvent && event.cancelled) return false
                }
            }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun detectAnnotations() {
        val funs = getAllFunAnnotatedWith(EventListener::class)
        funs.forEach {
            val parameters = it.parameters
            if (parameters.size == 1) {
                val parameter = parameters[0]
                val type = parameter.type.classifier as KClass<*>
                if (type.isSubclassOf(Event::class)) {
                    subscribe(type as KClass<Event>, it::call)
                } else {
                    println("The parameter of the function ${it.name} must be a subclass of Event")
                }
            } else {
                println("The function ${it.name} must have only one parameter")
            }
        }
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

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class EventListener