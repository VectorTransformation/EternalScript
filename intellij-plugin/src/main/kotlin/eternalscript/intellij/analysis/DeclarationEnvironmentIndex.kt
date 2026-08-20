package eternalscript.intellij.analysis

import eternalscript.intellij.model.EternalScriptFileAbi
import eternalscript.intellij.model.EternalScriptRenderedDeclaration

/**
 * Name-indexed view of the declarations that can be referenced from another script.
 *
 * The old implementation rebuilt and grouped every declaration in the workspace for every file
 * being analyzed. This index is built once per analysis pass and updated as fresh ABIs replace the
 * previous ones. The per-file view only materializes declarations for names that inference asks for.
 */
internal class DeclarationEnvironmentIndex(
    initialAbis: Map<String, EternalScriptFileAbi>,
    private val visibleUrls: Set<String>
) {
    private val declarationsByUrl = linkedMapOf<String, List<EternalScriptRenderedDeclaration>>()
    private val declarationsByName = linkedMapOf<
        String,
        LinkedHashMap<String, List<EternalScriptRenderedDeclaration>>
    >()

    init {
        initialAbis.forEach { (url, abi) ->
            if (url in visibleUrls) replace(url, abi)
        }
    }

    fun replace(url: String, abi: EternalScriptFileAbi) {
        remove(url)
        if (url !in visibleUrls) return
        val declarations = abi.callables + abi.classifiers
        declarationsByUrl[url] = declarations
        declarations.groupBy(EternalScriptRenderedDeclaration::name).forEach { (name, named) ->
            declarationsByName.getOrPut(name) { linkedMapOf() }[url] = named
        }
    }

    fun excluding(sourceUrl: String): Map<String, List<EternalScriptRenderedDeclaration>> =
        ExcludingMap(sourceUrl)

    private fun remove(url: String) {
        val previous = declarationsByUrl.remove(url) ?: return
        previous.asSequence().map(EternalScriptRenderedDeclaration::name).distinct().forEach { name ->
            declarationsByName[name]?.let { owners ->
                owners.remove(url)
                if (owners.isEmpty()) declarationsByName.remove(name)
            }
        }
    }

    private inner class ExcludingMap(
        private val excludedUrl: String
    ) : AbstractMap<String, List<EternalScriptRenderedDeclaration>>() {
        private val materialized = hashMapOf<String, List<EternalScriptRenderedDeclaration>>()

        override fun get(key: String): List<EternalScriptRenderedDeclaration>? {
            if (key in materialized) return materialized.getValue(key).takeIf { it.isNotEmpty() }
            val declarations = declarationsByName[key].orEmpty().asSequence()
                .filter { (url, _) -> url != excludedUrl }
                .flatMap { (_, values) -> values.asSequence() }
                .toList()
            materialized[key] = declarations
            return declarations.takeIf { it.isNotEmpty() }
        }

        override val entries: Set<Map.Entry<String, List<EternalScriptRenderedDeclaration>>>
            get() = declarationsByName.keys.mapNotNull { name ->
                get(name)?.let { declarations -> name to declarations }
            }.toMap().entries
    }
}
