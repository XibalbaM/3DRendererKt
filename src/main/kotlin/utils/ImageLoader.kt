package utils

import javax.imageio.ImageIO

fun loadImage(path: String) = ImageIO.read(object {}.javaClass.getResource("/images/$path"))