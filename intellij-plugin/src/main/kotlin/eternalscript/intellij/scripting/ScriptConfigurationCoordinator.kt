package eternalscript.intellij.scripting

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.model.EternalScriptProjectSnapshot
import eternalscript.intellij.model.EternalScriptWorkspace
import eternalscript.intellij.resolve.Idea262Facade
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.core.script.v1.ScriptDependencyAware
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

internal class ScriptConfigurationCoordinator(
    private val project: Project,
    private val snapshot: () -> EternalScriptProjectSnapshot
) {
    private val definitionRegistered = AtomicBoolean()
    private var configurationFingerprints: Map<String, String> = emptyMap()
    private val configuredFiles = ConcurrentHashMap<String, String>()
    private val requestedFiles = ConcurrentHashMap.newKeySet<String>()
    private val retryCounts = ConcurrentHashMap<String, Int>()

    fun definitionRegistered() {
        if (definitionRegistered.compareAndSet(false, true)) {
            val current = snapshot()
            reloadOpenFiles(current)
        }
    }

    @TestOnly
    fun setReloadsEnabledForTests(enabled: Boolean) {
        check(ApplicationManager.getApplication().isUnitTestMode)
        definitionRegistered.set(enabled)
        configurationFingerprints = emptyMap()
    }

    fun definitionConfiguration(workspace: EternalScriptWorkspace): ScriptCompilationConfiguration {
        val classpath = workspace.environment.classpath().map { uri -> Path.of(uri).toFile() }
        return ScriptCompilationConfiguration {
            defaultImports.append("${workspace.packageName.asString()}.*")
            implicitReceivers.append(KotlinType(workspace.receiverFqName))
            compilerOptions.append("-jvm-target=${IdeProtocol.SCRIPT_JVM_TARGET}", "-Xnested-type-aliases")
            if (classpath.isNotEmpty()) jvm { updateClasspath(classpath) }
        }
    }

    fun snapshotPublished(previous: EternalScriptProjectSnapshot, next: EternalScriptProjectSnapshot) {
        val nextFingerprints = next.workspaces.associate { workspace ->
            workspace.id to configurationFingerprint(workspace)
        }
        val rootsChanged = definitionInputs(previous) != definitionInputs(next)
        if (rootsChanged) Idea262Facade.invalidateScriptDefinitions(project)
        val environmentChanged = nextFingerprints.any { (id, fingerprint) ->
            configurationFingerprints[id] != fingerprint
        } || configurationFingerprints.keys.any { id -> id !in nextFingerprints }
        val changedIds = nextFingerprints.filter { (id, fingerprint) ->
            configurationFingerprints[id] != fingerprint
        }.keys + configurationFingerprints.keys.filter { id -> id !in nextFingerprints }
        if (changedIds.isNotEmpty()) {
            val changed = { key: String -> changedIds.any { id -> key.startsWith("$id\u0000") } }
            configuredFiles.keys.removeIf(changed)
            requestedFiles.removeIf(changed)
            retryCounts.keys.removeIf(changed)
        }
        configurationFingerprints = nextFingerprints
        if (definitionRegistered.get()) {
            if (environmentChanged || rootsChanged) reloadOpenFiles(next)
        }
    }

    /** Brackets a changing provider result so a concurrent cached-value computation cannot retain old roots. */
    fun prepareSnapshotPublication(previous: EternalScriptProjectSnapshot, next: EternalScriptProjectSnapshot) {
        if (definitionInputs(previous) != definitionInputs(next)) {
            Idea262Facade.invalidateScriptDefinitions(project)
        }
    }

    fun fileOpened(file: com.intellij.openapi.vfs.VirtualFile) {
        if (!definitionRegistered.get()) return
        val workspace = snapshot().workspaceFor(file) ?: return
        reloadFile(file, workspace.id, configurationFingerprint(workspace))
    }

    /**
     * IDEA may start its default Kotlin-script load concurrently with fileOpened(). If that
     * older load wins, request one more EternalScript load after IDEA has published it.
     */
    fun configurationChanged(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        if (!ApplicationManager.getApplication().isReadAccessAllowed) {
            return ReadAction.computeCancellable<Boolean, RuntimeException> {
                configurationChangedInReadAction(file)
            }
        }
        return configurationChangedInReadAction(file)
    }

    private fun configurationChangedInReadAction(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        ProgressManager.checkCanceled()
        val workspace = snapshot().workspaceFor(file) ?: return false
        val fingerprint = configurationFingerprint(workspace)
        val key = "${workspace.id}\u0000${file.url}"
        requestedFiles.remove(key)
        val dependencies = project.service<ScriptConfigurationsProvider>() as? ScriptDependencyAware ?: return false
        val expectedClasspath = workspace.environment.classpath().mapTo(linkedSetOf()) { uri ->
            Path.of(uri).toAbsolutePath().normalize()
        }
        ProgressManager.checkCanceled()
        val actualClasspath = dependencies.getScriptDependenciesClassFiles(file)
            .asSequence()
            .mapIndexedNotNull { index, dependency ->
                if (index and CANCELLATION_CHECK_MASK == 0) ProgressManager.checkCanceled()
                dependencyPath(dependency)
            }
            .toCollection(linkedSetOf())
        ProgressManager.checkCanceled()
        val valid = expectedClasspath.isNotEmpty() && actualClasspath.containsAll(expectedClasspath)
        if (valid) {
            configuredFiles[key] = fingerprint
            retryCounts.remove(key)
            return true
        }
        configuredFiles.remove(key)
        val retry = retryCounts.merge(key, 1, Int::plus) ?: 1
        if (retry <= MAX_CONFIGURATION_RETRIES && !project.isDisposed) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) reloadFile(file, workspace.id, fingerprint)
            }
        }
        return false
    }

    private fun reloadOpenFiles(current: EternalScriptProjectSnapshot) {
        if (project.isDisposed) return
        val openFiles = FileEditorManager.getInstance(project).openFiles
        openFiles.forEach { file ->
            val workspace = current.workspaceFor(file) ?: return@forEach
            reloadFile(file, workspace.id, configurationFingerprint(workspace))
        }
    }

    private fun reloadFile(file: com.intellij.openapi.vfs.VirtualFile, workspaceId: String, fingerprint: String) {
        val key = "$workspaceId\u0000${file.url}"
        val psi = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return
        if (configuredFiles[key] == fingerprint || !requestedFiles.add(key)) return
        val scheduled = runCatching { Idea262Facade.reloadScriptConfigurationSilently(psi) }
            .getOrDefault(false)
        if (!scheduled) {
            requestedFiles.remove(key)
        }
    }

    private companion object {
        const val MAX_CONFIGURATION_RETRIES: Int = 3
        const val CANCELLATION_CHECK_MASK: Int = 0x3f
    }

    private fun definitionInputs(snapshot: EternalScriptProjectSnapshot): Set<String> = snapshot.workspaces
        .mapTo(linkedSetOf()) { workspace ->
            "${workspace.id}\u0000${workspace.scriptRoot}\u0000${configurationFingerprint(workspace)}"
        }

    private fun configurationFingerprint(workspace: EternalScriptWorkspace): String =
        workspace.configurationFingerprint

}

private fun dependencyPath(file: VirtualFile): Path? = runCatching {
    val localFile = JarFileSystem.getInstance().getVirtualFileForJar(file) ?: file
    localFile.toNioPath().toAbsolutePath().normalize()
}.getOrNull()
