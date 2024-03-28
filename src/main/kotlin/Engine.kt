import fr.xibalba.math.Vec2
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.system.Configuration.DEBUG
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import utils.loadShader
import utils.toList
import utils.toPointerBuffer
import java.lang.invoke.MethodHandles.loop

abstract class Engine(private val defaultSize: Vec2<Int>, private val logLevel: Int = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {

    private var window: Long? = null
    private var vulkan: VkInstance? = null
    private val validationLayers = if (DEBUG.get(true)) listOf("VK_LAYER_KHRONOS_validation") else emptyList()
    private val deviceExtensions = listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
    private var debugMessenger: Long? = null
    private var physicalDevice: VkPhysicalDevice? = null
    private var logicalDevice: VkDevice? = null
    private var surface: Long? = null
    private var graphicsQueue: VkQueue? = null
    private var presentQueue: VkQueue? = null
    private var swapChain: Long? = null
    private var swapChainImages: List<Long>? = null
    private var swapChainImageViews: List<Long>? = null
    private var swapChainImageFormat: Int? = null
    private var renderPass: Long? = null
    private var pipelineLayout: Long? = null
    private var graphicsPipeline: Long? = null
    private var windowSize = defaultSize
    private var swapChainFramebuffers: List<Long>? = null
    private var commandPool: Long? = null
    private var commandBuffers: VkCommandBuffer? = null

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

        glfwMakeContextCurrent(window!!)
        glfwSwapInterval(1)

        glfwShowWindow(window!!)

    }

    private fun initVulkan() {
        createInstance()
        setupDebugMessenger()
        createSurface()
        pickPhysicalDevice()
        createLogicalDevice()
        createSwapChain()
        createImageViews()
        createRenderPass()
        createGraphicsPipeline()
        createFramebuffers()
        createCommandPool()
        createCommandBuffers()
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
            createInfo.ppEnabledLayerNames(validationLayers.toPointerBuffer(stack))
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

    open fun rateDeviceSuitability(device: VkPhysicalDevice, properties: VkPhysicalDeviceProperties, features: VkPhysicalDeviceFeatures, queueFamilies: QueueFamilyIndices): Byte {
        MemoryStack.stackPush().use { stack ->
            val extensionCount = stack.ints(0)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, null)
            val availableExtensions = VkExtensionProperties.malloc(extensionCount[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)
            val availableExtensionsNames = availableExtensions.map { it.extensionNameString() }
            if (
                !queueFamilies.isComplete ||
                !deviceExtensions.all { it in availableExtensionsNames } ||
                querySwapChainSupport(stack, device).let { it.formats.isEmpty() || it.presentModes.isEmpty() }
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
                }
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface!!, presentSupport)
                if (presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i
                }
                if (indices.isComplete) break
            }
            return indices
        }
    }

    private fun createLogicalDevice() {
        if (physicalDevice == null) {
            throw RuntimeException("No physical device found")
        }
        val indices = findQueueFamilies(physicalDevice!!)
        MemoryStack.stackPush().use { stack ->
            val uniqueQueueFamilies = setOf(indices.graphicsFamily!!, indices.presentFamily!!)
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

            val createInfo = VkDeviceCreateInfo.calloc(stack)
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            createInfo.pQueueCreateInfos(queuesCreateInfos)
            createInfo.pEnabledFeatures(deviceFeatures)
            createInfo.ppEnabledExtensionNames(deviceExtensions.toPointerBuffer(stack))
            createInfo.ppEnabledLayerNames(validationLayers.toPointerBuffer(stack))
            val createInfoPtr = stack.mallocPointer(1)
            if (vkCreateDevice(physicalDevice!!, createInfo, null, createInfoPtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create logical device")
            }
            logicalDevice = VkDevice(createInfoPtr.get(0), physicalDevice!!, createInfo)
            graphicsQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(logicalDevice!!, indices.graphicsFamily!!, 0, this) }.get(0), logicalDevice!!)
            presentQueue = VkQueue(stack.mallocPointer(1).apply { vkGetDeviceQueue(logicalDevice!!, indices.presentFamily!!, 0, this) }.get(0), logicalDevice!!)
        }
    }

    private fun createSurface() {
        MemoryStack.stackPush().use { stack ->
            val pSurface = stack.mallocLong(1)
            if (glfwCreateWindowSurface(vulkan!!, window!!, null, pSurface) != VK_SUCCESS) {
                throw RuntimeException("Failed to create window surface")
            }
            surface = pSurface.get(0)
        }
    }

    private fun querySwapChainSupport(stack: MemoryStack, device: VkPhysicalDevice): SwapChainSupportDetails {
        val details = SwapChainSupportDetails(
            VkSurfaceCapabilitiesKHR.malloc(stack),
            mutableListOf(),
            mutableListOf()
        )
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface!!, details.capabilities)
        val formatCount = stack.ints(0)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface!!, formatCount, null)
        if (formatCount[0] != 0) {
            val formats = VkSurfaceFormatKHR.malloc(formatCount[0], stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface!!, formatCount, formats)
            details.formats.addAll(formats)
        }
        val presentModeCount = stack.ints(0)
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface!!, presentModeCount, null)
        if (presentModeCount[0] != 0) {
            val presentModes = stack.mallocInt(presentModeCount[0])
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface!!, presentModeCount, presentModes)
            details.presentModes.addAll(presentModes.toList())
        }
        return details
    }

    private fun createSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val swapChainSupport = querySwapChainSupport(stack, physicalDevice!!)
            val surfaceFormat = chooseSwapSurfaceFormat(swapChainSupport.formats)
            val presentMode = chooseSwapPresentMode(swapChainSupport.presentModes)
            val extent = chooseSwapExtent(stack, swapChainSupport.capabilities)
            var imageCount = swapChainSupport.capabilities.minImageCount() + 1
            if (swapChainSupport.capabilities.maxImageCount() in 1..imageCount) {
                imageCount = swapChainSupport.capabilities.maxImageCount()
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
            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily!!, indices.presentFamily!!))
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }
            createInfo.preTransform(swapChainSupport.capabilities.currentTransform())
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            createInfo.presentMode(presentMode)
            createInfo.clipped(true)
            createInfo.oldSwapchain(VK_NULL_HANDLE)
            val pSwapChain = stack.mallocLong(1)
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
        }
    }

    private fun chooseSwapSurfaceFormat(formats: List<VkSurfaceFormatKHR>): VkSurfaceFormatKHR {
        return formats.firstOrNull { it.format() == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
            ?: formats.first()
    }

    private fun chooseSwapPresentMode(presentModes: List<Int>): Int {
        return presentModes.firstOrNull { it == VK_PRESENT_MODE_MAILBOX_KHR } ?: VK_PRESENT_MODE_FIFO_KHR
    }

    private fun chooseSwapExtent(stack: MemoryStack, capabilities: VkSurfaceCapabilitiesKHR): VkExtent2D {
        return capabilities.currentExtent().let {
            if (it.width() != Int.MAX_VALUE) it
            else {
                val actualExtent = VkExtent2D.calloc(stack).set(defaultSize.x, defaultSize.y)
                actualExtent.apply {
                    width(width().coerceAtMost(capabilities.maxImageExtent().width()).coerceAtLeast(capabilities.minImageExtent().width()))
                    height(height().coerceAtMost(capabilities.maxImageExtent().height()).coerceAtLeast(capabilities.minImageExtent().height()))
                }.also { extent -> windowSize = Vec2(extent.width(), extent.height()) }
            }
        }
    }

    private fun createImageViews() {
        MemoryStack.stackPush().use { stack ->
            swapChainImageViews = swapChainImages!!.map { image ->
                val createInfo = VkImageViewCreateInfo.calloc(stack)
                createInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                createInfo.image(image)
                createInfo.viewType(VK_IMAGE_VIEW_TYPE_2D)
                createInfo.format(swapChainImageFormat!!)
                createInfo.components {
                    it.r(VK_COMPONENT_SWIZZLE_IDENTITY)
                    it.g(VK_COMPONENT_SWIZZLE_IDENTITY)
                    it.b(VK_COMPONENT_SWIZZLE_IDENTITY)
                    it.a(VK_COMPONENT_SWIZZLE_IDENTITY)
                }
                createInfo.subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    it.baseMipLevel(0)
                    it.levelCount(1)
                    it.baseArrayLayer(0)
                    it.layerCount(1)
                }
                val pImageView = stack.mallocLong(1)
                if (vkCreateImageView(logicalDevice!!, createInfo, null, pImageView) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create image views")
                }
                pImageView.get(0)
            }
        }
    }

    private fun createRenderPass() {
        MemoryStack.stackPush().use { stack ->
            val colorAttachment = VkAttachmentDescription.calloc(1, stack)
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

            val subpass = VkSubpassDescription.calloc(1, stack)
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            subpass.colorAttachmentCount(1)
            subpass.pColorAttachments(VkAttachmentReference.calloc(1, stack).put(colorAttachmentRef).flip())

            val renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            renderPassInfo.pAttachments(VkAttachmentDescription.calloc(1, stack).put(colorAttachment).flip())
            renderPassInfo.pSubpasses(VkSubpassDescription.calloc(1, stack).put(subpass).flip())

            val pRenderPass = stack.mallocLong(1)
            if (vkCreateRenderPass(logicalDevice!!, renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw RuntimeException("Failed to create render pass")
            }
            renderPass = pRenderPass.get(0)
        }
    }

    private fun createGraphicsPipeline() {
        MemoryStack.stackPush().use { stack ->
            val swapChainExtent = VkExtent2D.calloc(stack).set(windowSize.x, windowSize.y)

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
            vertexInputInfo.pVertexBindingDescriptions(null)
            vertexInputInfo.pVertexAttributeDescriptions(null)

            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            inputAssembly.primitiveRestartEnable(false)

            val viewport = VkViewport.calloc(stack)
            viewport.x(0.0f)
            viewport.y(0.0f)
            viewport.width(swapChainExtent.width().toFloat())
            viewport.height(swapChainExtent.height().toFloat())
            viewport.minDepth(0.0f)
            viewport.maxDepth(1.0f)

            val scissor = VkRect2D.calloc(stack)
            scissor.offset { it.x(0).y(0) }
            scissor.extent(swapChainExtent)

            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
            viewportState.pViewports(VkViewport.calloc(1, stack).put(viewport).flip())
            viewportState.pScissors(VkRect2D.calloc(1, stack).put(scissor).flip())

            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            rasterizer.depthClampEnable(false)
            rasterizer.rasterizerDiscardEnable(false)
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL)
            rasterizer.lineWidth(1.0f)
            rasterizer.cullMode(VK_CULL_MODE_BACK_BIT)
            rasterizer.frontFace(VK_FRONT_FACE_CLOCKWISE)
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

            val pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)

            val pPipelineLayout = stack.mallocLong(1)
            if (vkCreatePipelineLayout(logicalDevice!!, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create pipeline layout")
            }
            pipelineLayout = pPipelineLayout.get(0)

            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(stack)
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            pipelineInfo.pStages(shaderStages)
            pipelineInfo.pVertexInputState(vertexInputInfo)
            pipelineInfo.pInputAssemblyState(inputAssembly)
            pipelineInfo.pViewportState(viewportState)
            pipelineInfo.pRasterizationState(rasterizer)
            pipelineInfo.pMultisampleState(multisampling)
            pipelineInfo.pColorBlendState(colorBlending)
            pipelineInfo.layout(pipelineLayout!!)
            pipelineInfo.renderPass(renderPass!!)
            pipelineInfo.subpass(0)

            val pipelineInfos = VkGraphicsPipelineCreateInfo.calloc(1, stack).put(pipelineInfo).flip()

            val pGraphicsPipeline = stack.mallocLong(1)
            if (vkCreateGraphicsPipelines(logicalDevice!!, VK_NULL_HANDLE, pipelineInfos, null, pGraphicsPipeline) != VK_SUCCESS) {
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
                val attachments = stack.longs(it)
                val framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                framebufferInfo.renderPass(renderPass!!)
                framebufferInfo.pAttachments(attachments)
                framebufferInfo.width(windowSize.x)
                framebufferInfo.height(windowSize.y)
                framebufferInfo.layers(1)
                val pFramebuffer = stack.mallocLong(1)
                if (vkCreateFramebuffer(logicalDevice!!, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create framebuffer")
                }
                pFramebuffer.get(0)
            }
        }
    }

    private fun createCommandPool() {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyIndices = findQueueFamilies(physicalDevice!!)
            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily!!)

            val pCommandPool = stack.mallocLong(1)
            if (vkCreateCommandPool(logicalDevice!!, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create command pool")
            }
            commandPool = pCommandPool.get(0)
        }
    }

    private fun createCommandBuffers() {
        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            allocInfo.commandPool(commandPool!!)
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            allocInfo.commandBufferCount(1)

            val pCommandBuffers = stack.mallocPointer(1)
            if (vkAllocateCommandBuffers(logicalDevice!!, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate command buffers")
            }
            commandBuffers = VkCommandBuffer(pCommandBuffers.get(0), logicalDevice!!)
        }
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
            it.extent { extent -> extent.set(windowSize.x, windowSize.y) }
        }
        val clearColor = VkClearValue.calloc(1, stack)
        clearColor.color().float32(0, 0.0f)
        clearColor.color().float32(1, 0.0f)
        clearColor.color().float32(2, 0.0f)
        clearColor.color().float32(3, 1.0f)
        renderPassInfo.pClearValues(clearColor)
        renderPassInfo.clearValueCount(1)

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline!!)
        //TODO: make viewport and scissor dynamic
        vkCmdDraw(commandBuffer, 3, 1, 0, 0)

        vkCmdEndRenderPass(commandBuffer)

        if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
            throw RuntimeException("Failed to record command buffer")
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

            cleanup()

            if (DEBUG.get(true)) {
                vkDestroyDebugUtilsMessengerEXT(vulkan!!, debugMessenger!!, null)
            }

            vkDestroyCommandPool(logicalDevice!!, commandPool!!, null)
            swapChainFramebuffers!!.forEach {
                vkDestroyFramebuffer(logicalDevice!!, it, null)
            }
            vkDestroyPipeline(logicalDevice!!, graphicsPipeline!!, null)
            vkDestroyPipelineLayout(logicalDevice!!, pipelineLayout!!, null)
            vkDestroyRenderPass(logicalDevice!!, renderPass!!, null)
            swapChainImageViews!!.forEach {
                vkDestroyImageView(logicalDevice!!, it, null)
            }
            vkDestroySwapchainKHR(logicalDevice!!, swapChain!!, null)
            vkDestroyDevice(logicalDevice!!, null)
            vkDestroySurfaceKHR(vulkan!!, surface!!, null)

            vkDestroyInstance(vulkan!!, null)
            glfwDestroyWindow(window!!)
        } finally {
            glfwTerminate()
        }
    }

    abstract fun cleanup()

    class QueueFamilyIndices(var graphicsFamily: Int? = null, var presentFamily: Int? = null) {
        val isComplete get() = graphicsFamily != null && presentFamily != null
    }

    class SwapChainSupportDetails(
        val capabilities: VkSurfaceCapabilitiesKHR,
        val formats: MutableList<VkSurfaceFormatKHR>,
        val presentModes: MutableList<Int>
    )
}