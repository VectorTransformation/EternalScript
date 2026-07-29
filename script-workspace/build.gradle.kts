import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

val javaVersion = 21
val minecraftVersion = "1.21.11"
val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
val scriptSourceRoot = layout.projectDirectory.dir("src/main/kotlin")
val bundledScriptRoot = rootProject.layout.projectDirectory.dir("src/main/resources/scripts")
val scriptProjectTools by configurations.creating {
    isCanBeConsumed = false
    extendsFrom(
        configurations.getByName("implementation"),
        configurations.getByName("runtimeOnly")
    )
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.papermc.paper:paper-api:$minecraftVersion-R0.1-SNAPSHOT")

    scriptProjectTools(kotlin("compiler-embeddable", kotlinVersion))
    scriptProjectTools(kotlin("reflect", kotlinVersion))
    scriptProjectTools("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlinVersion")
    scriptProjectTools("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlinVersion")
}

kotlin {
    jvmToolchain(javaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xuse-fir-lt=false")
    }
}

fun registerScriptCheck(
    name: String,
    taskDescription: String,
    sourceRoot: File,
    mode: String
) =
    tasks.register<JavaExec>(name) {
        val compilerCache = layout.buildDirectory.dir("eternalScript-check/$name")
        group = "verification"
        description = taskDescription
        classpath = scriptProjectTools
        mainClass.set("eternalScript.core.script.project.ScriptProjectCheckTool")
        args(
            sourceRoot.absolutePath,
            compilerCache.get().asFile.absolutePath,
            mode
        )
        inputs.files(
            fileTree(sourceRoot) {
                include("**/*.kt")
            }
        ).withPathSensitivity(PathSensitivity.RELATIVE).normalizeLineEndings()
        inputs.property("compilerSchema", 2)
        outputs.dir(compilerCache)
    }

val checkWorkspaceScripts = registerScriptCheck(
    "checkWorkspaceScripts",
    "Compiles the workspace Kotlin sources as one reloadable project without evaluating them.",
    scriptSourceRoot.asFile,
    "runtime"
).also { task ->
    task.configure {
        dependsOn(tasks.named("compileKotlin"))
    }
}
val checkBundledScripts = registerScriptCheck(
    "checkBundledScripts",
    "Compiles the plugin's bundled runtime Kotlin sources as one project without evaluating them.",
    bundledScriptRoot.asFile,
    "runtime"
)
val checkBundledExamples = registerScriptCheck(
    "checkBundledExamples",
    "Compiles ignored bundled examples as a documentation-fixture project.",
    bundledScriptRoot.asFile,
    "ignored"
)

tasks.register("checkScripts") {
    group = "verification"
    description = "Checks both workspace and bundled EternalScript projects."
    dependsOn(checkWorkspaceScripts, checkBundledScripts, checkBundledExamples)
}

tasks.named("check") {
    dependsOn("checkScripts")
}
