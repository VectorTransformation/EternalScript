package eternalScript.core.environment

import eternalScript.core.script.classpath.ScriptPluginClasspathCapture
import eternalScript.core.script.classpath.ScriptPluginClasspathRegistry
import eternalScript.core.script.classpath.ScriptPluginClasspathSnapshot
import eternalScript.core.the.Root
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
internal object ScriptEnvironmentCoordinator {
    fun clear() = ScriptPluginClasspathRegistry.clear()

    fun isReady(): Boolean = ScriptPluginClasspathRegistry.current() != null

    fun initialize(): WorkspaceUpdateResult = WorkspaceBootstrap.initialize()

    fun capturePluginClasspath(excludedPlugin: Plugin? = null): ScriptPluginClasspathCapture =
        ScriptPluginClasspathRegistry.capture(
            Root.plugins().asSequence()
                .filterNot { plugin -> plugin === excludedPlugin }
                .asIterable()
        )

    fun refreshClasspathAndWorkspace(
        capture: ScriptPluginClasspathCapture
    ): Pair<ScriptPluginClasspathSnapshot, WorkspaceUpdateResult> {
        val snapshot = ScriptPluginClasspathRegistry.refresh(capture)
        val workspace = WorkspaceManager.update(
            classpathEntries = snapshot.files.map(File::toPath),
            activePluginCount = snapshot.plugins.size
        )
        return snapshot to workspace
    }
}
