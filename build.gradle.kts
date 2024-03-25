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

    implementation("com.github.XibalbaM:MathKt:master-SNAPSHOT")

    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-vulkan")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = "natives-windows")
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = "natives-windows")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}