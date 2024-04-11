package fr.xibalba.renderer.utils

import fr.xibalba.math.Vec2f
import fr.xibalba.math.Vec3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription

data class Vertex(val position: Vec3f, val color: Vec3f, val textureCoordinates: Vec2f) {

    companion object {
        const val SIZEOF = (3 + 3 + 2) * Float.SIZE_BYTES

        fun getBindingDescription(stack: MemoryStack): VkVertexInputBindingDescription.Buffer {
            val bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
            bindingDescription.binding(0)
            bindingDescription.stride(SIZEOF)
            bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            return bindingDescription
        }

        fun getAttributeDescriptions(stack: MemoryStack): VkVertexInputAttributeDescription.Buffer {
            val attributeDescriptions = VkVertexInputAttributeDescription.calloc(3, stack)

            attributeDescriptions[0].binding(0)
            attributeDescriptions[0].location(0)
            attributeDescriptions[0].format(VK_FORMAT_R32G32B32_SFLOAT)
            attributeDescriptions[0].offset(0)

            attributeDescriptions[1].binding(0)
            attributeDescriptions[1].location(1)
            attributeDescriptions[1].format(VK_FORMAT_R32G32B32_SFLOAT)
            attributeDescriptions[1].offset(3 * Float.SIZE_BYTES)

            attributeDescriptions[2].binding(0)
            attributeDescriptions[2].location(2)
            attributeDescriptions[2].format(VK_FORMAT_R32G32_SFLOAT)
            attributeDescriptions[2].offset(6 * Float.SIZE_BYTES)

            return attributeDescriptions
        }
    }
}