plugins {
    `java-library`
    kotlin("jvm")
}

group = "eternalScript"
version = providers.gradleProperty("eternalScriptVersion").get()

val javaVersion = providers.gradleProperty("eternalScriptJavaVersion").get().toInt()
val minecraftVersion = providers.gradleProperty("eternalScriptMinecraftVersion").get()
val paperBuild = providers.gradleProperty("eternalScriptPaperBuild").get()
val paperVersion = "$minecraftVersion.build.$paperBuild"
val kotlinxVersion = providers.gradleProperty("eternalScriptKotlinxVersion").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinxVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$kotlinxVersion")
}

kotlin {
    jvmToolchain(javaVersion)
}
