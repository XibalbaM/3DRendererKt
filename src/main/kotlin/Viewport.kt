package fr.xibalba.renderer

import fr.xibalba.math.*
import fr.xibalba.renderer.events.EngineEvents
import fr.xibalba.renderer.utils.lookAt

object Viewport {
    private val camera = Camera(vec3(0f, 0f, 3f), vec3(0f, 0f, 0f))

    fun run(size: Vec2<Int>) {
        Engine.run(size)
    }

    fun setLogLevel(logLevel: Int): Viewport {
        Engine.logLevel = logLevel
        return this
    }

    @EventListener
    fun addCameraToUBO(event: EngineEvents.CreateUniformBufferObject) {
        event.uniformBufferObject.view = camera.getViewMatrix()
    }
}

class Camera(var position: Vec3f, var rotation: Vec3f) {
    fun move(direction: Vec3f) {
        position += direction
    }

    fun rotate(direction: Vec3f) {
        rotation += direction
    }

    fun getViewMatrix(): SquareMatrix<Float> {
        val eye = position
        val center = position + rotation
        val up = vec3(0f, 1f, 0f)
        return lookAt(eye, center, up)
    }
}