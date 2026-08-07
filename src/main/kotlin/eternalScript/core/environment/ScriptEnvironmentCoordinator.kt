package eternalScript.core.environment

import eternalScript.core.script.classpath.ScriptPluginClasspathCapture
import eternalScript.core.script.classpath.ScriptPluginClasspathRegistry
import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import eternalScript.core.runtime.ServerAccess
import eternalScript.core.workspace.WorkspaceBootstrap
import eternalScript.core.workspace.WorkspaceManager
import eternalScript.core.workspace.WorkspaceUpdateResult
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * Owns the plugin classpath snapshot and the generated Kotlin workspace.
 * Script operations may ask it for a coherent snapshot without owning the
 * workspace reconciliation or its user-facing diagnostics.
 */
internal class ScriptEnvironmentCoordinator(
    private val server: ServerAccess,
    private val classpathRegistry: ScriptPluginClasspathRegistry,
    private val workspace: WorkspaceManager,
    private val bootstrap: WorkspaceBootstrap
) {
    fun clear() = classpathRegistry.clear()

    fun isReady(): Boolean = classpathRegistry.current() != null

    fun initialize(): WorkspaceUpdateResult = bootstrap.initialize()

    fun capturePluginClasspath(excludedPlugin: Plugin? = null): ScriptPluginClasspathCapture =
        classpathRegistry.capture(
            server.plugins.asSequence()
                .filterNot { plugin -> plugin === excludedPlugin }
                .asIterable()
        )

    fun refreshClasspathAndWorkspace(
        capture: ScriptPluginClasspathCapture
    ): Pair<ScriptPluginClasspathSnapshot, WorkspaceUpdateResult> {
        val snapshot = classpathRegistry.refresh(capture)
        val workspaceResult = workspace.update(
            classpathEntries = snapshot.files.map(File::toPath),
            activePluginCount = snapshot.plugins.size
        )
        return snapshot to workspaceResult
    }
}
