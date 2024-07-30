package fr.xibalba.renderer

import fr.xibalba.math.*
import fr.xibalba.renderer.events.EngineEvents
import fr.xibalba.renderer.events.KeyboardEvent
import fr.xibalba.renderer.events.MouseEvents
import fr.xibalba.renderer.utils.lookAt
import fr.xibalba.renderer.utils.rotateY
import fr.xibalba.renderer.utils.rotateZ
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose

object Viewport {
    private val camera = Camera(Vec3(-2f, 0f, 2f), Vec2(0f, pi/4))

    fun run(size: Vec2<Int>) {
        Engine.run(size, listOf("viking_room"))
    }

    fun setLogLevel(logLevel: Int): Viewport {
        Engine.logLevel = logLevel
        return this
    }

    @EventListener
    fun addCameraToUBO(event: EngineEvents.CreateUniformBufferObject) {
        event.uniformBufferObject.view = this.camera.getViewMatrix()
    }

    @EventListener
    fun moveCamera(event: EngineEvents.Tick) {
        val speed = 0.001f
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_W)) {
            this.camera.move(this.camera.viewVector() * speed)
        }
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_S)) {
            this.camera.move(this.camera.viewVector() * -speed)
        }
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_A)) {
            val right = this.camera.viewVector().cross(Vec3(0f, 0f, 1f))
            this.camera.move(right * -speed)
        }
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_D)) {
            val right = this.camera.viewVector().cross(Vec3(0f, 0f, 1f))
            this.camera.move(right * speed)
        }
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_SPACE)) {
            this.camera.move(Vec3(0f, 0f, speed))
        }
        if (KeyboardManager.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) {
            this.camera.move(Vec3(0f, 0f, -speed))
        }
    }

    @EventListener
    fun closeWindow(event: KeyboardEvent.KeyPressedEvent) {
        if (event.keyCode == GLFW.GLFW_KEY_ESCAPE) {
            glfwSetWindowShouldClose(Engine.window!!, true)
        }
    }

    @EventListener
    fun rotateCamera(event: MouseEvents.Moved) {
        val speed = 0.01f
        val (oldX, oldY) = event.oldPosition
        val (newX, newY) = event.newPosition
        val dx = (newX - oldX).toFloat()
        val dy = (newY - oldY).toFloat()
        this.camera.rotate(Vec2(-dx * speed, dy * speed))
    }

    @EventListener
    fun init(event: EngineEvents.AfterInit) {
        Engine.setCursor(GLFW.GLFW_CURSOR_DISABLED)
    }
}

class Camera(var position: Vec3f, var rotation: Vec2f) {
    fun move(direction: Vec3f) {
        this.position += direction
    }

    fun rotate(direction: Vec2f) {
        if (this.rotation.y + direction.y > pi / 2) {
            this.rotation = Vec2((this.rotation.x + direction.x) % (2 * pi), pi / 2)
        } else if (this.rotation.y + direction.y < -pi / 2) {
            this.rotation = Vec2((this.rotation.x + direction.x) % (2 * pi), -pi / 2)
        } else {
            this.rotation += Vec2(direction.x, direction.y)
            this.rotation = Vec2(this.rotation.x % (2 * pi), this.rotation.y)
        }
    }

    fun getViewMatrix(): SquareMatrix<Float> {
        val eye = this.position
        val center =  Vec4(this.viewVector(), 1f) + Vec4(eye.x, eye.y, eye.z, 0f)
        val up = Vec3(0f, 0f, 1f)
        return lookAt(eye, Vec3(center[0, 0], center[1,0], center[2, 0]), up)
    }

    fun viewVector(): Vec3f {
        return (rotateZ(this.rotation.x) * rotateY(this.rotation.y) * Vec4(1f, 0f, 0f, 1f)).let { Vec3(it[0, 0], it[1, 0], it[2, 0]) }
    }
}