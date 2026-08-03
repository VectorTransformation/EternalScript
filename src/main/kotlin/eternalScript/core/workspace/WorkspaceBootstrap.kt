package eternalScript.core.workspace

import eternalScript.core.data.Resource
import eternalScript.core.script.data.ScriptSuffix
import eternalScript.core.the.Root
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Creates the plugin-owned workspace and restores bundled resources.
 *
 * Project execution remains owned by DataManager/ScriptManager; this object
 * only performs the one-time workspace bootstrap and returns its diagnostics.
 */
internal object WorkspaceBootstrap {
    fun initialize(): WorkspaceUpdateResult {
        listOf(
            Resource.DATA_FOLDER,
            Resource.LIBS,
            Resource.CACHE
        ).forEach(Resource::make)

        saveResource(Resource.SCRIPTS, *ScriptSuffix.SCRIPT.suffix)
        saveResource(Resource.LANG, *ScriptSuffix.LANG.suffix)

        return WorkspaceManager.initialize(Resource.DATA_FOLDER.toPath())
    }

    private fun saveResource(resource: Resource, vararg extension: String) {
        if (resource.exists()) return

        val jarFile = File(javaClass.protectionDomain.codeSource.location.toURI())
        val fileName = resource.file.nameWithoutExtension
        ZipFile(jarFile).use { jar ->
            jar.entries()
                .asSequence()
                .map(ZipEntry::getName)
                .filter { name ->
                    name.startsWith(fileName) && extension.any(name::endsWith)
                }
                .forEach { name ->
                    Root.INSTANCE.saveResource(name, false)
                }
        }
    }
}
