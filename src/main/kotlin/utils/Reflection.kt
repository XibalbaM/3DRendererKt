package fr.xibalba.renderer.utils

import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.kotlinFunction


fun getAllFunAnnotatedWith(annotation: KClass<out Annotation>): Map<KClass<*>, List<KFunction<*>>> {
    val classLoader = ClassLoader.getSystemClassLoader()
    val packages = classLoader.definedPackages
    val packageNames = packages.mapNotNull { it.name }.filterNot { it.isBlank() }.filterNot { it.startsWith("java") }.filterNot { it.startsWith("kotlin") }
    val classes = packageNames
        .flatMap { classLoader.getResources(it.replace(".", "/")).toList().map { clazz -> it to clazz } }
        .toSet()
        .flatMap { data ->
            val path = data.second.path
            val file = File(path)
            if (file.isDirectory) {
                file.walkTopDown().filter { it.extension == "class" }.toList().map { getClass(it.nameWithoutExtension, data.first) }
            } else if (file.extension == "class") {
                listOf(getClass(file.nameWithoutExtension, data.first))
            } else {
                emptyList()
            }
        }.filterNotNull()
    val methods = classes.associateWith { it.methods.toList().filter { it.isAnnotationPresent(annotation.java) }.mapNotNull { it.kotlinFunction } }
    return methods.mapKeys { it.key.kotlin }
}

fun getClass(className: String, packageName: String): Class<*>? {
    return try {
        Class.forName("$packageName.$className")
    } catch (e: ClassNotFoundException) {
        null
    }
}