package utils

fun loadShader(name: String): ByteArray {
    return object {}.javaClass.getResource("/shaders/$name")!!.readBytes()
}