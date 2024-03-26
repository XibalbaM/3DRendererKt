package utils

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import java.nio.IntBuffer

fun List<String>.toPointerBuffer(stack: MemoryStack): PointerBuffer {
    val layers = stack.mallocPointer(size)
    forEachIndexed { index, layer ->
        layers.put(index, stack.UTF8(layer))
    }
    return layers
}

fun IntBuffer.toList(): List<Int> {
    val list = mutableListOf<Int>()
    while (hasRemaining()) {
        list.add(get())
    }
    return list
}