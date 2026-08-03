package eternalScript.core.environment

import eternalScript.core.script.classpath.ScriptPluginClasspathCapture

internal data class EnvironmentRefreshRequest(
    val capture: ScriptPluginClasspathCapture,
    val loadScripts: Boolean = false,
    val disabledPlugins: Set<String> = emptySet(),
    val metadataApplied: Boolean = false
) {
    fun merge(newer: EnvironmentRefreshRequest?): EnvironmentRefreshRequest {
        if (newer == null) return this
        val selectedCapture = if (newer.capture.revision >= capture.revision) {
            newer.capture
        } else {
            capture
        }
        val selectedMetadataApplied = when {
            selectedCapture.revision == capture.revision &&
                selectedCapture.revision == newer.capture.revision ->
                metadataApplied || newer.metadataApplied
            selectedCapture.revision == capture.revision -> metadataApplied
            else -> newer.metadataApplied
        }
        return EnvironmentRefreshRequest(
            capture = selectedCapture,
            loadScripts = loadScripts || newer.loadScripts,
            disabledPlugins = disabledPlugins + newer.disabledPlugins,
            metadataApplied = selectedMetadataApplied
        )
    }
}
