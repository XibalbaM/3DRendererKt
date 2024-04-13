package fr.xibalba.renderer

import fr.xibalba.math.*

object Viewport {
    private val camera = Camera(vec3(0f, 0f, 3f), vec3(0f, 0f, 0f))

    fun run(size: Vec2<Int>) {
        Engine.run(size)
    }

    fun setLogLevel(logLevel: Int): Viewport {
        Engine.logLevel = logLevel
        return this
    }
}