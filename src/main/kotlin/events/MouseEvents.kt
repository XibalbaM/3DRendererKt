package fr.xibalba.renderer.events

import fr.xibalba.math.Vec2
import fr.xibalba.renderer.Event

open class MouseEvents : Event() {
    class Moved(val oldPosition: Vec2<Double>, val newPosition: Vec2<Double>) : MouseEvents()
    class Pressed(val button: Int, val action: Int, val mods: Int) : MouseEvents()
    class Scrolled(val xOffset: Double, val yOffset: Double) : MouseEvents()
}