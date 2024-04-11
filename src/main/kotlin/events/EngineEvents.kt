package fr.xibalba.renderer.events

import fr.xibalba.renderer.CancellableEvent
import fr.xibalba.renderer.Engine
import fr.xibalba.renderer.Engine.QueueFamilyIndices
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures
import org.lwjgl.vulkan.VkPhysicalDeviceProperties

open class EngineEvents(val engine: Engine) : CancellableEvent() {

    class BeforeInit(engine: Engine) : EngineEvents(engine)
    class AfterInit(engine: Engine) : EngineEvents(engine)
    class Tick(engine: Engine, val deltaTime: Float) : EngineEvents(engine)
    class CreateUniformBufferObject(engine: Engine, val deltaTime: Float, val uniformBufferObject: Engine.UniformBufferObject) : EngineEvents(engine)
    class RateDeviceSuitability(engine: Engine, val device: VkPhysicalDevice, val properties: VkPhysicalDeviceProperties, val features: VkPhysicalDeviceFeatures, val queueFamilies: QueueFamilyIndices, val suitability: Byte) : EngineEvents(engine)
    class Cleanup(engine: Engine) : EngineEvents(engine)
    class Log(engine: Engine, val messageSeverity: Int, val messageType: Int, val pCallbackData: Long, val pUserData: Long, val message: String) : EngineEvents(engine)
}