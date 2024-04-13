package fr.xibalba.renderer

import fr.xibalba.renderer.events.KeyboardEvent.*
import org.lwjgl.glfw.GLFW.*

object KeyboardManager {

    private val keyStates = BooleanArray(512)

    fun isKeyDown(key: Int) = keyStates[key]

    fun keyCallback(window: Long, key: Int, scancode: Int, action: Int, mods: Int) {
        if (action == GLFW_PRESS) {
            keyStates[key] = true
            EventManager.fire(KeyPressedEvent(this, key))
        } else if (action == GLFW_RELEASE) {
            keyStates[key] = false
            EventManager.fire(KeyReleasedEvent(this, key))
        }
    }
}