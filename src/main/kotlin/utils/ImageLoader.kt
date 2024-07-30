package fr.xibalba.renderer.utils

import javax.imageio.ImageIO

fun loadTexture(path: String) = ImageIO.read(object {}.javaClass.getResource("/textures/$path.png"))