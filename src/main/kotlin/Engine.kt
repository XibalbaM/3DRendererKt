import fr.xibalba.math.Vec2
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.system.Configuration.DEBUG
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*

abstract class Engine(
    private val size: Vec2<Int>,
    private val logLevel: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
) {

    private var window: Long? = null
    private var vulkan: VkInstance? = null
    private val validationLayers = if (DEBUG.get(true)) listOf("VK_LAYER_KHRONOS_validation") else emptyList()
    private var debugMessenger: Long? = null
    private var physicalDevice: VkPhysicalDevice? = null

    fun run() {
        try {
            initWindow()
            initVulkan()
            init(window!!)
            pLoop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            pCleanup()
        }
    }

    abstract fun init(window: Long)

    private fun initWindow() {

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)

        window = glfwCreateWindow(size.x, size.y, "Vulkan", NULL, NULL)
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
        createInstance()
        setupDebugMessenger()
        pickPhysicalDevice()
    }

    private fun createInstance() {
        if (!checkValidationLayerSupport()) {
            throw RuntimeException("Validation layers not available!")
        }
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
            val layers = stack.mallocPointer(validationLayers.size)
            validationLayers.forEachIndexed { index, layer ->
                layers.put(index, stack.UTF8(layer))
            }
            createInfo.ppEnabledLayerNames(layers)
            createInfo.ppEnabledExtensionNames(getRequiredExtensions(stack))

            if (DEBUG.get(true)) {
                createInfo.pNext(createDebugMessengerInfo(stack).address())
            } else {
                createInfo.pNext(NULL)
            }

            val instancePtr = stack.mallocPointer(1)
            if (vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create Vulkan instance")
            }
            vulkan = VkInstance(instancePtr.get(0), createInfo)
        }
    }

    private fun checkValidationLayerSupport(): Boolean {
        MemoryStack.stackPush().use { stack ->
            val layerCount = stack.ints(0)
            vkEnumerateInstanceLayerProperties(layerCount, null)
            val availableLayers = VkLayerProperties.malloc(layerCount[0], stack)
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers)
            val availableLayerNames = availableLayers.map { it.layerNameString() }
            return validationLayers.all { it in availableLayerNames }
        }
    }

    private fun getRequiredExtensions(stack: MemoryStack): PointerBuffer {
        val glfwExtensions = glfwGetRequiredInstanceExtensions() ?: throw RuntimeException("Failed to get GLFW extensions")
        return if (DEBUG.get(true)) {
            val extensions = MemoryStack.stackGet().mallocPointer(glfwExtensions.capacity() + 1)
            extensions.put(glfwExtensions)
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
            extensions.rewind()
        } else {
            glfwExtensions
        }
    }

    private fun setupDebugMessenger() {
        if (!DEBUG.get(true)) return

        MemoryStack.stackPush().use { stack ->
            val pDebugMessenger = stack.mallocLong(1)
            if (vkCreateDebugUtilsMessengerEXT(vulkan!!, createDebugMessengerInfo(stack), null, pDebugMessenger) != VK_SUCCESS) {
                throw RuntimeException("Failed to set up debug messenger")
            }
            debugMessenger = pDebugMessenger.get(0)
        }
    }

    private fun createDebugMessengerInfo(stack: MemoryStack): VkDebugUtilsMessengerCreateInfoEXT {
        val createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
        createInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        createInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
        createInfo.pfnUserCallback(this::debugCallback)
        createInfo.pUserData(NULL)
        return createInfo
    }

    open fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {
        if (messageSeverity >= logLevel) {
            val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
            val color = when (messageSeverity) {
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT -> "\u001b[37m"
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT -> "\u001b[36m"
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT -> "\u001b[33m"
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT -> "\u001b[31m"
                else -> "\u001b[0m"
            }
            val prefix = when (messageType) {
                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT -> "[GENERAL]"
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT -> "[VALIDATION]"
                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT -> "[PERFORMANCE]"
                else -> "[UNKNOWN]"
            }
            val message = "$color$prefix ${callbackData.pMessageString()}\u001b[0m"
            when (messageSeverity) {
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT -> System.err.println(message)
                else -> println(message)
            }
        }
        return VK_FALSE
    }

    private fun pickPhysicalDevice() {
        MemoryStack.stackPush().use { stack ->
            val deviceCount = stack.ints(0)
            vkEnumeratePhysicalDevices(vulkan!!, deviceCount, null)
            if (deviceCount[0] == 0) {
                throw RuntimeException("Failed to find GPUs with Vulkan support")
            }
            val ppPhysicalDevices = stack.mallocPointer(deviceCount[0])
            vkEnumeratePhysicalDevices(vulkan!!, deviceCount, ppPhysicalDevices)
            val devices = mutableMapOf<VkPhysicalDevice, Byte>()
            for (i in 0..<ppPhysicalDevices.capacity()) {
                val device = VkPhysicalDevice(ppPhysicalDevices[i], vulkan!!)
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(device, properties)
                val features = VkPhysicalDeviceFeatures.calloc(stack)
                vkGetPhysicalDeviceFeatures(device, features)
                val queueFamilies = findQueueFamilies(device)
                devices[device] = rateDeviceSuitability(device, properties, features, queueFamilies)
            }
            if (devices.none { it.value >= 1 })
                throw RuntimeException("Failed to find a suitable GPU")
            physicalDevice = devices.maxBy { it.value }.key
        }
    }

    open fun rateDeviceSuitability(device: VkPhysicalDevice, properties: VkPhysicalDeviceProperties, features: VkPhysicalDeviceFeatures, queueFamilies: Int?): Byte {
        return if (queueFamilies != null) 1 else 0
    }

    private fun findQueueFamilies(device: VkPhysicalDevice): Int? {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyCount = stack.ints(0)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)
            val queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)
            return (0..queueFamilies.capacity()).firstOrNull { (queueFamilies.get(it).queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0 }
        }
    }

    private fun pLoop() {
        while (!glfwWindowShouldClose(window!!)) {
            loop(window!!)

            glfwSwapBuffers(window!!)

            glfwPollEvents()
        }
    }

    abstract fun loop(window: Long)

    private fun pCleanup() {
        try {
            if (DEBUG.get(true)) {
                vkDestroyDebugUtilsMessengerEXT(vulkan!!, debugMessenger!!, null)
            }
            cleanup()
            vkDestroyInstance(vulkan!!, null)
            glfwDestroyWindow(window!!)
        } finally {
            glfwTerminate()
        }
    }

    abstract fun cleanup()
}