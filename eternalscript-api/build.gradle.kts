plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "eternalScript"
version = providers.gradleProperty("eternalScriptVersion").get()

val javaVersion = providers.gradleProperty("eternalScriptJavaVersion").get().toInt()
val minecraftVersion = providers.gradleProperty("eternalScriptMinecraftVersion").get()
val paperBuild = providers.gradleProperty("eternalScriptPaperBuild").get()
val paperVersion = "$minecraftVersion.build.$paperBuild"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly(libs.kotlinx.coroutines.core.jvm)

    testImplementation(libs.kotlin.test)
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation(libs.kotlinx.coroutines.core.jvm)
}

kotlin {
    jvmToolchain(javaVersion)
}
