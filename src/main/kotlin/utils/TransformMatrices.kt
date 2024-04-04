package utils

import fr.xibalba.math.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

fun translate(x: Float = 0f, y: Float = 0f, z: Float = 0f) = SquareMatrix(listOf(
    listOf(1f, 0f, 0f, x),
    listOf(0f, 1f, 0f, y),
    listOf(0f, 0f, 1f, z),
    listOf(0f, 0f, 0f, 1f)
))

fun scale(x: Float = 1f, y: Float = 1f, z: Float = 1f) = SquareMatrix(listOf(
    listOf(x, 0f, 0f, 0f),
    listOf(0f, y, 0f, 0f),
    listOf(0f, 0f, z, 0f),
    listOf(0f, 0f, 0f, 1f)
))

fun rotate(angle: Float, vec: Vec3f, point: Vec3f = vec3(0f, 0f, 0f)) : SquareMatrix<Float> {
    val (u, v, w) = vec
    val (a, b, c) = point
    val uSquare = u * u
    val vSquare = v * v
    val wSquare = w * w
    require(uSquare + vSquare + wSquare == 1f) { "The vector must be normalized" }
    val cos = cos(angle)
    val sin = sin(angle)
    val oneMinusCos = 1 - cos

    return SquareMatrix(listOf(
        listOf(uSquare + (vSquare + wSquare)*cos, u*v*(oneMinusCos)-w*sin, u*w*(oneMinusCos)+v*sin, (a*(vSquare+wSquare)-u*(b*v+c*w))*oneMinusCos + sin*(b*w-c*v)),
        listOf(u*v*(oneMinusCos)+w*sin, vSquare + (uSquare + wSquare)*cos, v*w*(oneMinusCos)-u*sin, (b*(uSquare+wSquare)-v*(a*u+c*w))*oneMinusCos + sin*(c*u-a*w)),
        listOf(u*w*(oneMinusCos)-v*sin, v*w*(oneMinusCos)+u*sin, wSquare + (uSquare + vSquare)*cos, (c*(uSquare+vSquare)-w*(a*u+b*v))*oneMinusCos + sin*(a*v-b*u)),
        listOf(0f, 0f, 0f, 1f)
    ))
}

fun lookAt(eye: Vec3f, center: Vec3f, up: Vec3f) : SquareMatrix<Float> {
    val f = (center - eye).normalize()
    val s = f.cross(up).normalize()
    val u = s.cross(f)

    return SquareMatrix(listOf(
        listOf(s.x, u.x, -f.x, 0f),
        listOf(s.y, u.y, -f.y, 0f),
        listOf(s.z, u.z, -f.z, 0f),
        listOf(-s.dot(eye), -u.dot(eye), f.dot(eye), 1f)
    ))
}

fun perspective(fov: Float, aspect: Float, near: Float, far: Float) : SquareMatrix<Float> {
    val f = 1f / tan(fov / 2f)
    val range = near - far

    return SquareMatrix(listOf(
        listOf(f / aspect, 0f, 0f, 0f),
        listOf(0f, f, 0f, 0f),
        listOf(0f, 0f, (far + near) / range, -1f),
        listOf(0f, 0f, 2f * far * near / range, 0f)
    ))
}