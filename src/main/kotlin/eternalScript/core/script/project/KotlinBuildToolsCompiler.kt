@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package eternalScript.core.script.project

import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.BaseIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import org.jetbrains.kotlin.buildtools.api.jvm.jvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.jetbrains.kotlin.buildtools.api.jvm.operations.snapshotBasedIcConfiguration
import java.nio.file.Path

internal class KotlinBuildToolsCompiler(
    implementationClassLoader: ClassLoader,
    private val classpath: List<Path>
) {
    private val toolchains = KotlinToolchains.loadImplementation(implementationClassLoader)
    private val jvm = toolchains.jvm

    val compilerVersion: String
        get() = toolchains.getCompilerVersion()

    fun compile(
        workspace: KotlinCompilerWorkspace,
        sourceChanges: KotlinSourceChanges,
        classpathState: KotlinClasspathState,
        snapshotStore: KotlinClasspathSnapshotStore,
        renderer: CompilerMessageRenderer
    ): CompilationResult = toolchains.createBuildSession().use { session ->
        val snapshots = snapshotStore.snapshots(classpathState, session, jvm)
        val operation = jvm.jvmCompilationOperation(
            sources = sourceChanges.sources,
            destinationDirectory = workspace.classesDirectory
        ) {
            this[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = renderer
            compilerArguments[JvmCompilerArguments.CLASSPATH] = classpath
            compilerArguments[JvmCompilerArguments.JVM_TARGET] = JvmTarget.JVM_25
            compilerArguments[JvmCompilerArguments.MODULE_NAME] = MODULE_NAME
            compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
            compilerArguments[JvmCompilerArguments.NO_REFLECT] = true

            this[JvmCompilationOperation.INCREMENTAL_COMPILATION] =
                snapshotBasedIcConfiguration(
                    workingDirectory = workspace.incrementalDirectory,
                    sourcesChanges = SourcesChanges.Known(
                        modifiedFiles = sourceChanges.modified.map(Path::toFile),
                        removedFiles = sourceChanges.removed.map(Path::toFile)
                    ),
                    dependenciesSnapshotFiles = snapshots
                ) {
                    this[BaseIncrementalCompilationConfiguration.ROOT_PROJECT_DIR] =
                        workspace.root
                    this[BaseIncrementalCompilationConfiguration.MODULE_BUILD_DIR] =
                        workspace.root
                    this[BaseIncrementalCompilationConfiguration.BACKUP_CLASSES] = true
                    this[BaseIncrementalCompilationConfiguration.KEEP_IC_CACHES_IN_MEMORY] = true
                    this[BaseIncrementalCompilationConfiguration.TRACK_CONFIGURATION_INPUTS] = true
                }
        }
        session.executeOperation(
            operation,
            toolchains.createInProcessExecutionPolicy(),
            QUIET_LOGGER
        )
    }

    private companion object {
        private const val MODULE_NAME = "eternal-script-project"

        private val QUIET_LOGGER = object : KotlinLogger {
            override val isDebugEnabled = false

            override fun error(msg: String, throwable: Throwable?) = Unit

            override fun warn(msg: String, throwable: Throwable?) = Unit

            override fun info(msg: String) = Unit

            override fun debug(msg: String) = Unit

            override fun lifecycle(msg: String) = Unit
        }
    }
}
