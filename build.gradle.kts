import java.util.jar.JarFile

plugins {
    `java-library`
    // https://kotlinlang.org/docs/releases.html
    alias(libs.plugins.kotlin.jvm)
    // https://plugins.gradle.org/plugin/io.papermc.paperweight.userdev
    alias(libs.plugins.paperweight.userdev)
    // https://github.com/jpenilla/run-task
    alias(libs.plugins.run.paper)
    // https://github.com/jpenilla/resource-factory
    alias(libs.plugins.resource.factory.paper)
}

group = "eternalScript"
val pluginVersion = providers.gradleProperty("eternalScriptVersion").get()
val javaVersion = providers.gradleProperty("eternalScriptJavaVersion").get().toInt()
val pluginApiVersion = "26.2"
val minecraftVersion = providers.gradleProperty("eternalScriptMinecraftVersion").get()
val paperBuild = providers.gradleProperty("eternalScriptPaperBuild").get()
val paperVersion = "$minecraftVersion.build.$paperBuild"
val minecraftHeapSize = 8
val minecraftArgs = listOf(
    "-Xmx${minecraftHeapSize}G",
    "-Xms${minecraftHeapSize}G",
    "-XX:+AlwaysPreTouch",
    "-XX:+DisableExplicitGC",
    "-XX:+ParallelRefProcEnabled",
    "-XX:+PerfDisableSharedMem",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseG1GC",
    "-XX:G1HeapRegionSize=8M",
    "-XX:G1HeapWastePercent=5",
    "-XX:G1MaxNewSizePercent=40",
    "-XX:G1MixedGCCountTarget=4",
    "-XX:G1MixedGCLiveThresholdPercent=90",
    "-XX:G1NewSizePercent=30",
    "-XX:G1RSetUpdatingPauseTimePercent=5",
    "-XX:G1ReservePercent=20",
    "-XX:InitiatingHeapOccupancyPercent=15",
    "-XX:MaxGCPauseMillis=200",
    "-XX:MaxTenuringThreshold=1",
    "-XX:SurvivorRatio=32",
    "-Dusing.aikars.flags=https://mcflags.emc.gs",
    "-Daikars.new.flags=true"
)
val projectJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        ignoredDependencies.add("com.velocitypowered:velocity-native")
        ignoredDependencies.add("io.papermc.codebook:codebook-cli")
        ignoredDependencies.add("me.lucko:spark-api")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":eternalscript-api"))
    paperweight.paperDevBundle(paperVersion)
    compileOnly(libs.kotlin.stdlib.jdk8)
    compileOnly(libs.kotlin.reflect)
    compileOnly(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)
    compileOnly(libs.kotlin.scripting.common)
    compileOnly(libs.kotlin.scripting.jvm)
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.build.tools.api)
    compileOnly(libs.kotlin.build.tools.impl)
    // Paper's Maven resolver does not use Gradle module variants. Declare the
    // JVM artifact directly so it wins over Kotlin compiler's older transitive
    // coroutines runtime.
    compileOnly(libs.kotlinx.coroutines.core.jvm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.scripting.common)
    testImplementation(libs.kotlin.scripting.jvm)
    testImplementation(libs.kotlin.build.tools.api)
    testImplementation(libs.kotlin.build.tools.impl)
    testImplementation(libs.kotlinx.coroutines.core.jvm)
}

evaluationDependsOn(":eternalscript-api")
val eternalScriptApiJar = project(":eternalscript-api").tasks.named<Jar>("jar")
val eternalScriptPluginJar = tasks.named<Jar>("jar")

fun libraries(): String {
    val compileOnly = project.configurations.getByName("compileOnly").dependencies

    val library = compileOnly.mapNotNull { dependency ->
        val group = dependency.group ?: return@mapNotNull null
        val name = dependency.name
        val version = dependency.version ?: return@mapNotNull null

        "  - $group:$name:$version"
    }.joinToString("\n", "\n")

    return "libraries:$library"
}

fun addLibraries() {
    val paperPluginYml = layout.buildDirectory.file("resources/main/paper-plugin.yml").get().asFile
    if (!paperPluginYml.exists()) return

    paperPluginYml.writeText(paperPluginYml.readText().plus(libraries()))
}

