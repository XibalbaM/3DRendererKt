import fr.xibalba.math.Vec2
import org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT

class Viewport(size: Vec2<Int>, showFps: Boolean = false, debugSeverity: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) {
    private val engine = ViewportEngine(size, showFps, debugSeverity)

    fun run() {
        engine.run()
    }
}

class ViewportEngine(size: Vec2<Int>, showFps: Boolean, debugSeverity: Int) : Engine(size, showFps, debugSeverity) {
    override fun init() {

    }

    override fun loop() {

    }

    override fun cleanup() {

    }
}