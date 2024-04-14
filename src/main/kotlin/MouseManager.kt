package fr.xibalba.renderer

import fr.xibalba.math.Vec2
import fr.xibalba.renderer.events.MouseEvents

object MouseManager {

    var mousePosition = Vec2(0.0, 0.0)

    fun mouseMoveCallback(window: Long, xpos: Double, ypos: Double) {
        mousePosition = Vec2(xpos, ypos)
        EventManager.fire(MouseEvents.Moved(mousePosition, Vec2(xpos, ypos)))
    }

    fun mouseButtonCallback(window: Long, button: Int, action: Int, mods: Int) {
        EventManager.fire(MouseEvents.Pressed(button, action, mods))
    }

    fun mouseScrollCallback(window: Long, xoffset: Double, yoffset: Double) {
        EventManager.fire(MouseEvents.Scrolled(xoffset, yoffset))
    }
}