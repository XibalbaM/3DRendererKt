import fr.xibalba.math.*
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.Configuration.DEBUG
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import utils.*
import java.nio.IntBuffer
import java.nio.LongBuffer


class Engine(private val defaultSize: Vec2<Int>, private val showFPS: Boolean = false, private val logLevel: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {

    companion object {
        private const val MAX_FRAMES_IN_FLIGHT = 2
    }

    val eventManager = EventManager()
    val keyboardManager = KeyboardManager(eventManager)

    private var window: Long? = null

    private var vulkan: VkInstance? = null
    private val validationLayers = if (DEBUG.get(true)) listOf("VK_LAYER_KHRONOS_validation") else emptyList()
    private val deviceExtensions = listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
    private var debugMessenger: Long? = null
    private var surface: Long? = null

    private var physicalDevice: VkPhysicalDevice? = null
    private var logicalDevice: VkDevice? = null

    private var graphicsQueue: VkQueue? = null
    private var presentQueue: VkQueue? = null
    private var transferQueue: VkQueue? = null

    private var swapChain: Long? = null
    private var swapChainImages: List<Long>? = null
    private var swapChainImageFormat: Int? = null
    private var swapChainExtent: VkExtent2D? = null
    private var swapChainImageViews: List<Long>? = null
    private var swapChainFramebuffers: List<Long>? = null

    private var renderPass: Long? = null
    private var descriptorSetLayout: Long? = null
    private var descriptorPool: Long? = null
    private var descriptorSets: List<Long>? = null
    private var pipelineLayout: Long? = null
    private var graphicsPipeline: Long? = null

    private var graphicCommandPool: Long? = null
    private var transferCommandPool: Long? = null
    private var commandBuffers: List<VkCommandBuffer>? = null

    private var frames = List(MAX_FRAMES_IN_FLIGHT) { Frame() }
    private var currentFrame = 0

    private var framebufferResized = false

    private var vertexBuffer: Long? = null
    private var vertexBufferMemory: Long? = null
    private var indexBuffer: Long? = null
    private var indexBufferMemory: Long? = null
    private var vertices = listOf(
        Vertex(Vec3(-0.5f, -0.5f, 0.0f), Vec3(1.0f, 0.0f, 0.0f), Vec2(1.0f, 0.0f)),
        Vertex(Vec3(0.5f, -0.5f, 0.0f), Vec3(0.0f, 1.0f, 0.0f), Vec2(0.0f, 0.0f)),
        Vertex(Vec3(0.5f, 0.5f, 0.0f), Vec3(0.0f, 0.0f, 1.0f), Vec2(0.0f, 1.0f)),
        Vertex(Vec3(-0.5f, 0.5f, 0.0f), Vec3(1.0f, 1.0f, 1.0f), Vec2(1.0f, 1.0f)),

        Vertex(Vec3(-0.5f, -0.5f, -0.5f), Vec3(1.0f, 0.0f, 0.0f), Vec2(1.0f, 0.0f)),
        Vertex(Vec3(0.5f, -0.5f, -0.5f), Vec3(0.0f, 1.0f, 0.0f), Vec2(0.0f, 0.0f)),
        Vertex(Vec3(0.5f, 0.5f, -0.5f), Vec3(0.0f, 0.0f, 1.0f), Vec2(0.0f, 1.0f)),
        Vertex(Vec3(-0.5f, 0.5f, -0.5f), Vec3(1.0f, 1.0f, 1.0f), Vec2(1.0f, 1.0f))
    )
    private var triangles = listOf(
        vec3(0, 1, 2), vec3(2, 3, 0),
        vec3(4, 5, 6), vec3(6, 7, 4)
    )

    private var uniformBuffers: List<Long>? = null
    private var uniformBuffersMemory: List<Long>? = null

    private var textureImage: Long? = null
    private var textureImageMemory: Long? = null
    private var textureImageView: Long? = null
    private var textureSampler: Long? = null

    private var depthImage: Long? = null
    private var depthImageMemory: Long? = null
    private var depthImageView: Long? = null

    fun run() {
        try {
            initWindow()
            initVulkan()
            loop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cleanup()
        }
    }

    private fun initWindow() {

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        window = glfwCreateWindow(defaultSize.x, defaultSize.y, "Vulkan", NULL, NULL)
        if (window == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor()) ?: throw RuntimeException("Failed to get the video mode")

        glfwSetWindowPos(
            window!!,
            (vidmode.width() - defaultSize.x) / 2,
            (vidmode.height() - defaultSize.y) / 2
        )

        glfwSetFramebufferSizeCallback(window!!, this::framebufferResizeCallback)
        glfwSetKeyCallback(window!!, keyboardManager::keyCallback)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun framebufferResizeCallback(window: Long, width: Int, height: Int) {
        eventManager.fire(WindowEvent.Resize(vec2(swapChainExtent!!.width(), swapChainExtent!!.height()), vec2(width, height)))
        framebufferResized = true
    }

    private fun initVulkan() {
        createInstance()
        setupDebugMessenger()
        createSurface()
        pickPhysicalDevice()
        createLogicalDevice()
        createCommandPools()
        createTextureImage()
        createTextureImageView()
        createTextureSampler()
        createSwapChainObjects()
        createVertexBuffer()
        createIndexBuffer()
        createSyncObjects()
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
            createInfo.ppEnabledLayerNames(validationLayers.stringToPointerBuffer(stack))
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
        return createInfo
    }

    private fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {
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

    private fun rateDeviceSuitability(device: VkPhysicalDevice, properties: VkPhysicalDeviceProperties, features: VkPhysicalDeviceFeatures, queueFamilies: QueueFamilyIndices): Byte {
        MemoryStack.stackPush().use { stack ->
            val extensionCount = stack.ints(0)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, null)
            val availableExtensions = VkExtensionProperties.malloc(extensionCount[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)
            val availableExtensionsNames = availableExtensions.map { it.extensionNameString() }
            if (
                !queueFamilies.isComplete ||
                !deviceExtensions.all { it in availableExtensionsNames } ||
                querySwapChainSupport(stack, device).let { it.formats!!.capacity() == 0 || it.presentModes!!.capacity() == 0} ||
                !features.samplerAnisotropy()
            ) {
                return 0
            }
            return 1
        }
    }

    private fun findQueueFamilies(device: VkPhysicalDevice): QueueFamilyIndices {
        val indices = QueueFamilyIndices()
        MemoryStack.stackPush().use { stack ->
            val queueFamilyCount = stack.ints(0)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)
            val queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)
            val presentSupport = stack.mallocInt(1)
            for (i in 0..<queueFamilies.capacity()) {
                if (queueFamilies[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0) {
                    indices.graphicsFamily = i
                } else if (queueFamilies[i].queueFlags() and VK_QUEUE_TRANSFER_BIT != 0) {
                    indices.transferFamily = i
                }
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface!!, presentSupport)
                if (presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i
                }
                if (indices.isComplete) break
            }
            if (indices.transferFamily == null) indices.transferFamily = indices.graphicsFamily
            return indices
        }
    }

    private fun createLogicalDevice() {
        if (physicalDevice == null) {
            throw RuntimeException("No physical device found")
        }
        val indices = findQueueFamilies(physicalDevice!!)
        MemoryStack.stackPush().use { stack ->
            val uniqueQueueFamilies = indices.uniques()
            val queuePriority = stack.floats(1.0f)
            val queuesCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size, stack)
            uniqueQueueFamilies.forEachIndexed { index, family ->
                val queueCreateInfo = VkDeviceQueueCreateInfo.calloc(stack)
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                queueCreateInfo.queueFamilyIndex(family)
                queueCreateInfo.pQueuePriorities(queuePriority)
                queuesCreateInfos.put(index, queueCreateInfo)
            }

            val deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)
            deviceFeatures.samplerAnisotropy(true)

            val createInfo = VkDeviceCreateInfo.calloc(stack)
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            createInfo.pQueueCreateInfos(queuesCreateInfos)
            createInfo.pEnabledFeatures(deviceFeatures)
            createInfo.ppEnabledExtensionNames(deviceExtensions.stringToPointerBuffer(stack))
            createInfo.ppEnabledLayerNames(validationLayers.stringToPointerBuffer(stack))
            val createInfoPtr = stack.mallocPointer(1)
            if (vkCreateDevice(physicalDevice!!, createInfo, null, createInfoPtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create logical device")
            }
            logicalDevice = VkDevice(createInfoPtr.get(0), physicalDevice!!, createInfo)
            graphicsQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(logicalDevice!!, indices.graphicsFamily!!, 0, this) }.get(0), logicalDevice!!)
            presentQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(logicalDevice!!, indices.presentFamily!!, 0, this) }.get(0), logicalDevice!!)
            transferQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(logicalDevice!!, indices.transferFamily!!, 0, this) }.get(0), logicalDevice!!)
        }
    }

    private fun createSurface() {
        MemoryStack.stackPush().use { stack ->
            val pSurface = stack.longs(0)
            if (glfwCreateWindowSurface(vulkan!!, window!!, null, pSurface) != VK_SUCCESS) {
                throw RuntimeException("Failed to create window surface")
            }
            surface = pSurface.get(0)
        }
    }

    private fun querySwapChainSupport(stack: MemoryStack, device: VkPhysicalDevice): SwapChainSupportDetails {
        val details = SwapChainSupportDetails()

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack)
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface!!, details.capabilities!!)

        val formatCount = stack.ints(0)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface!!, formatCount, null)
        if (formatCount[0] != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(formatCount[0], stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface!!, formatCount, details.formats!!)
        }

        val presentModeCount = stack.ints(0)
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface!!, presentModeCount, null)
        if (presentModeCount[0] != 0) {
            details.presentModes = stack.mallocInt(presentModeCount[0])
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface!!, presentModeCount, details.presentModes)
        }

        return details
    }

    private fun createCommandPools() {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyIndices = findQueueFamilies(physicalDevice!!)
            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)

            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily!!)
            val pGraphicCommandPool = stack.mallocLong(1)
            if (vkCreateCommandPool(logicalDevice!!, poolInfo, null, pGraphicCommandPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create command pool")
            }
            graphicCommandPool = pGraphicCommandPool.get(0)

            if (queueFamilyIndices.graphicsFamily != queueFamilyIndices.transferFamily) {
                poolInfo.queueFamilyIndex(queueFamilyIndices.transferFamily!!)
                val pTransferCommandPool = stack.mallocLong(1)
                if (vkCreateCommandPool(logicalDevice!!, poolInfo, null, pTransferCommandPool) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create command pool")
                }
                transferCommandPool = pTransferCommandPool.get(0)
            } else {
                transferCommandPool = graphicCommandPool
            }
        }
    }

    private fun createDepthResources() {
        MemoryStack.stackPush().use { stack ->
            val depthFormat = findDepthFormat(stack)
            val pDepthImage = stack.mallocLong(1)
            val pDepthImageMemory = stack.mallocLong(1)
            createImage(stack, swapChainExtent!!.width(), swapChainExtent!!.height(), depthFormat, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pDepthImage, pDepthImageMemory)
            depthImage = pDepthImage.get(0)
            depthImageMemory = pDepthImageMemory.get(0)
            depthImageView = createImageView(stack, depthImage!!, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)

            transitionImageLayout(stack, depthImage!!, depthFormat, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        }
    }

    private fun findSupportedFormat(stack: MemoryStack, candidates: List<Int>, tiling: Int, features: Int): Int {
        for (format in candidates) {
            val props = VkFormatProperties.calloc(stack)
            vkGetPhysicalDeviceFormatProperties(physicalDevice!!, format, props)
            if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() and features) == features) {
                return format
            } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() and features) == features) {
                return format
            }
        }
        throw RuntimeException("Failed to find supported format")
    }

    private fun findDepthFormat(stack: MemoryStack): Int {
        return findSupportedFormat(stack, listOf(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT), VK_IMAGE_TILING_OPTIMAL, VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)
    }

    private fun hasStencilComponent(format: Int): Boolean {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT
    }

    private fun createTextureImage() {
        MemoryStack.stackPush().use { stack ->
            val image = loadImage("test.jpg")
            val imageSize = (image.width * image.height * 4).toLong()
            val stagingBuffer = stack.mallocLong(1)
            val stagingBufferMemory = stack.mallocLong(1)
            createBuffer(stack, imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, stagingBuffer, stagingBufferMemory, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            val data = stack.mallocPointer(1)
            vkMapMemory(logicalDevice!!, stagingBufferMemory.get(0), 0, imageSize, 0, data)
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val pixelsBuffer = data.getByteBuffer(0, imageSize.toInt())
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = pixels[image.width * y + x]
                    pixelsBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    pixelsBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    pixelsBuffer.put((pixel and 0xFF).toByte())
                    pixelsBuffer.put(((pixel shr 24) and 0xFF).toByte())
                }
            }
            pixelsBuffer.flip()
            vkUnmapMemory(logicalDevice!!, stagingBufferMemory.get(0))

            val pTextureImage = stack.mallocLong(1)
            val pTextureImageMemory = stack.mallocLong(1)
            createImage(stack, image.width, image.height, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pTextureImage, pTextureImageMemory)
            textureImage = pTextureImage.get(0)
            textureImageMemory = pTextureImageMemory.get(0)

            transitionImageLayout(stack, textureImage!!, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            copyBufferToImage(stack, stagingBuffer.get(0), textureImage!!, image.width, image.height)
            transitionImageLayout(stack, textureImage!!, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

            vkDestroyBuffer(logicalDevice!!, stagingBuffer.get(0), null)
            vkFreeMemory(logicalDevice!!, stagingBufferMemory.get(0), null)
        }
    }

    private fun createImage(stack: MemoryStack, width: Int, height: Int, format: Int, tiling: Int, usage: Int, properties: Int, pImage: LongBuffer, pImageMemory: LongBuffer) {
        val imageInfo = VkImageCreateInfo.calloc(stack)
        imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
        imageInfo.imageType(VK_IMAGE_TYPE_2D)
        imageInfo.extent {
            it.width(width)
            it.height(height)
            it.depth(1)
        }
        imageInfo.mipLevels(1)
        imageInfo.arrayLayers(1)
        imageInfo.format(format)
        imageInfo.tiling(tiling)
        imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        imageInfo.usage(usage)
        imageInfo.samples(VK_SAMPLE_COUNT_1_BIT)
        imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE)

        if (vkCreateImage(logicalDevice!!, imageInfo, null, pImage) != VK_SUCCESS) {
            throw RuntimeException("Failed to create image")
        }

        val memRequirements = VkMemoryRequirements.calloc(stack)
        vkGetImageMemoryRequirements(logicalDevice!!, pImage.get(0), memRequirements)

        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        allocInfo.allocationSize(memRequirements.size())
        allocInfo.memoryTypeIndex(findMemoryType(stack, memRequirements.memoryTypeBits(), properties))

        if (vkAllocateMemory(logicalDevice!!, allocInfo, null, pImageMemory) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate image memory")
        }

        vkBindImageMemory(logicalDevice!!, pImage.get(0), pImageMemory.get(0), 0)
    }

    private fun transitionImageLayout(stack: MemoryStack, image: Long, format: Int, oldLayout: Int, newLayout: Int) {
        val commandBuffer = beginSingleTimeCommands(stack)

        val barrier = VkImageMemoryBarrier.calloc(1, stack)
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        barrier.oldLayout(oldLayout)
        barrier.newLayout(newLayout)
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        barrier.image(image)
        barrier.subresourceRange {
            it.baseMipLevel(0)
            it.levelCount(1)
            it.baseArrayLayer(0)
            it.layerCount(1)

            if (newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                it.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                if (hasStencilComponent(format)) {
                    it.aspectMask(it.aspectMask() or VK_IMAGE_ASPECT_STENCIL_BIT)
                }
            } else {
                it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            }
        }

        val srcStage: Int
        val dstStage: Int
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(0)
            barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
        } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
        } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
            barrier.srcAccessMask(0)
            barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            dstStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
        } else {
            throw IllegalArgumentException("Unsupported layout transition")
        }

        vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier)

        endSingleTimeCommands(stack, commandBuffer)
    }

    private fun copyBufferToImage(stack: MemoryStack, buffer: Long, image: Long, width: Int, height: Int) {
        val commandBuffer = beginSingleTimeCommands(stack)

        val region = VkBufferImageCopy.calloc(1, stack)
        region.bufferOffset(0)
        region.bufferRowLength(0)
        region.bufferImageHeight(0)
        region.imageSubresource {
            it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            it.mipLevel(0)
            it.baseArrayLayer(0)
            it.layerCount(1)
        }
        region.imageOffset { it.set(0, 0, 0) }
        region.imageExtent { it.set(width, height, 1) }

        vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)

        endSingleTimeCommands(stack, commandBuffer)
    }

    private fun createTextureImageView() {
        MemoryStack.stackPush().use { stack ->
            textureImageView = createImageView(stack, textureImage!!, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_ASPECT_COLOR_BIT)
        }
    }

    private fun createTextureSampler() {
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.calloc(stack)
            vkGetPhysicalDeviceProperties(physicalDevice!!, properties)

            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            samplerInfo.magFilter(VK_FILTER_LINEAR)
            samplerInfo.minFilter(VK_FILTER_LINEAR)
            samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            samplerInfo.anisotropyEnable(true)
            samplerInfo.maxAnisotropy(properties.limits().maxSamplerAnisotropy())
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
            samplerInfo.unnormalizedCoordinates(false)
            samplerInfo.compareEnable(false)
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS)
            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
            samplerInfo.mipLodBias(0.0f)
            samplerInfo.minLod(0.0f)
            samplerInfo.maxLod(0.0f)

            val pTextureSampler = stack.mallocLong(1)
            if (vkCreateSampler(logicalDevice!!, samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw RuntimeException("Failed to create texture sampler")
            }
            textureSampler = pTextureSampler.get(0)
        }
    }

    private fun createImageView(stack: MemoryStack, image: Long, format: Int, aspectFlags: Int) : Long {
        val viewInfo = VkImageViewCreateInfo.calloc(stack)
        viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
        viewInfo.image(image)
        viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D)
        viewInfo.format(format)
        viewInfo.subresourceRange {
            it.aspectMask(aspectFlags)
            it.baseMipLevel(0)
            it.levelCount(1)
            it.baseArrayLayer(0)
            it.layerCount(1)
        }

        val pImageView = stack.mallocLong(1)
        if (vkCreateImageView(logicalDevice!!, viewInfo, null, pImageView) != VK_SUCCESS) {
            throw RuntimeException("Failed to create image views")
        }
        return pImageView.get(0)
    }

    private fun createSwapChainObjects() {
        createSwapChain()
        createImageViews()
        createRenderPass()
        createDescriptorSetLayout()
        createGraphicsPipeline()
        createDepthResources()
        createFramebuffers()
        createUniformBuffers()
        createDescriptorPool()
        createDescriptorSets()
        createCommandBuffers()
    }

    private fun createSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val swapChainSupport = querySwapChainSupport(stack, physicalDevice!!)
            val surfaceFormat = chooseSwapSurfaceFormat(swapChainSupport.formats!!)
            val presentMode = chooseSwapPresentMode(swapChainSupport.presentModes!!)
            val extent = chooseSwapExtent(stack, swapChainSupport.capabilities!!)
            var imageCount = swapChainSupport.capabilities!!.minImageCount() + 1
            if (swapChainSupport.capabilities!!.maxImageCount() in 1..imageCount) {
                imageCount = swapChainSupport.capabilities!!.maxImageCount()
            }
            val indices = findQueueFamilies(physicalDevice!!)

            val createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
            createInfo.surface(surface!!)
            createInfo.minImageCount(imageCount)
            createInfo.imageFormat(surfaceFormat.format())
            createInfo.imageColorSpace(surfaceFormat.colorSpace())
            createInfo.imageExtent(extent)
            createInfo.imageArrayLayers(1)
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
            if (indices.uniques().size != 1) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                createInfo.pQueueFamilyIndices(stack.ints(*indices.uniques().toIntArray()))
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }
            createInfo.preTransform(swapChainSupport.capabilities!!.currentTransform())
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            createInfo.presentMode(presentMode)
            createInfo.clipped(true)
            createInfo.oldSwapchain(VK_NULL_HANDLE)
            val pSwapChain = stack.longs(0)
            if (vkCreateSwapchainKHR(logicalDevice!!, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw RuntimeException("Failed to create swap chain")
            }
            swapChain = pSwapChain.get(0)

            val pImageCount = stack.ints(0)
            vkGetSwapchainImagesKHR(logicalDevice!!, swapChain!!, pImageCount, null)
            val pSwapChainImages = stack.mallocLong(pImageCount[0])
            vkGetSwapchainImagesKHR(logicalDevice!!, swapChain!!, pImageCount, pSwapChainImages)
            swapChainImages = pSwapChainImages.toList()

            swapChainImageFormat = surfaceFormat.format()
            swapChainExtent = VkExtent2D.create().set(extent)
        }
    }

    private fun chooseSwapSurfaceFormat(formats: VkSurfaceFormatKHR.Buffer): VkSurfaceFormatKHR {
        return formats.firstOrNull { it.format() == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
            ?: formats.first()
    }

    private fun chooseSwapPresentMode(presentModes: IntBuffer): Int {
        return presentModes.toList().firstOrNull { it == VK_PRESENT_MODE_MAILBOX_KHR } ?: VK_PRESENT_MODE_FIFO_KHR
    }

    private fun chooseSwapExtent(stack: MemoryStack, capabilities: VkSurfaceCapabilitiesKHR): VkExtent2D {
        return capabilities.currentExtent().let {
            if (it.width() != Int.MAX_VALUE) it
            else {
                val width = stack.ints(0)
                val height = stack.ints(0)
                glfwGetFramebufferSize(window!!, width, height)
                val actualExtent = VkExtent2D.calloc(stack).set(width[0], height[0])
                actualExtent.apply {
                    width(width().coerceAtMost(capabilities.maxImageExtent().width()).coerceAtLeast(capabilities.minImageExtent().width()))
                    height(height().coerceAtMost(capabilities.maxImageExtent().height()).coerceAtLeast(capabilities.minImageExtent().height()))
                }
            }
        }
    }

    private fun createImageViews() {
        MemoryStack.stackPush().use { stack ->
            swapChainImageViews = swapChainImages!!.map { image ->
                createImageView(stack, image, swapChainImageFormat!!, VK_IMAGE_ASPECT_COLOR_BIT)
            }
        }
    }

    private fun createRenderPass() {
        MemoryStack.stackPush().use { stack ->
            val colorAttachment = VkAttachmentDescription.calloc(stack)
            colorAttachment.format(swapChainImageFormat!!)
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT)
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)

            val colorAttachmentRef = VkAttachmentReference.calloc(1, stack)
            colorAttachmentRef.attachment(0)
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

            val depthAttachment = VkAttachmentDescription.calloc(stack)
            depthAttachment.format(findDepthFormat(stack))
            depthAttachment.samples(VK_SAMPLE_COUNT_1_BIT)
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            depthAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            depthAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            depthAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            depthAttachment.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)

            val depthAttachmentRef = VkAttachmentReference.calloc(stack)
            depthAttachmentRef.attachment(1)
            depthAttachmentRef.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)

            val attachments = VkAttachmentDescription.calloc(2, stack)
            attachments.put(colorAttachment).put(depthAttachment).flip()

            val subpass = VkSubpassDescription.calloc(1, stack)
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            subpass.colorAttachmentCount(1)
            subpass.pColorAttachments(colorAttachmentRef)
            subpass.pDepthStencilAttachment(depthAttachmentRef)

            val dependency = VkSubpassDependency.calloc(1, stack)
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL)
            dependency.dstSubpass(0)
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            dependency.srcAccessMask(0)
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)

            val renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            renderPassInfo.pAttachments(attachments)
            renderPassInfo.pSubpasses(subpass)
            renderPassInfo.pDependencies(dependency)

            val pRenderPass = stack.mallocLong(1)
            if (vkCreateRenderPass(logicalDevice!!, renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw RuntimeException("Failed to create render pass")
            }
            renderPass = pRenderPass.get(0)
        }
    }

    private fun createDescriptorSetLayout() {
        MemoryStack.stackPush().use { stack ->
            val uboLayoutBinding = VkDescriptorSetLayoutBinding.calloc(stack)
            uboLayoutBinding.binding(0)
            uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            uboLayoutBinding.descriptorCount(1)
            uboLayoutBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT)

            val samplerLayoutBinding = VkDescriptorSetLayoutBinding.calloc(stack)
            samplerLayoutBinding.binding(1)
            samplerLayoutBinding.descriptorCount(1)
            samplerLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            samplerLayoutBinding.pImmutableSamplers(null)
            samplerLayoutBinding.stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)

            val bindings = VkDescriptorSetLayoutBinding.calloc(2, stack)
            bindings.put(uboLayoutBinding).put(samplerLayoutBinding).flip()
            val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            layoutInfo.pBindings(bindings)

            val pDescriptorSetLayout = stack.mallocLong(1)
            if (vkCreateDescriptorSetLayout(logicalDevice!!, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor set layout")
            }
            descriptorSetLayout = pDescriptorSetLayout.get(0)
        }
    }

    private fun createGraphicsPipeline() {
        MemoryStack.stackPush().use { stack ->
            val vertShaderModule = createShaderModule(stack, loadShader("vert.spv"))
            val fragShaderModule = createShaderModule(stack, loadShader("frag.spv"))

            val vertShaderStageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT)
            vertShaderStageInfo.module(vertShaderModule)
            vertShaderStageInfo.pName(stack.UTF8("main"))

            val fragShaderStageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT)
            fragShaderStageInfo.module(fragShaderModule)
            fragShaderStageInfo.pName(stack.UTF8("main"))

            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            shaderStages.put(vertShaderStageInfo).put(fragShaderStageInfo).flip()

            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            vertexInputInfo.pVertexBindingDescriptions(Vertex.getBindingDescription(stack))
            vertexInputInfo.pVertexAttributeDescriptions(Vertex.getAttributeDescriptions(stack))

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            inputAssembly.primitiveRestartEnable(false)

            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
            dynamicState.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
            dynamicState.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
            viewportState.pViewports(VkViewport.calloc(1, stack))
            viewportState.pScissors(VkRect2D.calloc(1, stack))

            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            rasterizer.depthClampEnable(false)
            rasterizer.rasterizerDiscardEnable(false)
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL)
            rasterizer.lineWidth(1.0f)
            rasterizer.cullMode(VK_CULL_MODE_BACK_BIT)
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            rasterizer.depthBiasEnable(false)

            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
            multisampling.sampleShadingEnable(false)
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(stack)
            colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            colorBlendAttachment.blendEnable(false)

            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            colorBlending.logicOpEnable(false)
            colorBlending.logicOp(VK_LOGIC_OP_COPY)
            colorBlending.pAttachments(VkPipelineColorBlendAttachmentState.calloc(1, stack).put(colorBlendAttachment).flip())

            val depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
            depthStencil.depthTestEnable(true)
            depthStencil.depthWriteEnable(true)
            depthStencil.depthCompareOp(VK_COMPARE_OP_LESS)
            depthStencil.depthBoundsTestEnable(false)
            depthStencil.stencilTestEnable(false)

            val pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            pipelineLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout!!))

            val pPipelineLayout = stack.mallocLong(1)
            if (vkCreatePipelineLayout(logicalDevice!!, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create pipeline layout")
            }
            pipelineLayout = pPipelineLayout.get(0)

            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            pipelineInfo.pStages(shaderStages)
            pipelineInfo.pVertexInputState(vertexInputInfo)
            pipelineInfo.pInputAssemblyState(inputAssembly)
            pipelineInfo.pViewportState(viewportState)
            pipelineInfo.pRasterizationState(rasterizer)
            pipelineInfo.pMultisampleState(multisampling)
            pipelineInfo.pColorBlendState(colorBlending)
            pipelineInfo.pDynamicState(dynamicState)
            pipelineInfo.layout(pipelineLayout!!)
            pipelineInfo.renderPass(renderPass!!)
            pipelineInfo.subpass(0)
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE)
            pipelineInfo.basePipelineIndex(-1)
            pipelineInfo.pDepthStencilState(depthStencil)

            val pGraphicsPipeline = stack.mallocLong(1)
            if (vkCreateGraphicsPipelines(logicalDevice!!, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw RuntimeException("Failed to create graphics pipeline")
            }
            graphicsPipeline = pGraphicsPipeline.get(0)

            vkDestroyShaderModule(logicalDevice!!, vertShaderModule, null)
            vkDestroyShaderModule(logicalDevice!!, fragShaderModule, null)
        }
    }

    private fun createShaderModule(stack: MemoryStack, code: ByteArray): Long {
        val createInfo = VkShaderModuleCreateInfo.calloc(stack)
        createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
        createInfo.pCode(stack.bytes(*code))
        val pShaderModule = stack.mallocLong(1)
        if (vkCreateShaderModule(logicalDevice!!, createInfo, null, pShaderModule) != VK_SUCCESS) {
            throw RuntimeException("Failed to create shader module")
        }
        return pShaderModule.get(0)
    }

    private fun createFramebuffers() {
        MemoryStack.stackPush().use { stack ->
            swapChainFramebuffers = swapChainImageViews!!.map {
                val attachments = stack.longs(it, depthImageView!!)
                val framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                framebufferInfo.renderPass(renderPass!!)
                framebufferInfo.pAttachments(attachments)
                framebufferInfo.width(swapChainExtent!!.width())
                framebufferInfo.height(swapChainExtent!!.height())
                framebufferInfo.layers(1)
                val pFramebuffer = stack.mallocLong(1)
                if (vkCreateFramebuffer(logicalDevice!!, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create framebuffer")
                }
                pFramebuffer.get(0)
            }
        }
    }

    private fun createCommandBuffers() {
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            allocInfo.commandPool(graphicCommandPool!!)
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            allocInfo.commandBufferCount(1)

            val tCommandBuffers = MutableList<VkCommandBuffer?>(MAX_FRAMES_IN_FLIGHT) { null }
            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                val pCommandBuffers = stack.mallocPointer(1)
                if (vkAllocateCommandBuffers(logicalDevice!!, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                    throw RuntimeException("Failed to allocate command buffers")
                }
                tCommandBuffers[i] = VkCommandBuffer(pCommandBuffers.get(0), logicalDevice!!)
            }
            commandBuffers = tCommandBuffers.filterNotNull()
        }
    }

    private fun createVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val size = (vertices.size * Vertex.SIZEOF).toLong()
            val pVertexBuffer = stack.mallocLong(1)
            val pVertexMemory = stack.mallocLong(1)
            createBuffer(stack, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, pVertexBuffer, pVertexMemory, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            vertexBuffer = pVertexBuffer.get(0)
            vertexBufferMemory = pVertexMemory.get(0)

            setVertices(stack, vertices)
        }
    }

    private fun createIndexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val size = (triangles.size * 3 * Integer.BYTES).toLong()

            val pIndexBuffer = stack.mallocLong(1)
            val pIndexMemory = stack.mallocLong(1)
            createBuffer(stack, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_INDEX_BUFFER_BIT, pIndexBuffer, pIndexMemory, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            indexBuffer = pIndexBuffer.get(0)
            indexBufferMemory = pIndexMemory.get(0)

            setTriangles(stack, triangles)
        }
    }

    private fun createUniformBuffers() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = UniformBufferObject.SIZEOF.toLong()
            val pUniformBuffers = mutableListOf<Long>()
            val pUniformBuffersMemory = mutableListOf<Long>()
            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                val pUniformBuffer = stack.mallocLong(1)
                val pUniformBufferMemory = stack.mallocLong(1)
                createBuffer(
                    stack,
                    bufferSize,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    pUniformBuffer,
                    pUniformBufferMemory,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                )
                pUniformBuffers.add(pUniformBuffer.get(0))
                pUniformBuffersMemory.add(pUniformBufferMemory.get(0))
            }
            uniformBuffers = pUniformBuffers
            uniformBuffersMemory = pUniformBuffersMemory
        }
    }

    private fun createDescriptorPool() {
        MemoryStack.stackPush().use { stack ->
            val uboPool = VkDescriptorPoolSize.calloc(stack)
            uboPool.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            uboPool.descriptorCount(MAX_FRAMES_IN_FLIGHT)

            val samplerPool = VkDescriptorPoolSize.calloc(stack)
            samplerPool.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            samplerPool.descriptorCount(MAX_FRAMES_IN_FLIGHT)

            val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
            poolSizes.put(uboPool).put(samplerPool).flip()

            val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            poolInfo.pPoolSizes(poolSizes)
            poolInfo.maxSets(MAX_FRAMES_IN_FLIGHT)

            val pDescriptorPool = stack.mallocLong(1)
            if (vkCreateDescriptorPool(logicalDevice!!, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor pool")
            }
            descriptorPool = pDescriptorPool.get(0)
        }
    }

    private fun createDescriptorSets() {
        val layouts = MutableList(MAX_FRAMES_IN_FLIGHT) { descriptorSetLayout!! }
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            allocInfo.descriptorPool(descriptorPool!!)
            allocInfo.pSetLayouts(stack.longs(*layouts.toLongArray()))

            val pDescriptorSets = stack.mallocLong(MAX_FRAMES_IN_FLIGHT)
            if (vkAllocateDescriptorSets(logicalDevice!!, allocInfo, pDescriptorSets) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate descriptor sets")
            }
            descriptorSets = pDescriptorSets.toList()

            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                bufferInfo.buffer(uniformBuffers!![i])
                bufferInfo.offset(0)
                bufferInfo.range(UniformBufferObject.SIZEOF.toLong())

                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                imageInfo.imageView(textureImageView!!)
                imageInfo.sampler(textureSampler!!)

                val uboDescriptor = VkWriteDescriptorSet.calloc(stack)
                uboDescriptor.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                uboDescriptor.dstSet(descriptorSets!![i])
                uboDescriptor.dstBinding(0)
                uboDescriptor.dstArrayElement(0)
                uboDescriptor.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                uboDescriptor.pBufferInfo(bufferInfo)
                uboDescriptor.descriptorCount(1)

                val samplerDescriptor = VkWriteDescriptorSet.calloc(stack)
                samplerDescriptor.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                samplerDescriptor.dstSet(descriptorSets!![i])
                samplerDescriptor.dstBinding(1)
                samplerDescriptor.dstArrayElement(0)
                samplerDescriptor.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                samplerDescriptor.pImageInfo(imageInfo)
                samplerDescriptor.descriptorCount(1)

                val descriptorWrites = VkWriteDescriptorSet.calloc(2, stack)
                descriptorWrites.put(uboDescriptor).put(samplerDescriptor).flip()

                vkUpdateDescriptorSets(logicalDevice!!, descriptorWrites, null)
            }
        }
    }

    private fun findMemoryType(stack: MemoryStack, typeFilter: Int, properties: Int): Int {
        val memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack)
        vkGetPhysicalDeviceMemoryProperties(physicalDevice!!, memProperties)
        for (i in 0 until memProperties.memoryTypeCount()) {
            if (typeFilter and (1 shl i) != 0 && (memProperties.memoryTypes(i).propertyFlags() and properties) == properties) {
                return i
            }
        }
        throw RuntimeException("Failed to find suitable memory type")

    }

    private fun createBuffer(stack: MemoryStack, size: Long, usage: Int, pBuffer: LongBuffer, pMemory: LongBuffer, properties: Int) {
        val bufferInfo = VkBufferCreateInfo.calloc(stack)
        bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        bufferInfo.size(size)
        bufferInfo.usage(usage)
        bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE)

        if (vkCreateBuffer(logicalDevice!!, bufferInfo, null, pBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to create buffer")
        }

        val memRequirements = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(logicalDevice!!, pBuffer.get(0), memRequirements)

        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        allocInfo.allocationSize(memRequirements.size())
        allocInfo.memoryTypeIndex(findMemoryType(stack, memRequirements.memoryTypeBits(), properties))

        if (vkAllocateMemory(logicalDevice!!, allocInfo, null, pMemory) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate buffer memory")
        }

        vkBindBufferMemory(logicalDevice!!, pBuffer.get(0), pMemory.get(0), 0)
    }

    private fun copyBuffer(stack: MemoryStack, srcBuffer: Long, dstBuffer: Long, size: Long) {
        val commandBuffer = beginSingleTimeCommands(stack)
        val copyRegion = VkBufferCopy.calloc(1, stack)
        copyRegion.size(size)
        vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion)
        endSingleTimeCommands(stack, commandBuffer)
    }

    private fun beginSingleTimeCommands(stack: MemoryStack) : VkCommandBuffer {
        val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
        allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
        allocInfo.commandPool(transferCommandPool!!)
        allocInfo.commandBufferCount(1)

        val pCommandBuffer = stack.mallocPointer(1)
        if (vkAllocateCommandBuffers(logicalDevice!!, allocInfo, pCommandBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate command buffer")
        }
        val commandBuffer = VkCommandBuffer(pCommandBuffer.get(0), logicalDevice!!)

        val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)

        vkBeginCommandBuffer(commandBuffer, beginInfo)
        return commandBuffer
    }

    private fun endSingleTimeCommands(stack: MemoryStack, commandBuffer: VkCommandBuffer) {

        vkEndCommandBuffer(commandBuffer)

        val submitInfo = VkSubmitInfo.calloc(stack)
        submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
        submitInfo.pCommandBuffers(stack.pointers(commandBuffer))

        if (vkQueueSubmit(transferQueue!!, submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
            throw RuntimeException("Failed to submit transfer command buffer")
        }
        vkQueueWaitIdle(transferQueue!!)

        vkFreeCommandBuffers(logicalDevice!!, transferCommandPool!!, commandBuffer)
    }

    private fun createSyncObjects() {
        MemoryStack.stackPush().use { stack ->
            val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)

            val fenceInfo = VkFenceCreateInfo.calloc(stack)
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT)
            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                val pImageAvailableSemaphore = stack.mallocLong(1)
                val pRenderFinishedSemaphore = stack.mallocLong(1)
                val pInFlightFence = stack.mallocLong(1)
                if (vkCreateSemaphore(logicalDevice!!, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS ||
                    vkCreateSemaphore(logicalDevice!!, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS ||
                    vkCreateFence(logicalDevice!!, fenceInfo, null, pInFlightFence) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create synchronization objects")
                }
                frames[i].imageAvailableSemaphore = pImageAvailableSemaphore.get(0)
                frames[i].renderFinishedSemaphore = pRenderFinishedSemaphore.get(0)
                frames[i].inFlightFence = pInFlightFence.get(0)
            }
        }
    }

    private fun recreateSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val width = stack.ints(0)
            val height = stack.ints(0)
            glfwGetFramebufferSize(window!!, width, height)
            while (width[0] == 0 || height[0] == 0) {
                glfwGetFramebufferSize(window!!, width, height)
                glfwWaitEvents()
            }
        }
        vkDeviceWaitIdle(logicalDevice!!)

        cleanupSwapChain()
        createSwapChainObjects()
    }

    private fun cleanupSwapChain() {
        vkDestroyImageView(logicalDevice!!, depthImageView!!, null)
        vkDestroyImage(logicalDevice!!, depthImage!!, null)
        vkFreeMemory(logicalDevice!!, depthImageMemory!!, null)

        swapChainFramebuffers!!.forEach {
            vkDestroyFramebuffer(logicalDevice!!, it, null)
        }
        MemoryStack.stackPush().use { stack ->
            vkFreeCommandBuffers(logicalDevice!!, graphicCommandPool!!, commandBuffers!!.toPointerBuffer(stack))
        }
        uniformBuffers!!.forEachIndexed { i, buffer ->
            vkDestroyBuffer(logicalDevice!!, buffer, null)
            vkFreeMemory(logicalDevice!!, uniformBuffersMemory!![i], null)
        }
        vkDestroyDescriptorPool(logicalDevice!!, descriptorPool!!, null)
        vkDestroyDescriptorSetLayout(logicalDevice!!, descriptorSetLayout!!, null)
        vkDestroyPipeline(logicalDevice!!, graphicsPipeline!!, null)
        vkDestroyPipelineLayout(logicalDevice!!, pipelineLayout!!, null)
        vkDestroyRenderPass(logicalDevice!!, renderPass!!, null)
        swapChainImageViews!!.forEach {
            vkDestroyImageView(logicalDevice!!, it, null)
        }
        vkDestroySwapchainKHR(logicalDevice!!, swapChain!!, null)
    }

    private fun loop() {
        while (!glfwWindowShouldClose(window!!)) {
            glfwPollEvents()
            drawFrame()
        }
        vkDeviceWaitIdle(logicalDevice!!)
    }

    private fun setVertices(stack: MemoryStack, newVertices: List<Vertex>) {
        vertices = newVertices
        val size = (vertices.size * Vertex.SIZEOF).toLong()
        val pStagingBuffer = stack.mallocLong(1)
        val pStagingMemory = stack.mallocLong(1)
        createBuffer(stack, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, pStagingBuffer, pStagingMemory, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)

        val data = stack.mallocPointer(1)
        vkMapMemory(logicalDevice!!, pStagingMemory.get(0), 0, size, 0, data)
        data.getByteBuffer(0, size.toInt()).apply {
            for (vertex in vertices) {
                putFloat(vertex.position.x)
                putFloat(vertex.position.y)
                putFloat(vertex.position.z)
                putFloat(vertex.color.x)
                putFloat(vertex.color.y)
                putFloat(vertex.color.z)
                putFloat(vertex.textureCoordinates.x)
                putFloat(vertex.textureCoordinates.y)
            }
        }
        vkUnmapMemory(logicalDevice!!, pStagingMemory.get())

        copyBuffer(stack, pStagingBuffer.get(0), vertexBuffer!!, size)

        vkDestroyBuffer(logicalDevice!!, pStagingBuffer.get(0), null)
        vkFreeMemory(logicalDevice!!, pStagingMemory.get(0), null)
    }
    @Suppress("unused")
    private fun updateVertices(stack: MemoryStack, transform: (Vertex) -> Vertex) {
        val newVertices = vertices.map(transform)
        setVertices(stack, newVertices)
    }

    private fun setTriangles(stack: MemoryStack, triangles: List<Vec3<Int>>) {
        this.triangles = triangles
        val size = (triangles.size * 3 * Integer.BYTES).toLong()
        val pStagingBuffer = stack.mallocLong(1)
        val pStagingMemory = stack.mallocLong(1)
        createBuffer(stack, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, pStagingBuffer, pStagingMemory, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)

        val data = stack.mallocPointer(1)
        vkMapMemory(logicalDevice!!, pStagingMemory.get(0), 0, size, 0, data)
        data.getByteBuffer(0, size.toInt()).apply {
            for (index in triangles.flatten()) {
                putInt(index)
            }
        }
        vkUnmapMemory(logicalDevice!!, pStagingMemory.get())

        copyBuffer(stack, pStagingBuffer.get(0), indexBuffer!!, size)

        vkDestroyBuffer(logicalDevice!!, pStagingBuffer.get(0), null)
        vkFreeMemory(logicalDevice!!, pStagingMemory.get(0), null)
    }
    @Suppress("unused")
    private fun updateTriangles(stack: MemoryStack, transform: (Vec3<Int>) -> Vec3<Int>) {
        val newTriangles = triangles.map(transform)
        setTriangles(stack, newTriangles)
    }

    private fun drawFrame() {
        MemoryStack.stackPush().use { stack ->
            val frame = frames[currentFrame]
            vkWaitForFences(logicalDevice!!, frame.inFlightFence, true, Long.MAX_VALUE)
            vkResetFences(logicalDevice!!, frame.inFlightFence)

            val pImageIndex = stack.ints(0)
            val result = vkAcquireNextImageKHR(logicalDevice!!, swapChain!!, Long.MAX_VALUE, frame.imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex)
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapChain()
                return
            }
            val imageIndex = pImageIndex.get(0)
            vkResetCommandBuffer(commandBuffers!![currentFrame], 0)

            recordCommandBuffer(stack, commandBuffers!![currentFrame], imageIndex)

            updateUniformBuffer()

            val submitInfo = VkSubmitInfo.calloc(stack)
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            val waitSemaphore = stack.longs(frame.imageAvailableSemaphore)
            val waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            submitInfo.waitSemaphoreCount(1)
            submitInfo.pWaitSemaphores(waitSemaphore)
            submitInfo.pWaitDstStageMask(waitStages)
            submitInfo.pCommandBuffers(stack.pointers(commandBuffers!![currentFrame]))
            val signalSemaphore = stack.longs(frame.renderFinishedSemaphore)
            submitInfo.pSignalSemaphores(signalSemaphore)

            if (vkQueueSubmit(graphicsQueue!!, submitInfo, frame.inFlightFence) != VK_SUCCESS) {
                throw RuntimeException("Failed to submit draw command buffer")
            }

            val presentInfo = VkPresentInfoKHR.calloc(stack)
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            presentInfo.pWaitSemaphores(signalSemaphore)
            val swapChains = stack.longs(swapChain!!)
            presentInfo.pSwapchains(swapChains)
            presentInfo.swapchainCount(swapChains.capacity())
            presentInfo.pImageIndices(pImageIndex)

            vkQueuePresentKHR(presentQueue!!, presentInfo)

            if (result == VK_SUBOPTIMAL_KHR || framebufferResized) {
                framebufferResized = false
                recreateSwapChain()
            } else if (result != VK_SUCCESS) {
                throw RuntimeException("Failed to acquire swap chain image")
            }

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT
        }
    }

    private val startTime = System.currentTimeMillis()
    private fun updateUniformBuffer() {
        MemoryStack.stackPush().use { stack ->
            val currentTime = System.currentTimeMillis()
            val deltaTime = (currentTime - startTime) / 1000.0f
            val ubo = getUniformBufferObject(deltaTime)
            val size = UniformBufferObject.SIZEOF.toLong()
            val data = stack.mallocPointer(1)
            vkMapMemory(logicalDevice!!, uniformBuffersMemory!![currentFrame], 0, size, 0, data)
            data.getByteBuffer(0, size.toInt()).apply {
                ubo.model.rows.flatten().forEach { putFloat(it) }
                ubo.view.rows.flatten().forEach { putFloat(it) }
                ubo.proj.rows.flatten().forEach { putFloat(it) }
            }
            vkUnmapMemory(logicalDevice!!, uniformBuffersMemory!![currentFrame])
        }
    }

    private fun getUniformBufferObject(deltaTime: Float) : UniformBufferObject {
        val model = rotate(deltaTime * pi / 2, vec3(0f, 0f, 1f))
        val view = lookAt(vec3(2f, 2f, 2f), vec3(0f, 0f, 0f), vec3(0f, 0f, 1f))
        val tempProj = perspective(pi / 4, swapChainExtent!!.width().toFloat() / swapChainExtent!!.height().toFloat(), 0.1f, 10f)
        val rows: List<MutableList<Float>> = tempProj.rows.map { it.toMutableList() }
        rows[1][1] = -rows[1][1]
        val proj = SquareMatrix(rows)

        return UniformBufferObject(model, view, proj)
    }

    private fun recordCommandBuffer(stack: MemoryStack, commandBuffer: VkCommandBuffer, imageIndex: Int) {
        val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)

        if (vkBeginCommandBuffer(commandBuffer, beginInfo) != VK_SUCCESS) {
            throw RuntimeException("Failed to begin recording command buffer")
        }

        val renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
        renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
        renderPassInfo.renderPass(renderPass!!)
        renderPassInfo.framebuffer(swapChainFramebuffers!![imageIndex])
        renderPassInfo.renderArea {
            it.offset { offset -> offset.set(0, 0) }
            it.extent(swapChainExtent!!)
        }
        val clearValues = VkClearValue.calloc(2, stack)
        clearValues[0].color().float32(0, 0.0f)
        clearValues[0].color().float32(1, 0.0f)
        clearValues[0].color().float32(2, 0.0f)
        clearValues[0].color().float32(3, 1.0f)
        clearValues[1].depthStencil().depth(1.0f)
        renderPassInfo.pClearValues(clearValues)
        renderPassInfo.clearValueCount(clearValues.capacity())

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline!!)

        val viewport = VkViewport.calloc(1, stack)
        viewport.x(0.0f)
        viewport.y(0.0f)
        viewport.width(swapChainExtent!!.width().toFloat())
        viewport.height(swapChainExtent!!.height().toFloat())
        viewport.minDepth(0.0f)
        viewport.maxDepth(1.0f)
        vkCmdSetViewport(commandBuffer, 0, viewport)

        val scissor = VkRect2D.calloc(1, stack)
        scissor.offset { offset -> offset.set(0, 0) }
        scissor.extent(swapChainExtent!!)
        vkCmdSetScissor(commandBuffer, 0, scissor)

        val buffer = stack.longs(vertexBuffer!!)
        val offsets = stack.longs(0)
        vkCmdBindVertexBuffers(commandBuffer, 0, buffer, offsets)
        vkCmdBindIndexBuffer(commandBuffer, indexBuffer!!, 0, VK_INDEX_TYPE_UINT32)

        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout!!, 0, stack.longs(descriptorSets!![currentFrame]), null)

        vkCmdDrawIndexed(commandBuffer, triangles.size * 3, 1, 0, 0, 0)

        vkCmdEndRenderPass(commandBuffer)

        if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to record command buffer")
        }
    }

    private fun cleanup() {
        eventManager.fire(WindowEvent.Close())
        try {
            cleanupSwapChain()

            vkDestroySampler(logicalDevice!!, textureSampler!!, null)
            vkDestroyImageView(logicalDevice!!, textureImageView!!, null)

            vkDestroyImage(logicalDevice!!, textureImage!!, null)
            vkFreeMemory(logicalDevice!!, textureImageMemory!!, null)

            vkDestroyBuffer(logicalDevice!!, indexBuffer!!, null)
            vkFreeMemory(logicalDevice!!, indexBufferMemory!!, null)
            vkDestroyBuffer(logicalDevice!!, vertexBuffer!!, null)
            vkFreeMemory(logicalDevice!!, vertexBufferMemory!!, null)

            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                vkDestroySemaphore(logicalDevice!!, frames[i].imageAvailableSemaphore, null)
                vkDestroySemaphore(logicalDevice!!, frames[i].renderFinishedSemaphore, null)
                vkDestroyFence(logicalDevice!!, frames[i].inFlightFence, null)
            }

            vkDestroyCommandPool(logicalDevice!!, graphicCommandPool!!, null)

            vkDestroyDevice(logicalDevice!!, null)

            if (DEBUG.get(true)) {
                vkDestroyDebugUtilsMessengerEXT(vulkan!!, debugMessenger!!, null)
            }

            vkDestroySurfaceKHR(vulkan!!, surface!!, null)
            vkDestroyInstance(vulkan!!, null)
            glfwDestroyWindow(window!!)
        } finally {
            glfwTerminate()
        }
    }

    class QueueFamilyIndices(var graphicsFamily: Int? = null, var presentFamily: Int? = null, var transferFamily: Int? = null) {
        val isComplete get() = graphicsFamily != null && presentFamily != null

        fun uniques() = setOfNotNull(graphicsFamily, presentFamily, transferFamily)
    }

    class SwapChainSupportDetails(
        var capabilities: VkSurfaceCapabilitiesKHR? = null,
        var formats: VkSurfaceFormatKHR.Buffer? = null,
        var presentModes: IntBuffer? = null
    )

    class Frame(var imageAvailableSemaphore: Long = -1, var renderFinishedSemaphore: Long = -1, var inFlightFence: Long = -1)

    class UniformBufferObject(
        var model: SquareMatrix<Float>,
        var view: SquareMatrix<Float>,
        var proj: SquareMatrix<Float>,
    ) {
        companion object {
            const val SIZEOF = 3 * 4 * 4 * Float.SIZE_BYTES
        }
    }
}
