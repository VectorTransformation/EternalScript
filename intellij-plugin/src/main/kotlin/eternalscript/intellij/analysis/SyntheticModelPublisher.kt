package eternalscript.intellij.analysis

import eternalscript.intellij.model.EternalScriptDeclarationRenderer
import eternalscript.intellij.model.EternalScriptFileAbi
import eternalscript.intellij.model.EternalScriptGeneratedFile
import eternalscript.intellij.model.EternalScriptWorkspace
import eternalscript.intellij.workspace.EternalScriptWorkspaceDescriptor
import org.jetbrains.kotlin.name.FqName

internal data class PublishedWorkspace(
    val workspace: EternalScriptWorkspace,
    val changedDeclarationNames: Set<String>
)

internal object SyntheticModelPublisher {
    fun build(
        descriptor: EternalScriptWorkspaceDescriptor,
        files: Map<String, IndexedScriptFile>,
        abis: Map<String, EternalScriptFileAbi>,
        previous: EternalScriptWorkspace?
    ): PublishedWorkspace {
        val sourceUrls = files.keys.toCollection(linkedSetOf())
        // Runtime activation remains separate metadata. IntelliJ treats every indexed source as
        // an ordinary project source, including leading-'-' files and directories.
        val ideAbis = sourceUrls.mapNotNull(abis::get)
        val packageName = "eternalscript.ide.synthetic.w${descriptor.id}"
        val receiverName = "EternalScriptSharedReceiver_${descriptor.id}"
        val syntheticInputsUnchanged = syntheticInputsUnchanged(
            previous,
            sourceUrls,
            abis,
            packageName,
            receiverName
        )
        val generated = if (syntheticInputsUnchanged) {
            requireNotNull(previous).generatedFiles
        } else {
            EternalScriptDeclarationRenderer.render(
                descriptor.id,
                packageName,
                receiverName,
                ideAbis
            ).map { candidate ->
                previous?.generatedFile(candidate.fileName)
                    ?.takeIf { old ->
                        old.packageName == candidate.packageName && old.textDigest == candidate.textDigest
                    }
                    ?: candidate
            }
        }
        val changedNames = if (
            syntheticInputsUnchanged &&
            previous != null &&
            previous.manifestDigest == descriptor.manifestDigest
        ) {
            emptySet()
        } else {
            changedDeclarationNames(
                descriptor,
                sourceUrls,
                abis,
                previous
            )
        }
        val configurationFingerprint = if (
            syntheticInputsUnchanged &&
            previous != null &&
            previous.manifestDigest == descriptor.manifestDigest
        ) {
            previous.configurationFingerprint
        } else {
            configurationFingerprint(descriptor.manifestDigest)
        }
        val digest = if (
            previous != null &&
            previous.manifestDigest == descriptor.manifestDigest &&
            previous.scriptRoot == descriptor.scriptRoot &&
            previous.sourceUrls == sourceUrls &&
            generatedSignatures(previous.generatedFiles) == generatedSignatures(generated)
        ) {
            previous.digest
        } else {
            workspaceDigest(descriptor, files, generated)
        }
        return PublishedWorkspace(
            EternalScriptWorkspace(
                descriptor.id,
                descriptor.manifest,
                descriptor.manifestDigest,
                descriptor.scriptRoot,
                descriptor.environment,
                FqName(packageName),
                receiverName,
                sourceUrls,
                abis,
                changedNames,
                generated,
                configurationFingerprint,
                digest
            ),
            changedNames
        )
    }

    private fun syntheticInputsUnchanged(
        previous: EternalScriptWorkspace?,
        sourceUrls: Set<String>,
        abis: Map<String, EternalScriptFileAbi>,
        packageName: String,
        receiverName: String
    ): Boolean = previous != null &&
        previous.packageName.asString() == packageName &&
        previous.receiverName == receiverName &&
        previous.sourceUrls == sourceUrls &&
        sourceUrls.all { url -> previous.fileAbis[url]?.abiDigest == abis[url]?.abiDigest }

    private fun changedDeclarationNames(
        descriptor: EternalScriptWorkspaceDescriptor,
        sourceUrls: Set<String>,
        abis: Map<String, EternalScriptFileAbi>,
        previous: EternalScriptWorkspace?
    ): Set<String> {
        val changedNames = linkedSetOf<String>()
        val previousSources = previous?.sourceUrls.orEmpty()
        (previousSources + sourceUrls).forEach { url ->
            val old = previous?.fileAbis?.get(url)
            val new = abis[url]
            if ((url in previousSources) != (url in sourceUrls) || old?.abiDigest != new?.abiDigest) {
                old?.exportedNames()?.let(changedNames::addAll)
                new?.exportedNames()?.let(changedNames::addAll)
            }
        }
        if (previous?.manifestDigest != descriptor.manifestDigest) {
            val previousAbis = previous?.fileAbis.orEmpty()
            previous?.sourceUrls.orEmpty()
                .mapNotNull(previousAbis::get)
                .forEach { abi -> changedNames += abi.exportedNames() }
            sourceUrls.mapNotNull(abis::get).forEach { abi -> changedNames += abi.exportedNames() }
        }
        return changedNames
    }

    private fun configurationFingerprint(manifestDigest: String): String = manifestDigest

    private fun workspaceDigest(
        descriptor: EternalScriptWorkspaceDescriptor,
        files: Map<String, IndexedScriptFile>,
        generatedFiles: List<EternalScriptGeneratedFile>
    ): String = EternalScriptDeclarationRenderer.digest(buildString {
        appendLine(descriptor.manifestDigest)
        files.values.sortedBy { file -> file.path.toString() }.forEach { file ->
            appendLine(descriptor.scriptRoot.relativize(file.path).toString())
            appendLine(file.url)
        }
        generatedSignatures(generatedFiles).forEach { signature -> appendLine(signature) }
    })

    private fun EternalScriptFileAbi.exportedNames(): Set<String> = buildSet {
        addAll(declaredNames)
    }

    private fun generatedSignatures(
        files: List<EternalScriptGeneratedFile>
    ): List<String> = files.map { file ->
        "${file.fileName}\u0000${file.packageName.asString()}\u0000${file.textDigest}"
    }
}
