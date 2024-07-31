package fr.xibalba.renderer.engine

import fr.xibalba.math.*
import fr.xibalba.renderer.Engine
import fr.xibalba.renderer.utils.RenderObject
import fr.xibalba.renderer.utils.Vertex
import fr.xibalba.renderer.utils.loadTexture
import java.awt.image.BufferedImage

data class Atlas(val pixels: List<Int>, val prefixWidths: List<Int>, val height: Int, private val textures: List<BufferedImage>) {
    val width = this.prefixWidths.last()

    fun translateTextureCoordinates(textureCoordinates: Vec2f, modelId: Int): Vec2f {
        val x = textureCoordinates.x * this.textures[modelId].width
        val y = textureCoordinates.y * this.textures[modelId].height
        val prefixWidth = this.prefixWidths[modelId]
        return Vec2f((x + prefixWidth) / this.width, y / this.height)
    }
}

var atlasCache: Pair<List<String>, Atlas>? = null

fun Engine.makeAtlas(): Atlas {
    if (atlasCache != null && atlasCache!!.first == this.models) {
        return atlasCache!!.second
    }
    val images = this.models.map { loadTexture(it) }
    val prefixWidths = images.scan(0) { acc, img -> acc + img.width }
    val maxHeight = images.maxOf { it.height }
    val maxWidth = prefixWidths.last()
    val atlasPixels = MutableList(maxWidth *maxHeight) { 0 }
    var offsetX = 0
    for (img in images) {
        //ARGB top left to bottom right line by line
        val pixels = img.getRGB(0, 0, img.width, img.height, null, 0, img.width).toList()
        for (x in 0..<img.width) {
            for (y in 0..<img.height) {
                atlasPixels[(x+offsetX)*maxHeight+y] = pixels[x+y*img.width]
            }
        }
        offsetX += img.width
    }
    val atlas = Atlas(atlasPixels, prefixWidths, maxHeight, images)
    atlasCache = Pair(this.models, atlas)
    return atlas
}

fun Engine.loadModels() {
    val atlas = this.makeAtlas()
    val models = this.models.mapIndexed { index, model -> loadModel(model, atlas, index) }
    val prefixIndex = models.scan(0) { acc, model -> acc + model.vertices.size }
    val vertices = models.flatMap { it.vertices }
    val indices = models.flatMapIndexed { index, model -> model.indices.map { it + prefixIndex[index] } }
    val (optimizedVertices, optimizedIndices) = optimize(vertices, indices)
    this.vertices = optimizedVertices
    this.indices = optimizedIndices
}

private fun loadModel(path: String, atlas: Atlas, index: Int): RenderObject {
    val file = object {}.javaClass.getResource("/models/${path}.obj")!!
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
        Vertex(verticesCoordinates[faceElement.vertex - 1].xyz, Vec3f(1f, 1f, 1f), atlas.translateTextureCoordinates(Vec2(textureCoordinates.x, 1f - textureCoordinates.y), index))
    }
    }.toList()
    val (optimizedVertices, optimizedIndices) = optimize(vertices, vertices.indices.toList())
    return RenderObject(optimizedVertices, optimizedIndices)
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