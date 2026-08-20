package eternalscript.intellij.model

import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal object EternalScriptConflictAnalyzer {
    fun analyzeAbis(files: Map<String, EternalScriptFileAbi>): Map<EternalScriptSourceLocation, EternalScriptConflict> {
        val conflicts = linkedMapOf<EternalScriptSourceLocation, EternalScriptConflict>()
        val declarations = linkedMapOf<String, MutableList<Pair<String, EternalScriptRenderedDeclaration>>>()
        files.forEach { (path, abi) ->
            (abi.callables + abi.classifiers).forEach { declaration ->
                declarations.getOrPut(declaration.signature) { mutableListOf() } += path to declaration
            }
        }
        declarations.values.forEach { owners ->
            val paths = owners.map(Pair<String, EternalScriptRenderedDeclaration>::first).distinct().sorted()
            if (paths.size < 2) return@forEach
            val name = owners.first().second.name
            val conflict = EternalScriptConflict.DuplicateDeclaration(name, paths)
            owners.forEach { (_, declaration) ->
                declaration.mappings.firstOrNull()?.source?.let { source -> conflicts[source] = conflict }
            }
        }

        return conflicts
    }

    fun renameConflicts(
        files: Map<String, EternalScriptFileAbi>,
        sharedDeclarationPaths: Set<String>,
        importScopePaths: Set<String>,
        target: KtNamedDeclaration,
        newName: String,
        defaultImports: List<String>
    ): List<EternalScriptConflict> {
        val targetLocation = location(target)
        val renderedTarget = files.values.asSequence()
            .flatMap { abi -> (abi.callables + abi.classifiers).asSequence() }
            .firstOrNull { declaration ->
                declaration.mappings.any { mapping -> mapping.source == targetLocation }
            } ?: return emptyList()
        val renamedSignature = renderedTarget.signature.replaceName(renderedTarget.name, newName)
        val duplicatePaths = files.asSequence()
            .filter { (path, _) -> path in sharedDeclarationPaths }
            .mapNotNull { (path, abi) ->
                val duplicate = (abi.callables + abi.classifiers).any { declaration ->
                    declaration.symbolId != renderedTarget.symbolId && declaration.signature == renamedSignature
                }
                path.takeIf { duplicate }
            }
            .distinct()
            .sorted()
            .toList()
        val conflicts = mutableListOf<EternalScriptConflict>()
        if (duplicatePaths.isNotEmpty()) {
            conflicts += EternalScriptConflict.DuplicateDeclaration(newName, duplicatePaths)
        }

        val conflictingImports = buildList {
            files.asSequence()
                .filter { (path, _) -> path in importScopePaths }
                .flatMap { (_, abi) -> abi.importEntries.asSequence() }
                .filter { entry -> entry.name == newName }
                .mapTo(this) { entry -> entry.importPath }
            defaultImports.asSequence()
                .filter { value -> EternalScriptImportPlanner.importedName(value) == newName }
                .toCollection(this)
        }.distinct().sorted()
        if (conflictingImports.isNotEmpty()) {
            conflicts += EternalScriptConflict.ConflictingImport(newName, conflictingImports)
        }
        return conflicts
    }

    private fun String.replaceName(oldName: String, newName: String): String = when {
        startsWith("property:$oldName") -> replaceRange("property:".length, "property:".length + oldName.length, newName)
        startsWith("function:$oldName") -> replaceRange("function:".length, "function:".length + oldName.length, newName)
        startsWith("classifier:$oldName") -> replaceRange("classifier:".length, "classifier:".length + oldName.length, newName)
        else -> this
    }

    private fun location(declaration: KtNamedDeclaration): EternalScriptSourceLocation =
        EternalScriptSourceLocation(requireNotNull(declaration.containingKtFile.virtualFile).url, declaration.textOffset)

}
