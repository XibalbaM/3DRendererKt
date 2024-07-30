package fr.xibalba.renderer.utils

import javax.imageio.ImageIO

fun loadImage(path: String) = ImageIO.read(object {}.javaClass.getResource("/images/$path"))

fun loadTexture(path: String) = ImageIO.read(object {}.javaClass.getResource("/textures/$path"))

fun makeAtlas(paths: List<String>): List<Int> {
    return paths.flatMap {
        val img = loadTexture(paths[0])
        img.getRGB(0, 0, img.width, img.height, null, 0, img.width).toList()
    }
}