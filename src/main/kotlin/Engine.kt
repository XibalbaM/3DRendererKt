package fr.xibalba.renderer

import fr.xibalba.math.*
import fr.xibalba.renderer.engine.loadModel
import fr.xibalba.renderer.engine.makeAtlas
import fr.xibalba.renderer.events.EngineEvents
import fr.xibalba.renderer.utils.*
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
import java.nio.IntBuffer
import java.nio.LongBuffer

object Engine {

    const val MAX_FRAMES_IN_FLIGHT = 2

    var models: List<String> = emptyList()

    var window: Long? = null

    var vulkan: VkInstance? = null
    val validationLayers = if (DEBUG.get(true)) listOf("VK_LAYER_KHRONOS_validation") else emptyList()
    val deviceExtensions = listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
    var debugMessenger: Long? = null
    var surface: Long? = null

    var physicalDevice: VkPhysicalDevice? = null
    var logicalDevice: VkDevice? = null

    var graphicsQueue: VkQueue? = null
    var presentQueue: VkQueue? = null
    var transferQueue: VkQueue? = null

    var swapChain: Long? = null
    var swapChainImages: List<Long>? = null
    var swapChainImageFormat: Int? = null
    var swapChainExtent: VkExtent2D? = null
    var swapChainImageViews: List<Long>? = null
    var swapChainFramebuffers: List<Long>? = null

    var renderPass: Long? = null
    var descriptorSetLayout: Long? = null
    var descriptorPool: Long? = null
    var descriptorSets: List<Long>? = null
    var pipelineLayout: Long? = null
    var graphicsPipeline: Long? = null

    var graphicCommandPool: Long? = null
    var transferCommandPool: Long? = null
    var commandBuffers: List<VkCommandBuffer>? = null

    var frames = List(this.MAX_FRAMES_IN_FLIGHT) { Frame() }
    var currentFrame = 0

    var framebufferResized = false

    var vertexBuffer: Long? = null
    var vertexBufferMemory: Long? = null
    var indexBuffer: Long? = null
    var indexBufferMemory: Long? = null
    var vertices: List<Vertex> = emptyList()
    var indices: List<Int> = emptyList()

    var uniformBuffers: List<Long>? = null
    var uniformBuffersMemory: List<Long>? = null

    var textureImage: Long? = null
    var textureImageMemory: Long? = null
    var textureImageView: Long? = null
    var textureSampler: Long? = null

    var depthImage: Long? = null
    var depthImageMemory: Long? = null
    var depthImageView: Long? = null

    var logLevel: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
    var running = false

    fun run(defaultSize: Vec2<Int>, models: List<String>) {
        if (this.running) throw IllegalStateException("Engine is already running")
        try {
            this.models = models
            if (!EventManager.fire(EngineEvents.BeforeInit(this))) {
                return
            }
            this.initWindow(defaultSize)
            this.initVulkan()
            this.running = true
            if (!EventManager.fire(EngineEvents.AfterInit(this))) {
                this.cleanup()
                return
            }
            this.loop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        this.cleanup()
    }

    fun initWindow(defaultSize: Vec2<Int>) {

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)

        this.window = glfwCreateWindow(defaultSize.x, defaultSize.y, "Vulkan", NULL, NULL)
        if (this.window == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor()) ?: throw RuntimeException("Failed to get the video mode")

        glfwSetWindowPos(
            this.window!!,
            (vidmode.width() - defaultSize.x) / 2,
            (vidmode.height() - defaultSize.y) / 2
        )

        glfwSetFramebufferSizeCallback(this.window!!, this::framebufferResizeCallback)
        glfwSetKeyCallback(this.window!!, KeyboardManager::keyCallback)
        glfwSetCursorPosCallback(this.window!!, MouseManager::mouseMoveCallback)
        glfwSetMouseButtonCallback(this.window!!, MouseManager::mouseButtonCallback)
        glfwSetScrollCallback(this.window!!, MouseManager::mouseScrollCallback)
    }

    @Suppress("UNUSED_PARAMETER")
    fun framebufferResizeCallback(window: Long, width: Int, height: Int) {
        EventManager.fire(WindowEvent.Resize(Vec2(this.swapChainExtent!!.width(), this.swapChainExtent!!.height()), Vec2(width, height)))
        this.framebufferResized = true
    }

    fun initVulkan() {
        this.createInstance()
        this.setupDebugMessenger()
        this.createSurface()
        this.pickPhysicalDevice()
        this.createLogicalDevice()
        this.createCommandPools()
        this.createTextureImage()
        this.createTextureImageView()
        this.createTextureSampler()
        this.createSwapChainObjects()
        this.loadModel()
        this.createVertexBuffer()
        this.createIndexBuffer()
        this.createSyncObjects()
    }

    fun createInstance() {
        if (!this.checkValidationLayerSupport()) {
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
            createInfo.ppEnabledLayerNames(this.validationLayers.stringToPointerBuffer(stack))
            createInfo.ppEnabledExtensionNames(this.getRequiredExtensions(stack))

            if (DEBUG.get(true)) {
                createInfo.pNext(this.createDebugMessengerInfo(stack).address())
            } else {
                createInfo.pNext(NULL)
            }

            val instancePtr = stack.mallocPointer(1)
            if (vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create Vulkan instance")
            }
            this.vulkan = VkInstance(instancePtr.get(0), createInfo)
        }
    }

