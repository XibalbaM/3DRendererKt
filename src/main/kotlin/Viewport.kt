package fr.xibalba.renderer

import fr.xibalba.math.*
import org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT

class Viewport(size: Vec2<Int>, showFps: Boolean = false, debugSeverity: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) {
    private val engine = Engine(size, showFps, debugSeverity)

    fun run() {
        engine.run()
    }
}