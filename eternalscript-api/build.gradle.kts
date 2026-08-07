plugins {
    `java-library`
    kotlin("jvm")
}

group = "eternalScript"
version = "2.0.0"

val javaVersion = 25
val paperVersion = "26.2.build.87-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
}

kotlin {
    jvmToolchain(javaVersion)
}