tasks {
    runServer {
        minecraftVersion(minecraftVersion)
        javaLauncher.set(projectJavaLauncher)
        jvmArgs(minecraftArgs)
    }
    test {
        maxHeapSize = "1G"
    }
    register("checkScripts") {
        group = "verification"
        description = "Checks the workspace and bundled reloadable Kotlin projects without evaluating them."
        dependsOn(":script-workspace:checkScripts")
    }
    register("verifyDependencyGovernance") {
        group = "verification"
        description = "Verifies version catalog, dependency locks, and checksum metadata boundaries."

        val catalogFile = project.file("gradle/libs.versions.toml")
        val propertiesFile = project.file("gradle.properties")
        val buildScripts = listOf(
            project.file("build.gradle.kts"),
            project.file("eternalscript-api/build.gradle.kts"),
            project.file("script-workspace/build.gradle.kts")
        )
        val lockFiles = listOf(
            project.file("gradle.lockfile"),
            project.file("eternalscript-api/gradle.lockfile"),
            project.file("script-workspace/gradle.lockfile"),
            project.file("settings-gradle.lockfile")
        )
        val verificationFile = project.file("gradle/verification-metadata.xml")

        inputs.files(listOf(catalogFile, propertiesFile, verificationFile) + buildScripts + lockFiles)

        doLast {
            val catalog = catalogFile.readText()
            listOf(
                "[versions]",
                "[libraries]",
                "[plugins]",
                "kotlinx-coroutines-core-jvm",
                "kotlinx-serialization-json",
                "paperweight-userdev",
                "resource-factory-paper"
            ).forEach { required ->
                check(required in catalog) {
                    "Missing dependency catalog entry: $required"
                }
            }

            val properties = propertiesFile.readText()
            listOf(
                "eternalScriptVersion=",
                "eternalScriptJavaVersion=",
                "eternalScriptMinecraftVersion=",
                "eternalScriptPaperBuild="
            ).forEach { required ->
                check(required in properties) {
                    "Missing project platform version property: $required"
                }
            }
            check("kotlinVersion=" !in properties && "eternalScriptKotlinxVersion=" !in properties) {
                "External dependency versions must be declared in gradle/libs.versions.toml."
            }

            val forbiddenInlineDeclarations = listOf(
                "org.jetbrains." + "kotlinx:",
                "org.jetbrains.kotlin:" + "kotlin-",
                "id(\"io.papermc." + "paperweight.userdev\") version",
                "id(\"xyz.jpenilla." + "run-paper\") version",
                "id(\"xyz.jpenilla." + "resource-factory-paper-convention\") version"
            )
            val inlineDeclarations = buildScripts.flatMap { script ->
                forbiddenInlineDeclarations.filter { declaration -> declaration in script.readText() }
                    .map { declaration -> "${script.path}:$declaration" }
            }
            check(inlineDeclarations.isEmpty()) {
                "External versions must use the version catalog: ${inlineDeclarations.joinToString()}"
            }

            lockFiles.forEach { lockFile ->
                check(lockFile.isFile && lockFile.length() > 0L) {
                    "Missing dependency lock state: ${lockFile.path}"
                }
                check("SNAPSHOT" !in lockFile.readText()) {
                    "Changing dependencies must not be persisted in lock state: ${lockFile.path}"
                }
            }

            val verification = verificationFile.readText()
            check("<verify-metadata>true</verify-metadata>" in verification) {
                "Dependency metadata verification must remain enabled."
            }
            check(Regex("<sha256 value=").findAll(verification).count() > 0) {
                "Dependency verification metadata does not contain SHA-256 entries."
            }
            check(Regex("<trust ").findAll(verification).count() == 3) {
                "Only the three documented changing Paper/Paperweight artifacts may bypass checksums."
            }
            listOf(
                "com.velocitypowered\" name=\"velocity-native",
                "io.papermc.codebook\" name=\"codebook-cli",
                "me.lucko\" name=\"spark-api"
            ).forEach { trusted ->
                check(trusted in verification) {
                    "Missing documented changing-artifact exception: $trusted"
                }
            }
        }
    }
    check {
        dependsOn("checkScripts")
        dependsOn("verifyApiArchitecture")
        dependsOn("verifyDependencyGovernance")
    }
    processResources {
        doLast {
            addLibraries()
        }
    }
    compileJava {
        options.release = javaVersion
    }
    jar {
        dependsOn(eternalScriptApiJar)
        from(eternalScriptApiJar.map { task -> zipTree(task.archiveFile.get().asFile) })
        duplicatesStrategy = DuplicatesStrategy.FAIL
        version = pluginVersion()
    }
    register("verifyApiArchitecture") {
        group = "verification"
        description = "Verifies the public API module boundary and plugin JAR embedding."
        dependsOn(jar, eternalScriptApiJar)

        doLast {
            val forbiddenImports = fileTree(
                project(":eternalscript-api").layout.projectDirectory.dir("src/main")
            ) {
                include("**/*.kt", "**/*.java")
            }.files.filter { source ->
                source.readText().contains("eternalScript.core")
            }
            check(forbiddenImports.isEmpty()) {
                "The API module imports core sources: ${forbiddenImports.joinToString()}"
            }

            fun entries(file: File): List<String> =
                JarFile(file).use { jarFile ->
                    jarFile.entries().asSequence()
                        .filterNot { entry -> entry.isDirectory }
                        .map { entry -> entry.name }
                        .toList()
                }

            val apiFile = eternalScriptApiJar.get().archiveFile.get().asFile
            val pluginFile = eternalScriptPluginJar.get().archiveFile.get().asFile
            val allApiJarEntries = entries(apiFile)
            val apiEntries = allApiJarEntries
                .filter { entry -> entry.startsWith("eternalScript/api/") }
            val pluginEntries = entries(pluginFile)

            check(apiEntries.isNotEmpty()) { "The API JAR does not contain API classes." }
            check(allApiJarEntries.none { entry -> entry.startsWith("eternalScript/core/") }) {
                "The API JAR contains core implementation classes."
            }
            val missing = apiEntries.toSet() - pluginEntries.toSet()
            check(missing.isEmpty()) {
                "The plugin JAR is missing API entries: ${missing.sorted()}"
            }
            val duplicates = pluginEntries
                .filter { entry -> entry.startsWith("eternalScript/api/") }
                .groupingBy { entry -> entry }
                .eachCount()
                .filterValues { count -> count != 1 }
            check(duplicates.isEmpty()) {
                "The plugin JAR contains duplicate API entries: $duplicates"
            }
        }
    }
}

kotlin {
    jvmToolchain(javaVersion)
}

paperPluginYaml {
    name = rootProject.name
    main = pluginMain()
    version = pluginVersion()
    apiVersion = pluginApiVersion
    loader = "${pluginMain()}Loader"
    foliaSupported = true
}

fun pluginMain() = "$group.${rootProject.name}"

fun pluginVersion() = pluginVersion.let { it.ifEmpty { "1.0.0" } }
