package fr.xibalba.renderer.engine

import fr.xibalba.math.Vec2
import fr.xibalba.math.Vec3
import fr.xibalba.math.Vec3f
import fr.xibalba.math.Vec4
import fr.xibalba.renderer.Engine
import fr.xibalba.renderer.utils.Vertex
import fr.xibalba.renderer.utils.loadTexture
import java.awt.image.BufferedImage

data class Atlas(val pixels: List<Int>, val prefixWidths: List<Int>, val height: Int) {
    val width = this.prefixWidths.last()
}

fun Engine.makeAtlas(): Atlas {
    val images = mutableListOf<BufferedImage>()
    var maxHeight = 0
    val widths = mutableListOf<Int>()
    for (path in this.models) {
        val img = loadTexture(path)
        if (img.height > maxHeight) {
            maxHeight = img.height
        }
        images.add(img)
        widths.add((widths.lastOrNull() ?: 0) + img.width)
    }
    val atlasPixels = MutableList(widths.last()*maxHeight) { 0 }
    var offsetX = 0
    for (img in images) {
        val pixs = img.getRGB(0, 0, img.width, img.height, null, 0, img.width).toList()
        for (x in 0..<img.width) {
            for (y in 0..<img.height) {
//                println("${x} ${y}")
                atlasPixels[offsetX*maxHeight+x+y*maxHeight] = pixs[x+y*img.width]
            }
        }
        offsetX += img.width
    }
    return Atlas(atlasPixels, widths, maxHeight)
}

fun Engine.loadModel() {
    val file = object {}.javaClass.getResource("/models/${this.models[0]}.obj")!!
    val lines = file.readText().lines().asSequence().filterNot { it.isBlank() }.filterNot { it.startsWith("#") }.map { it.trim() }.map { it.replace("\\s\\s+".toRegex(), " ") }
    val verticesCoordinates = lines.filter { it.startsWith("v ") }.map { line -> line.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec4(it[0], it[1], it[2], it.getOrElse(3) { 1f }) }.toList()
    val texturesCoordinates = lines.filter { it.startsWith("vt ") }.map { line -> line.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec3(it[0], it.getOrElse(1) {0f}, it.getOrElse(2) {0f}) }.toList()
    val normals = lines.filter { it.startsWith("vn ") }.map { line -> line.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec3(it[0], it[1], it[2]) }
    val faces = lines.filter { it.startsWith("f ") }.map { line -> line.split(" ").drop(1).map { it.split("/") } }
        .map { faces -> faces.map { face -> face.map { if(it.isEmpty()) null else it.toInt() } } }
        .map { faces -> faces.map { FaceElement(it[0]!!, it.getOrElse(1) { null }, it.getOrElse(2) { null }) } }
    val vertices = faces.flatMap { it.toList().map { faceElement ->
        val textureCoordinates = texturesCoordinates[faceElement.texture?.minus(1) ?: 0]
        Vertex(verticesCoordinates[faceElement.vertex - 1].xyz, Vec3f(1f, 1f, 1f), Vec2(textureCoordinates.x, 1f - textureCoordinates.y))
    }
    }.toList()
    val (optimizedVertices, optimizedIndices) = optimize(vertices, vertices.indices.toList())
    this.vertices = optimizedVertices
    this.indices = optimizedIndices
}

private fun optimize(vertices: List<Vertex>, indices: List<Int>): Pair<List<Vertex>, List<Int>> {
    val optimizedVertices = mutableListOf<Vertex>()
    val optimizedIndices = mutableListOf<Int>()
    val vertexMap = mutableMapOf<Vertex, Int>()
    for (index in indices) {
        if (vertices[index] !in vertexMap) {
            vertexMap[vertices[index]] = optimizedVertices.size
            optimizedVertices.add(vertices[index])
        }
        optimizedIndices.add(vertexMap[vertices[index]]!!)
    }
    return Pair(optimizedVertices, optimizedIndices)
}

data class FaceElement(val vertex: Int, val texture: Int?, val normal: Int?)