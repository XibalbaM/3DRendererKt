package fr.xibalba.renderer.utils

fun loadShader(name: String): ByteArray {
    return object {}.javaClass.getResource("/shaders/$name")!!.readBytes()
}