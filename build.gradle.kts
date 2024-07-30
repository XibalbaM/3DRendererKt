@file:Suppress("ImplicitThis")

import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

plugins {
    kotlin("jvm") version "1.9.23"
}

group = "fr.xibalba"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))

    implementation("com.github.XibalbaM:MathKt:v1.1.0")

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-vulkan")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")

    implementation(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinJsCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += listOf(
        "-Aorg.eclipse.jdt.core.compiler.annotation.suppressFBWarnings=\"private\""
    )
}

tasks.register("compileVertShader") {
    group = "build"
    description = "Compile vert shader"

    exec {
        commandLine("glslc", "./src/main/resources/shaders/shader.vert", "-o", "./src/main/resources/shaders/vert.spv")
    }
}

tasks.register("compileFragShader") {
    group = "build"
    description = "Compile frag shader"
    exec {
        commandLine("glslc", "./src/main/resources/shaders/shader.frag", "-o", "./src/main/resources/shaders/frag.spv")
    }
}

tasks.register("compileShaders") {
    group = "build"
    description = "Compile shaders"
    dependsOn("compileVertShader", "compileFragShader")
}

tasks.processResources {
    dependsOn("compileShaders")
}