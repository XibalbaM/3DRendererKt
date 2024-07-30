package fr.xibalba.renderer.parsers

import fr.xibalba.math.Vec3
import fr.xibalba.math.Vec3f
import fr.xibalba.math.Vec4
import fr.xibalba.renderer.utils.RenderObject
import fr.xibalba.renderer.utils.Vertex

fun parseModel(path: String): RenderObject {
    val file = object {}.javaClass.getResource("/models/$path.obj")!!
    val lines = file.readText().lines().asSequence().filterNot { it.isBlank() }.filterNot { it.startsWith("#") }.map { it.trim() }.map { it.replace("\\s\\s+".toRegex(), " ") }
    val verticesCoordinates = lines.filter { it.startsWith("v ") }.map { it.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec4(it[0], it[1], it[2], it.getOrElse(3) { 1f }) }.toList()
    val texturesCoordinates = lines.filter { it.startsWith("vt ") }.map { it.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec3(it[0], it.getOrElse(1) {0f}, it.getOrElse(2) {0f}) }.toList()
    val normals = lines.filter { it.startsWith("vn ") }.map { it.split(" ").drop(1).map { it.toFloat() } }
        .map { Vec3(it[0], it[1], it[2]) }
    val faces = lines.filter { it.startsWith("f ") }.map { it.split(" ").drop(1).map { it.split("/") } }.map { it.map { it.map { if(it.isEmpty()) null else it.toInt() } } }
        .map { it.map { FaceElement(it[0]!!, it.getOrElse(1) { null }, it.getOrElse(2) { null }) } }
    val vertices = faces.flatMap { it.toList().map { faceElement ->
            Vertex(verticesCoordinates[faceElement.vertex - 1].xyz, Vec3f(1f, 1f, 1f), texturesCoordinates[faceElement.texture?.minus(1) ?: 0].xy)
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