    fun checkValidationLayerSupport(): Boolean {
        MemoryStack.stackPush().use { stack ->
            val layerCount = stack.ints(0)
            vkEnumerateInstanceLayerProperties(layerCount, null)
            val availableLayers = VkLayerProperties.malloc(layerCount[0], stack)
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers)
            val availableLayerNames = availableLayers.map { it.layerNameString() }
            return this.validationLayers.all { it in availableLayerNames }
        }
    }

    fun getRequiredExtensions(stack: MemoryStack): PointerBuffer {
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

    fun setupDebugMessenger() {
        if (!DEBUG.get(true)) return

        MemoryStack.stackPush().use { stack ->
            val pDebugMessenger = stack.mallocLong(1)
            if (vkCreateDebugUtilsMessengerEXT(this.vulkan!!, this.createDebugMessengerInfo(stack), null, pDebugMessenger) != VK_SUCCESS) {
                throw RuntimeException("Failed to set up debug messenger")
            }
            this.debugMessenger = pDebugMessenger.get(0)
        }
    }

    fun createDebugMessengerInfo(stack: MemoryStack): VkDebugUtilsMessengerCreateInfoEXT {
        val createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
        createInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        createInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
        createInfo.pfnUserCallback(this::debugCallback)
        return createInfo
    }

    fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {
        if (messageSeverity >= this.logLevel) {
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
            val event = EngineEvents.Log(this, messageSeverity, messageType, pCallbackData, pUserData, message)
            if (!EventManager.fire(event))
                return VK_FALSE
            when (messageSeverity) {
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT -> System.err.println(event.message)
                else -> println(event.message)
            }
        }
        return VK_FALSE
    }

    fun pickPhysicalDevice() {
        MemoryStack.stackPush().use { stack ->
            val deviceCount = stack.ints(0)
            vkEnumeratePhysicalDevices(this.vulkan!!, deviceCount, null)
            if (deviceCount[0] == 0) {
                throw RuntimeException("Failed to find GPUs with Vulkan support")
            }
            val ppPhysicalDevices = stack.mallocPointer(deviceCount[0])
            vkEnumeratePhysicalDevices(this.vulkan!!, deviceCount, ppPhysicalDevices)
            val devices = mutableMapOf<VkPhysicalDevice, Byte>()
            for (i in 0..<ppPhysicalDevices.capacity()) {
                val device = VkPhysicalDevice(ppPhysicalDevices[i], this.vulkan!!)
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(device, properties)
                val features = VkPhysicalDeviceFeatures.calloc(stack)
                vkGetPhysicalDeviceFeatures(device, features)
                val queueFamilies = this.findQueueFamilies(device)
                devices[device] = this.rateDeviceSuitability(device, properties, features, queueFamilies)
            }
            if (devices.none { it.value >= 1 })
                throw RuntimeException("Failed to find a suitable GPU")
            this.physicalDevice = devices.maxBy { it.value }.key
        }
    }

    fun rateDeviceSuitability(
        device: VkPhysicalDevice,
        properties: VkPhysicalDeviceProperties,
        features: VkPhysicalDeviceFeatures,
        queueFamilies: QueueFamilyIndices
    ): Byte {
        MemoryStack.stackPush().use { stack ->
            var suitability = 1
            val extensionCount = stack.ints(0)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, null)
            val availableExtensions = VkExtensionProperties.malloc(extensionCount[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)
            val availableExtensionsNames = availableExtensions.map { it.extensionNameString() }
            if (
                !queueFamilies.isComplete ||
                !this.deviceExtensions.all { it in availableExtensionsNames } ||
                this.querySwapChainSupport(stack, device).let { it.formats!!.capacity() == 0 || it.presentModes!!.capacity() == 0 } ||
                !features.samplerAnisotropy()
            ) {
                suitability = 0
            }
            val event = EngineEvents.RateDeviceSuitability(this, device, properties, features, queueFamilies, suitability.toByte())
            if (!EventManager.fire(event)) {
                return 0
            } else {
                return event.suitability
            }
        }
    }

    fun findQueueFamilies(device: VkPhysicalDevice): QueueFamilyIndices {
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
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, this.surface!!, presentSupport)
                if (presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i
                }
                if (indices.isComplete) break
            }
            if (indices.transferFamily == null) indices.transferFamily = indices.graphicsFamily
            return indices
        }
    }

    fun createLogicalDevice() {
        if (this.physicalDevice == null) {
            throw RuntimeException("No physical device found")
        }
        val indices = this.findQueueFamilies(this.physicalDevice!!)
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
            createInfo.ppEnabledExtensionNames(this.deviceExtensions.stringToPointerBuffer(stack))
            createInfo.ppEnabledLayerNames(this.validationLayers.stringToPointerBuffer(stack))
            val createInfoPtr = stack.mallocPointer(1)
            if (vkCreateDevice(this.physicalDevice!!, createInfo, null, createInfoPtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create logical device")
            }
            this.logicalDevice = VkDevice(createInfoPtr.get(0), this.physicalDevice!!, createInfo)
            this.graphicsQueue =
                VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(this@Engine.logicalDevice!!, indices.graphicsFamily!!, 0, this) }.get(0), this.logicalDevice!!)
            this.presentQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(this@Engine.logicalDevice!!, indices.presentFamily!!, 0, this) }.get(0), this.logicalDevice!!)
            this.transferQueue =
                VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(this@Engine.logicalDevice!!, indices.transferFamily!!, 0, this) }.get(0), this.logicalDevice!!)
        }
    }

    fun createSurface() {
        MemoryStack.stackPush().use { stack ->
            val pSurface = stack.longs(0)
            if (glfwCreateWindowSurface(this.vulkan!!, this.window!!, null, pSurface) != VK_SUCCESS) {
                throw RuntimeException("Failed to create window surface")
            }
            this.surface = pSurface.get(0)
        }
    }

    fun querySwapChainSupport(stack: MemoryStack, device: VkPhysicalDevice): SwapChainSupportDetails {
        val details = SwapChainSupportDetails()

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack)
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, this.surface!!, details.capabilities!!)

        val formatCount = stack.ints(0)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.surface!!, formatCount, null)
        if (formatCount[0] != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(formatCount[0], stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.surface!!, formatCount, details.formats!!)
        }

        val presentModeCount = stack.ints(0)
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.surface!!, presentModeCount, null)
        if (presentModeCount[0] != 0) {
            details.presentModes = stack.mallocInt(presentModeCount[0])
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.surface!!, presentModeCount, details.presentModes)
        }

        return details
    }

    fun createCommandPools() {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyIndices = this.findQueueFamilies(this.physicalDevice!!)
            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)

            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily!!)
            val pGraphicCommandPool = stack.mallocLong(1)
            if (vkCreateCommandPool(this.logicalDevice!!, poolInfo, null, pGraphicCommandPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create command pool")
            }
            this.graphicCommandPool = pGraphicCommandPool.get(0)

            if (queueFamilyIndices.graphicsFamily != queueFamilyIndices.transferFamily) {
                poolInfo.queueFamilyIndex(queueFamilyIndices.transferFamily!!)
                val pTransferCommandPool = stack.mallocLong(1)
                if (vkCreateCommandPool(this.logicalDevice!!, poolInfo, null, pTransferCommandPool) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create command pool")
                }
                this.transferCommandPool = pTransferCommandPool.get(0)
            } else {
                this.transferCommandPool = this.graphicCommandPool
            }
        }
    }

    fun createDepthResources() {
        MemoryStack.stackPush().use { stack ->
            val depthFormat = this.findDepthFormat(stack)
            val pDepthImage = stack.mallocLong(1)
            val pDepthImageMemory = stack.mallocLong(1)
            this.createImage(
                stack,
                this.swapChainExtent!!.width(),
                this.swapChainExtent!!.height(),
                depthFormat,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                pDepthImage,
                pDepthImageMemory
            )
            this.depthImage = pDepthImage.get(0)
            this.depthImageMemory = pDepthImageMemory.get(0)
            this.depthImageView = this.createImageView(stack, this.depthImage!!, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)

            this.transitionImageLayout(stack, this.depthImage!!, depthFormat, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        }
    }

    fun findSupportedFormat(stack: MemoryStack, candidates: List<Int>, tiling: Int, features: Int): Int {
        for (format in candidates) {
            val props = VkFormatProperties.calloc(stack)
            vkGetPhysicalDeviceFormatProperties(this.physicalDevice!!, format, props)
            if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() and features) == features) {
                return format
            } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() and features) == features) {
                return format
            }
        }
        throw RuntimeException("Failed to find supported format")
    }

    fun findDepthFormat(stack: MemoryStack): Int {
        return this.findSupportedFormat(
            stack,
            listOf(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT),
            VK_IMAGE_TILING_OPTIMAL,
            VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
        )
    }

    fun hasStencilComponent(format: Int): Boolean {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT
    }

    fun createTextureImage() {
        MemoryStack.stackPush().use { stack ->
            val atlas = this.makeAtlas()
            val imageSize = (atlas.width * atlas.height * 4).toLong()
            val stagingBuffer = stack.mallocLong(1)
            val stagingBufferMemory = stack.mallocLong(1)
            this.createBuffer(
                stack,
                imageSize,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                stagingBuffer,
                stagingBufferMemory,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            )
            val data = stack.mallocPointer(1)
            vkMapMemory(this.logicalDevice!!, stagingBufferMemory.get(0), 0, imageSize, 0, data)
            val pixelsBuffer = data.getByteBuffer(0, imageSize.toInt())
            for (y in 0 until atlas.height) {
                for (x in 0 until atlas.width) {
                    val pixel = atlas.pixels[atlas.width * y + x]
                    pixelsBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    pixelsBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    pixelsBuffer.put((pixel and 0xFF).toByte())
                    pixelsBuffer.put(((pixel shr 24) and 0xFF).toByte())
                }
            }
            pixelsBuffer.flip()
            vkUnmapMemory(this.logicalDevice!!, stagingBufferMemory.get(0))

            val pTextureImage = stack.mallocLong(1)
            val pTextureImageMemory = stack.mallocLong(1)
            this.createImage(
                stack,
                atlas.width,
                atlas.height,
                VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                pTextureImage,
                pTextureImageMemory
            )
            this.textureImage = pTextureImage.get(0)
            this.textureImageMemory = pTextureImageMemory.get(0)

            this.transitionImageLayout(stack, this.textureImage!!, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            this.copyBufferToImage(stack, stagingBuffer.get(0), this.textureImage!!, atlas.width, atlas.height)
            this.transitionImageLayout(
                stack,
                this.textureImage!!,
                VK_FORMAT_R8G8B8A8_SRGB,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            )

            vkDestroyBuffer(this.logicalDevice!!, stagingBuffer.get(0), null)
            vkFreeMemory(this.logicalDevice!!, stagingBufferMemory.get(0), null)
        }
    }

    fun createImage(
        stack: MemoryStack,
        width: Int,
        height: Int,
        format: Int,
        tiling: Int,
        usage: Int,
        properties: Int,
        pImage: LongBuffer,
        pImageMemory: LongBuffer
    ) {
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

        if (vkCreateImage(this.logicalDevice!!, imageInfo, null, pImage) != VK_SUCCESS) {
            throw RuntimeException("Failed to create image")
        }

        val memRequirements = VkMemoryRequirements.calloc(stack)
        vkGetImageMemoryRequirements(this.logicalDevice!!, pImage.get(0), memRequirements)

        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        allocInfo.allocationSize(memRequirements.size())
        allocInfo.memoryTypeIndex(this.findMemoryType(stack, memRequirements.memoryTypeBits(), properties))

        if (vkAllocateMemory(this.logicalDevice!!, allocInfo, null, pImageMemory) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate image memory")
        }

        vkBindImageMemory(this.logicalDevice!!, pImage.get(0), pImageMemory.get(0), 0)
    }

    fun transitionImageLayout(stack: MemoryStack, image: Long, format: Int, oldLayout: Int, newLayout: Int) {
        val commandBuffer = this.beginSingleTimeCommands(stack)

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
                if (this.hasStencilComponent(format)) {
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

        this.endSingleTimeCommands(stack, commandBuffer)
    }

    fun copyBufferToImage(stack: MemoryStack, buffer: Long, image: Long, width: Int, height: Int) {
        val commandBuffer = this.beginSingleTimeCommands(stack)

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

        this.endSingleTimeCommands(stack, commandBuffer)
    }

    fun createTextureImageView() {
        MemoryStack.stackPush().use { stack ->
            this.textureImageView = this.createImageView(stack, this.textureImage!!, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_ASPECT_COLOR_BIT)
        }
    }

    fun createTextureSampler() {
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.calloc(stack)
            vkGetPhysicalDeviceProperties(this.physicalDevice!!, properties)

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
            if (vkCreateSampler(this.logicalDevice!!, samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw RuntimeException("Failed to create texture sampler")
            }
            this.textureSampler = pTextureSampler.get(0)
        }
    }

    fun createImageView(stack: MemoryStack, image: Long, format: Int, aspectFlags: Int): Long {
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
        if (vkCreateImageView(this.logicalDevice!!, viewInfo, null, pImageView) != VK_SUCCESS) {
            throw RuntimeException("Failed to create image views")
        }
        return pImageView.get(0)
    }

    fun createSwapChainObjects() {
        this.createSwapChain()
        this.createImageViews()
        this.createRenderPass()
        this.createDescriptorSetLayout()
        this.createGraphicsPipeline()
        this.createDepthResources()
        this.createFramebuffers()
        this.createUniformBuffers()
        this.createDescriptorPool()
        this.createDescriptorSets()
        this.createCommandBuffers()
    }

    fun createSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val swapChainSupport = this.querySwapChainSupport(stack, this.physicalDevice!!)
            val surfaceFormat = this.chooseSwapSurfaceFormat(swapChainSupport.formats!!)
            val presentMode = this.chooseSwapPresentMode(swapChainSupport.presentModes!!)
            val extent = this.chooseSwapExtent(stack, swapChainSupport.capabilities!!)
            var imageCount = swapChainSupport.capabilities!!.minImageCount() + 1
            if (swapChainSupport.capabilities!!.maxImageCount() in 1..imageCount) {
                imageCount = swapChainSupport.capabilities!!.maxImageCount()
            }
            val indices = this.findQueueFamilies(this.physicalDevice!!)

            val createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
            createInfo.surface(this.surface!!)
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
            if (vkCreateSwapchainKHR(this.logicalDevice!!, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw RuntimeException("Failed to create swap chain")
            }
            this.swapChain = pSwapChain.get(0)

            val pImageCount = stack.ints(0)
            vkGetSwapchainImagesKHR(this.logicalDevice!!, this.swapChain!!, pImageCount, null)
            val pSwapChainImages = stack.mallocLong(pImageCount[0])
            vkGetSwapchainImagesKHR(this.logicalDevice!!, this.swapChain!!, pImageCount, pSwapChainImages)
            this.swapChainImages = pSwapChainImages.toList()

            this.swapChainImageFormat = surfaceFormat.format()
            this.swapChainExtent = VkExtent2D.create().set(extent)
        }
    }

    fun chooseSwapSurfaceFormat(formats: VkSurfaceFormatKHR.Buffer): VkSurfaceFormatKHR {
        return formats.firstOrNull { it.format() == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
            ?: formats.first()
    }

    fun chooseSwapPresentMode(presentModes: IntBuffer): Int {
        return presentModes.toList().firstOrNull { it == VK_PRESENT_MODE_MAILBOX_KHR } ?: VK_PRESENT_MODE_FIFO_KHR
    }

    fun chooseSwapExtent(stack: MemoryStack, capabilities: VkSurfaceCapabilitiesKHR): VkExtent2D {
        return capabilities.currentExtent().let {
            if (it.width() != Int.MAX_VALUE) it
            else {
                val width = stack.ints(0)
                val height = stack.ints(0)
                glfwGetFramebufferSize(this.window!!, width, height)
                val actualExtent = VkExtent2D.calloc(stack).set(width[0], height[0])
                actualExtent.apply {
                    this.width(this.width().coerceAtMost(capabilities.maxImageExtent().width()).coerceAtLeast(capabilities.minImageExtent().width()))
                    this.height(this.height().coerceAtMost(capabilities.maxImageExtent().height()).coerceAtLeast(capabilities.minImageExtent().height()))
                }
            }
        }
    }

    fun createImageViews() {
        MemoryStack.stackPush().use { stack ->
            this.swapChainImageViews = this.swapChainImages!!.map { image ->
                this.createImageView(stack, image, this.swapChainImageFormat!!, VK_IMAGE_ASPECT_COLOR_BIT)
            }
        }
    }

    fun createRenderPass() {
        MemoryStack.stackPush().use { stack ->
            val colorAttachment = VkAttachmentDescription.calloc(stack)
            colorAttachment.format(this.swapChainImageFormat!!)
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
            depthAttachment.format(this.findDepthFormat(stack))
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
            if (vkCreateRenderPass(this.logicalDevice!!, renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw RuntimeException("Failed to create render pass")
            }
            this.renderPass = pRenderPass.get(0)
        }
    }

    fun createDescriptorSetLayout() {
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
            if (vkCreateDescriptorSetLayout(this.logicalDevice!!, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor set layout")
            }
            this.descriptorSetLayout = pDescriptorSetLayout.get(0)
        }
    }

    fun createGraphicsPipeline() {
        MemoryStack.stackPush().use { stack ->
            val vertShaderModule = this.createShaderModule(stack, loadShader("vert.spv"))
            val fragShaderModule = this.createShaderModule(stack, loadShader("frag.spv"))

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
            pipelineLayoutInfo.pSetLayouts(stack.longs(this.descriptorSetLayout!!))

            val pPipelineLayout = stack.mallocLong(1)
            if (vkCreatePipelineLayout(this.logicalDevice!!, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create pipeline layout")
            }
            this.pipelineLayout = pPipelineLayout.get(0)

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
            pipelineInfo.layout(this.pipelineLayout!!)
            pipelineInfo.renderPass(this.renderPass!!)
            pipelineInfo.subpass(0)
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE)
            pipelineInfo.basePipelineIndex(-1)
            pipelineInfo.pDepthStencilState(depthStencil)

            val pGraphicsPipeline = stack.mallocLong(1)
            if (vkCreateGraphicsPipelines(this.logicalDevice!!, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw RuntimeException("Failed to create graphics pipeline")
            }
            this.graphicsPipeline = pGraphicsPipeline.get(0)

            vkDestroyShaderModule(this.logicalDevice!!, vertShaderModule, null)
            vkDestroyShaderModule(this.logicalDevice!!, fragShaderModule, null)
        }
    }

    fun createShaderModule(stack: MemoryStack, code: ByteArray): Long {
        val createInfo = VkShaderModuleCreateInfo.calloc(stack)
        createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
        createInfo.pCode(stack.bytes(*code))
        val pShaderModule = stack.mallocLong(1)
        if (vkCreateShaderModule(this.logicalDevice!!, createInfo, null, pShaderModule) != VK_SUCCESS) {
            throw RuntimeException("Failed to create shader module")
        }
        return pShaderModule.get(0)
    }

    fun createFramebuffers() {
        MemoryStack.stackPush().use { stack ->
            this.swapChainFramebuffers = this.swapChainImageViews!!.map {
                val attachments = stack.longs(it, this.depthImageView!!)
                val framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                framebufferInfo.renderPass(this.renderPass!!)
                framebufferInfo.pAttachments(attachments)
                framebufferInfo.width(this.swapChainExtent!!.width())
                framebufferInfo.height(this.swapChainExtent!!.height())
                framebufferInfo.layers(1)
                val pFramebuffer = stack.mallocLong(1)
                if (vkCreateFramebuffer(this.logicalDevice!!, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create framebuffer")
                }
                pFramebuffer.get(0)
            }
        }
    }

    fun createCommandBuffers() {
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            allocInfo.commandPool(this.graphicCommandPool!!)
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            allocInfo.commandBufferCount(1)

            val tCommandBuffers = MutableList<VkCommandBuffer?>(this.MAX_FRAMES_IN_FLIGHT) { null }
            for (i in 0 until this.MAX_FRAMES_IN_FLIGHT) {
                val pCommandBuffers = stack.mallocPointer(1)
                if (vkAllocateCommandBuffers(this.logicalDevice!!, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                    throw RuntimeException("Failed to allocate command buffers")
                }
                tCommandBuffers[i] = VkCommandBuffer(pCommandBuffers.get(0), this.logicalDevice!!)
            }
            this.commandBuffers = tCommandBuffers.filterNotNull()
        }
    }

    fun createVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val size = (this.vertices.size * Vertex.SIZEOF).toLong()
            val pVertexBuffer = stack.mallocLong(1)
            val pVertexMemory = stack.mallocLong(1)
            this.createBuffer(
                stack,
                size,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                pVertexBuffer,
                pVertexMemory,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            )
            this.vertexBuffer = pVertexBuffer.get(0)
            this.vertexBufferMemory = pVertexMemory.get(0)

            this.setVertices(stack, this.vertices)
        }
    }

    fun createIndexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val size = (this.indices.size * 3 * Integer.BYTES).toLong()

            val pIndexBuffer = stack.mallocLong(1)
            val pIndexMemory = stack.mallocLong(1)
            this.createBuffer(
                stack,
                size,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                pIndexBuffer,
                pIndexMemory,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            )
            this.indexBuffer = pIndexBuffer.get(0)
            this.indexBufferMemory = pIndexMemory.get(0)

            this.setIndices(stack, this.indices)
        }
    }

    fun createUniformBuffers() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = UniformBufferObject.SIZEOF.toLong()
            val pUniformBuffers = mutableListOf<Long>()
            val pUniformBuffersMemory = mutableListOf<Long>()
            for (i in 0 until this.MAX_FRAMES_IN_FLIGHT) {
                val pUniformBuffer = stack.mallocLong(1)
                val pUniformBufferMemory = stack.mallocLong(1)
                this.createBuffer(
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
            this.uniformBuffers = pUniformBuffers
            this.uniformBuffersMemory = pUniformBuffersMemory
        }
    }

    fun createDescriptorPool() {
        MemoryStack.stackPush().use { stack ->
            val uboPool = VkDescriptorPoolSize.calloc(stack)
            uboPool.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            uboPool.descriptorCount(this.MAX_FRAMES_IN_FLIGHT)

            val samplerPool = VkDescriptorPoolSize.calloc(stack)
            samplerPool.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            samplerPool.descriptorCount(this.MAX_FRAMES_IN_FLIGHT)

            val poolSizes = VkDescriptorPoolSize.calloc(2, stack)
            poolSizes.put(uboPool).put(samplerPool).flip()

            val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            poolInfo.pPoolSizes(poolSizes)
            poolInfo.maxSets(this.MAX_FRAMES_IN_FLIGHT)

            val pDescriptorPool = stack.mallocLong(1)
            if (vkCreateDescriptorPool(this.logicalDevice!!, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor pool")
            }
            this.descriptorPool = pDescriptorPool.get(0)
        }
    }

    fun createDescriptorSets() {
        val layouts = MutableList(this.MAX_FRAMES_IN_FLIGHT) { this.descriptorSetLayout!! }
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            allocInfo.descriptorPool(this.descriptorPool!!)
            allocInfo.pSetLayouts(stack.longs(*layouts.toLongArray()))

            val pDescriptorSets = stack.mallocLong(this.MAX_FRAMES_IN_FLIGHT)
            if (vkAllocateDescriptorSets(this.logicalDevice!!, allocInfo, pDescriptorSets) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate descriptor sets")
            }
            this.descriptorSets = pDescriptorSets.toList()

            for (i in 0 until this.MAX_FRAMES_IN_FLIGHT) {
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                bufferInfo.buffer(this.uniformBuffers!![i])
                bufferInfo.offset(0)
                bufferInfo.range(UniformBufferObject.SIZEOF.toLong())

                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                imageInfo.imageView(this.textureImageView!!)
                imageInfo.sampler(this.textureSampler!!)

                val uboDescriptor = VkWriteDescriptorSet.calloc(stack)
                uboDescriptor.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                uboDescriptor.dstSet(this.descriptorSets!![i])
                uboDescriptor.dstBinding(0)
                uboDescriptor.dstArrayElement(0)
                uboDescriptor.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                uboDescriptor.pBufferInfo(bufferInfo)
                uboDescriptor.descriptorCount(1)

                val samplerDescriptor = VkWriteDescriptorSet.calloc(stack)
                samplerDescriptor.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                samplerDescriptor.dstSet(this.descriptorSets!![i])
                samplerDescriptor.dstBinding(1)
                samplerDescriptor.dstArrayElement(0)
                samplerDescriptor.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                samplerDescriptor.pImageInfo(imageInfo)
                samplerDescriptor.descriptorCount(1)

                val descriptorWrites = VkWriteDescriptorSet.calloc(2, stack)
                descriptorWrites.put(uboDescriptor).put(samplerDescriptor).flip()

                vkUpdateDescriptorSets(this.logicalDevice!!, descriptorWrites, null)
            }
        }
    }

    fun findMemoryType(stack: MemoryStack, typeFilter: Int, properties: Int): Int {
        val memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack)
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice!!, memProperties)
        for (i in 0 until memProperties.memoryTypeCount()) {
            if (typeFilter and (1 shl i) != 0 && (memProperties.memoryTypes(i).propertyFlags() and properties) == properties) {
                return i
            }
        }
        throw RuntimeException("Failed to find suitable memory type")

    }

    fun createBuffer(stack: MemoryStack, size: Long, usage: Int, pBuffer: LongBuffer, pMemory: LongBuffer, properties: Int) {
        val bufferInfo = VkBufferCreateInfo.calloc(stack)
        bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        bufferInfo.size(size)
        bufferInfo.usage(usage)
        bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE)

        if (vkCreateBuffer(this.logicalDevice!!, bufferInfo, null, pBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to create buffer")
        }

        val memRequirements = VkMemoryRequirements.calloc(stack)
        vkGetBufferMemoryRequirements(this.logicalDevice!!, pBuffer.get(0), memRequirements)

        val allocInfo = VkMemoryAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        allocInfo.allocationSize(memRequirements.size())
        allocInfo.memoryTypeIndex(this.findMemoryType(stack, memRequirements.memoryTypeBits(), properties))

        if (vkAllocateMemory(this.logicalDevice!!, allocInfo, null, pMemory) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate buffer memory")
        }

        vkBindBufferMemory(this.logicalDevice!!, pBuffer.get(0), pMemory.get(0), 0)
    }

    fun copyBuffer(stack: MemoryStack, srcBuffer: Long, dstBuffer: Long, size: Long) {
        val commandBuffer = this.beginSingleTimeCommands(stack)
        val copyRegion = VkBufferCopy.calloc(1, stack)
        copyRegion.size(size)
        vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion)
        this.endSingleTimeCommands(stack, commandBuffer)
    }

    fun beginSingleTimeCommands(stack: MemoryStack): VkCommandBuffer {
        val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
        allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
        allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
        allocInfo.commandPool(this.transferCommandPool!!)
        allocInfo.commandBufferCount(1)

        val pCommandBuffer = stack.mallocPointer(1)
        if (vkAllocateCommandBuffers(this.logicalDevice!!, allocInfo, pCommandBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to allocate command buffer")
        }
        val commandBuffer = VkCommandBuffer(pCommandBuffer.get(0), this.logicalDevice!!)

        val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)

        vkBeginCommandBuffer(commandBuffer, beginInfo)
        return commandBuffer
    }

    fun endSingleTimeCommands(stack: MemoryStack, commandBuffer: VkCommandBuffer) {

        vkEndCommandBuffer(commandBuffer)

        val submitInfo = VkSubmitInfo.calloc(stack)
        submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
        submitInfo.pCommandBuffers(stack.pointers(commandBuffer))

        if (vkQueueSubmit(this.transferQueue!!, submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
            throw RuntimeException("Failed to submit transfer command buffer")
        }
        vkQueueWaitIdle(this.transferQueue!!)

        vkFreeCommandBuffers(this.logicalDevice!!, this.transferCommandPool!!, commandBuffer)
    }

    fun createSyncObjects() {
        MemoryStack.stackPush().use { stack ->
            val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)

            val fenceInfo = VkFenceCreateInfo.calloc(stack)
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT)
            for (i in 0 until this.MAX_FRAMES_IN_FLIGHT) {
                val pImageAvailableSemaphore = stack.mallocLong(1)
                val pRenderFinishedSemaphore = stack.mallocLong(1)
                val pInFlightFence = stack.mallocLong(1)
                if (vkCreateSemaphore(this.logicalDevice!!, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS ||
                    vkCreateSemaphore(this.logicalDevice!!, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS ||
                    vkCreateFence(this.logicalDevice!!, fenceInfo, null, pInFlightFence) != VK_SUCCESS
                ) {
                    throw RuntimeException("Failed to create synchronization objects")
                }
                this.frames[i].imageAvailableSemaphore = pImageAvailableSemaphore.get(0)
                this.frames[i].renderFinishedSemaphore = pRenderFinishedSemaphore.get(0)
                this.frames[i].inFlightFence = pInFlightFence.get(0)
            }
        }
    }

    fun recreateSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val width = stack.ints(0)
            val height = stack.ints(0)
            glfwGetFramebufferSize(this.window!!, width, height)
            while (width[0] == 0 || height[0] == 0) {
                glfwGetFramebufferSize(this.window!!, width, height)
                glfwWaitEvents()
            }
        }
        vkDeviceWaitIdle(this.logicalDevice!!)

        this.cleanupSwapChain()
        this.createSwapChainObjects()
    }

    fun cleanupSwapChain() {
        vkDestroyImageView(this.logicalDevice!!, this.depthImageView!!, null)
        vkDestroyImage(this.logicalDevice!!, this.depthImage!!, null)
        vkFreeMemory(this.logicalDevice!!, this.depthImageMemory!!, null)

        this.swapChainFramebuffers!!.forEach {
            vkDestroyFramebuffer(this.logicalDevice!!, it, null)
        }
        MemoryStack.stackPush().use { stack ->
            vkFreeCommandBuffers(this.logicalDevice!!, this.graphicCommandPool!!, this.commandBuffers!!.toPointerBuffer(stack))
        }
        this.uniformBuffers!!.forEachIndexed { i, buffer ->
            vkDestroyBuffer(this.logicalDevice!!, buffer, null)
            vkFreeMemory(this.logicalDevice!!, this.uniformBuffersMemory!![i], null)
        }
        vkDestroyDescriptorPool(this.logicalDevice!!, this.descriptorPool!!, null)
        vkDestroyDescriptorSetLayout(this.logicalDevice!!, this.descriptorSetLayout!!, null)
        vkDestroyPipeline(this.logicalDevice!!, this.graphicsPipeline!!, null)
        vkDestroyPipelineLayout(this.logicalDevice!!, this.pipelineLayout!!, null)
        vkDestroyRenderPass(this.logicalDevice!!, this.renderPass!!, null)
        this.swapChainImageViews!!.forEach {
            vkDestroyImageView(this.logicalDevice!!, it, null)
        }
        vkDestroySwapchainKHR(this.logicalDevice!!, this.swapChain!!, null)
    }

    val startTime = System.currentTimeMillis()
    fun loop() {
        while (!glfwWindowShouldClose(this.window!!)) {
            val currentTime = System.currentTimeMillis()
            val deltaTime = (currentTime - this.startTime) / 1000.0f
            glfwPollEvents()
            if (!EventManager.fire(EngineEvents.Tick(this, deltaTime))) {
                continue
            }
            this.drawFrame(deltaTime)
        }
        vkDeviceWaitIdle(this.logicalDevice!!)
    }

    fun setVertices(stack: MemoryStack, newVertices: List<Vertex>) {
        this.vertices = newVertices
        val size = (this.vertices.size * Vertex.SIZEOF).toLong()
        val pStagingBuffer = stack.mallocLong(1)
        val pStagingMemory = stack.mallocLong(1)
        this.createBuffer(
            stack,
            size,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            pStagingBuffer,
            pStagingMemory,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )

        val data = stack.mallocPointer(1)
        vkMapMemory(this.logicalDevice!!, pStagingMemory.get(0), 0, size, 0, data)
        data.getByteBuffer(0, size.toInt()).apply {
            for (vertex in this@Engine.vertices) {
                this.putFloat(vertex.position.x)
                this.putFloat(vertex.position.y)
                this.putFloat(vertex.position.z)
                this.putFloat(vertex.color.x)
                this.putFloat(vertex.color.y)
                this.putFloat(vertex.color.z)
                this.putFloat(vertex.textureCoordinates.x)
                this.putFloat(vertex.textureCoordinates.y)
            }
        }
        vkUnmapMemory(this.logicalDevice!!, pStagingMemory.get())

        this.copyBuffer(stack, pStagingBuffer.get(0), this.vertexBuffer!!, size)

        vkDestroyBuffer(this.logicalDevice!!, pStagingBuffer.get(0), null)
        vkFreeMemory(this.logicalDevice!!, pStagingMemory.get(0), null)
    }

    @Suppress("unused")
    fun updateVertices(stack: MemoryStack, transform: (Vertex) -> Vertex) {
        val newVertices = this.vertices.map(transform)
        this.setVertices(stack, newVertices)
    }

    fun setIndices(stack: MemoryStack, indices: List<Int>) {
        this.indices = indices
        val size = (indices.size * 3 * Integer.BYTES).toLong()
        val pStagingBuffer = stack.mallocLong(1)
        val pStagingMemory = stack.mallocLong(1)
        this.createBuffer(
            stack,
            size,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            pStagingBuffer,
            pStagingMemory,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )

        val data = stack.mallocPointer(1)
        vkMapMemory(this.logicalDevice!!, pStagingMemory.get(0), 0, size, 0, data)
        data.getByteBuffer(0, size.toInt()).apply {
            for (index in indices) {
                this.putInt(index)
            }
        }
        vkUnmapMemory(this.logicalDevice!!, pStagingMemory.get())

        this.copyBuffer(stack, pStagingBuffer.get(0), this.indexBuffer!!, size)

        vkDestroyBuffer(this.logicalDevice!!, pStagingBuffer.get(0), null)
        vkFreeMemory(this.logicalDevice!!, pStagingMemory.get(0), null)
    }

    @Suppress("unused")
    fun updateIndices(stack: MemoryStack, transform: (Int) -> Int) {
        this.setIndices(stack, this.indices.map(transform))
    }

    fun drawFrame(deltaTime: Float) {
        MemoryStack.stackPush().use { stack ->
            val frame = this.frames[this.currentFrame]
            vkWaitForFences(this.logicalDevice!!, frame.inFlightFence, true, Long.MAX_VALUE)
            vkResetFences(this.logicalDevice!!, frame.inFlightFence)

            val pImageIndex = stack.ints(0)
            val result = vkAcquireNextImageKHR(this.logicalDevice!!, this.swapChain!!, Long.MAX_VALUE, frame.imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex)
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                this.recreateSwapChain()
                return
            }
            val imageIndex = pImageIndex.get(0)
            vkResetCommandBuffer(this.commandBuffers!![this.currentFrame], 0)

            this.recordCommandBuffer(stack, this.commandBuffers!![this.currentFrame], imageIndex)

            this.updateUniformBuffer(deltaTime)

            val submitInfo = VkSubmitInfo.calloc(stack)
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            val waitSemaphore = stack.longs(frame.imageAvailableSemaphore)
            val waitStages = stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            submitInfo.waitSemaphoreCount(1)
            submitInfo.pWaitSemaphores(waitSemaphore)
            submitInfo.pWaitDstStageMask(waitStages)
            submitInfo.pCommandBuffers(stack.pointers(this.commandBuffers!![this.currentFrame]))
            val signalSemaphore = stack.longs(frame.renderFinishedSemaphore)
            submitInfo.pSignalSemaphores(signalSemaphore)

            if (vkQueueSubmit(this.graphicsQueue!!, submitInfo, frame.inFlightFence) != VK_SUCCESS) {
                throw RuntimeException("Failed to submit draw command buffer")
            }

            val presentInfo = VkPresentInfoKHR.calloc(stack)
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
            presentInfo.pWaitSemaphores(signalSemaphore)
            val swapChains = stack.longs(this.swapChain!!)
            presentInfo.pSwapchains(swapChains)
            presentInfo.swapchainCount(swapChains.capacity())
            presentInfo.pImageIndices(pImageIndex)

            vkQueuePresentKHR(this.presentQueue!!, presentInfo)

            if (result == VK_SUBOPTIMAL_KHR || this.framebufferResized) {
                this.framebufferResized = false
                this.recreateSwapChain()
            } else if (result != VK_SUCCESS) {
                throw RuntimeException("Failed to acquire swap chain image")
            }

            this.currentFrame = (this.currentFrame + 1) % this.MAX_FRAMES_IN_FLIGHT
        }
    }

    fun updateUniformBuffer(deltaTime: Float) {
        MemoryStack.stackPush().use { stack ->
            val ubo = this.getUniformBufferObject(deltaTime)
            val size = UniformBufferObject.SIZEOF.toLong()
            val data = stack.mallocPointer(1)
            vkMapMemory(this.logicalDevice!!, this.uniformBuffersMemory!![this.currentFrame], 0, size, 0, data)
            data.getByteBuffer(0, size.toInt()).apply {
                ubo.model.rows.flatten().forEach { this.putFloat(it) }
                ubo.view.rows.flatten().forEach { this.putFloat(it) }
                ubo.proj.rows.flatten().forEach { this.putFloat(it) }
            }
            vkUnmapMemory(this.logicalDevice!!, this.uniformBuffersMemory!![this.currentFrame])
        }
    }

    fun getUniformBufferObject(deltaTime: Float): UniformBufferObject {
        val model = unitMatrix(4)
        val view = lookAt(Vec3(2f, 2f, 2f), Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f))
        val tempProj = perspective(pi / 4, this.swapChainExtent!!.width().toFloat() / this.swapChainExtent!!.height().toFloat(), 0.1f, 100f)
        val rows: List<MutableList<Float>> = tempProj.rows.map { it.toMutableList() }
        rows[1][1] = -rows[1][1]
        val proj = SquareMatrix(rows)
        val ubo = UniformBufferObject(model, view, proj)
        val event = EngineEvents.CreateUniformBufferObject(this, deltaTime, ubo)
        return if (EventManager.fire(event)) event.uniformBufferObject else ubo
    }

    fun recordCommandBuffer(stack: MemoryStack, commandBuffer: VkCommandBuffer, imageIndex: Int) {
        val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)

        if (vkBeginCommandBuffer(commandBuffer, beginInfo) != VK_SUCCESS) {
            throw RuntimeException("Failed to begin recording command buffer")
        }

        val renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
        renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
        renderPassInfo.renderPass(this.renderPass!!)
        renderPassInfo.framebuffer(this.swapChainFramebuffers!![imageIndex])
        renderPassInfo.renderArea {
            it.offset { offset -> offset.set(0, 0) }
            it.extent(this.swapChainExtent!!)
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

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.graphicsPipeline!!)

        val viewport = VkViewport.calloc(1, stack)
        viewport.x(0.0f)
        viewport.y(0.0f)
        viewport.width(this.swapChainExtent!!.width().toFloat())
        viewport.height(this.swapChainExtent!!.height().toFloat())
        viewport.minDepth(0.0f)
        viewport.maxDepth(1.0f)
        vkCmdSetViewport(commandBuffer, 0, viewport)

        val scissor = VkRect2D.calloc(1, stack)
        scissor.offset { offset -> offset.set(0, 0) }
        scissor.extent(this.swapChainExtent!!)
        vkCmdSetScissor(commandBuffer, 0, scissor)

        val buffer = stack.longs(this.vertexBuffer!!)
        val offsets = stack.longs(0)
        vkCmdBindVertexBuffers(commandBuffer, 0, buffer, offsets)
        vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer!!, 0, VK_INDEX_TYPE_UINT32)

        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout!!, 0, stack.longs(this.descriptorSets!![this.currentFrame]), null)

        vkCmdDrawIndexed(commandBuffer, this.indices.size * 3, 1, 0, 0, 0)

        vkCmdEndRenderPass(commandBuffer)

        if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to record command buffer")
        }
    }

    fun cleanup() {
        EventManager.fire(WindowEvent.Close())
        try {
            this.cleanupSwapChain()

            vkDestroySampler(this.logicalDevice!!, this.textureSampler!!, null)
            vkDestroyImageView(this.logicalDevice!!, this.textureImageView!!, null)

            vkDestroyImage(this.logicalDevice!!, this.textureImage!!, null)
            vkFreeMemory(this.logicalDevice!!, this.textureImageMemory!!, null)

            vkDestroyBuffer(this.logicalDevice!!, this.indexBuffer!!, null)
            vkFreeMemory(this.logicalDevice!!, this.indexBufferMemory!!, null)
            vkDestroyBuffer(this.logicalDevice!!, this.vertexBuffer!!, null)
            vkFreeMemory(this.logicalDevice!!, this.vertexBufferMemory!!, null)

            for (i in 0 until this.MAX_FRAMES_IN_FLIGHT) {
                vkDestroySemaphore(this.logicalDevice!!, this.frames[i].imageAvailableSemaphore, null)
                vkDestroySemaphore(this.logicalDevice!!, this.frames[i].renderFinishedSemaphore, null)
                vkDestroyFence(this.logicalDevice!!, this.frames[i].inFlightFence, null)
            }

            vkDestroyCommandPool(this.logicalDevice!!, this.graphicCommandPool!!, null)

            vkDestroyDevice(this.logicalDevice!!, null)

            if (DEBUG.get(true)) {
                vkDestroyDebugUtilsMessengerEXT(this.vulkan!!, this.debugMessenger!!, null)
            }

            vkDestroySurfaceKHR(this.vulkan!!, this.surface!!, null)
            vkDestroyInstance(this.vulkan!!, null)
            glfwDestroyWindow(this.window!!)
            EventManager.fire(EngineEvents.Cleanup(this))
        } finally {
            glfwTerminate()
            this.running = false
        }
    }

    fun setCursor(cursor: Int) {
        require(this.running)
        glfwSetInputMode(this.window!!, GLFW_CURSOR, cursor)
        if (cursor == GLFW_CURSOR_DISABLED && glfwRawMouseMotionSupported()) {
            glfwSetInputMode(this.window!!, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
        } else {
            glfwSetInputMode(this.window!!, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE)
        }
    }

    class QueueFamilyIndices(var graphicsFamily: Int? = null, var presentFamily: Int? = null, var transferFamily: Int? = null) {
        val isComplete get() = this.graphicsFamily != null && this.presentFamily != null

        fun uniques() = setOfNotNull(this.graphicsFamily, this.presentFamily, this.transferFamily)
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
