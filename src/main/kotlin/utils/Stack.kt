package utils

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.Pointer
import java.nio.IntBuffer
import java.nio.LongBuffer

fun List<String>.stringToPointerBuffer(stack: MemoryStack): PointerBuffer {
    val layers = stack.mallocPointer(size)
    forEachIndexed { index, layer ->
        layers.put(index, stack.UTF8(layer))
    }
    return layers.rewind()
}

fun <T : Pointer> List<T>.toPointerBuffer(stack: MemoryStack): PointerBuffer {
    val layers = stack.mallocPointer(size)
    forEachIndexed { index, layer ->
        layers.put(index, layer)
    }
    return layers.rewind()
}

fun IntBuffer.toList(): List<Int> {
    val list = mutableListOf<Int>()
    while (hasRemaining()) {
        list.add(get())
    }
    return list
}

fun LongBuffer.toList(): List<Long> {
    val list = mutableListOf<Long>()
    while (hasRemaining()) {
        list.add(get())
    }
    return list
}