import fr.xibalba.math.Vec2
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo

class Engine(private val size: Vec2<Int>, private val initFun: (window: Long) -> Unit, private val loopFun: (window: Long) -> Unit, private val cleanupFun: () -> Unit) {

    var window: Long? = null
    var vulkan: VkInstance? = null

    private fun initWindow() {

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        window = glfwCreateWindow(size.x, size.y, "Hello World!", NULL, NULL)
        if (window == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor()) ?: throw RuntimeException("Failed to get the video mode")

        glfwSetWindowPos(
            window!!,
            (vidmode.width() - size.x) / 2,
            (vidmode.height() - size.y) / 2
        )

        glfwMakeContextCurrent(window!!)
        glfwSwapInterval(1)

        glfwShowWindow(window!!)

    }

    private fun initVulkan() {
        MemoryStack.stackPush().use { stack ->
            val appInfo = VkApplicationInfo.calloc(stack)

            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            appInfo.pApplicationName(stack.UTF8("Hello Triangle"))
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0))
            appInfo.pEngineName(stack.UTF8("No Engine"))
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0))
            appInfo.apiVersion(VK_API_VERSION_1_0)

            val createInfo = VkInstanceCreateInfo.calloc(stack)

            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            createInfo.pApplicationInfo(appInfo)
            createInfo.ppEnabledExtensionNames(glfwGetRequiredInstanceExtensions())
            createInfo.ppEnabledLayerNames(null)

            val instancePtr = stack.mallocPointer(1)

            if (vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create Vulkan instance")
            }

            vulkan = VkInstance(instancePtr.get(0), createInfo)
        }
    }

    private fun loop() {

        while (!glfwWindowShouldClose(window!!)) {
            loopFun(window!!)

            glfwSwapBuffers(window!!)

            glfwPollEvents()
        }
    }

    fun run() {
        try {
            initWindow()
            initVulkan()
            initFun(window!!)
            loop()
            cleanupFun()
            vkDestroyInstance(vulkan!!, null)
            glfwDestroyWindow(window!!)
        } finally {
            glfwTerminate()
        }
    }
}