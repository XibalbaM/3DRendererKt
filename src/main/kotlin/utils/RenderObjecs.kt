package utils

import fr.xibalba.math.Vec2f
import fr.xibalba.math.Vec3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription

data class Vertex(val position: Vec2f, val color: Vec3f) {

    companion object {
        const val SIZEOF = (2 + 3) * Float.SIZE_BYTES

        fun getBindingDescription(stack: MemoryStack): VkVertexInputBindingDescription.Buffer {
            val bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDescription.binding(0)
            bindingDescription.stride(SIZEOF)
            bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            return bindingDescription
        }

        fun getAttributeDescriptions(stack: MemoryStack): VkVertexInputAttributeDescription.Buffer {
            val attributeDescriptions = VkVertexInputAttributeDescription.calloc(2, stack)

            attributeDescriptions[0].binding(0)
            attributeDescriptions[0].location(0)
            attributeDescriptions[0].format(VK_FORMAT_R32G32_SFLOAT)
            attributeDescriptions[0].offset(0)

            attributeDescriptions[1].binding(0)
            attributeDescriptions[1].location(1)
            attributeDescriptions[1].format(VK_FORMAT_R32G32B32_SFLOAT)
            attributeDescriptions[1].offset(2 * Float.SIZE_BYTES)

            return attributeDescriptions
        }
    }
}