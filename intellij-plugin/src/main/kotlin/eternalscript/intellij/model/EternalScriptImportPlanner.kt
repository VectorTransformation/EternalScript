package eternalscript.intellij.model

/** Parses the name introduced by one file-local Kotlin import. */
internal object EternalScriptImportPlanner {
    fun importedName(importPath: String): String? {
        val alias = importPath.substringAfter(" as ", missingDelimiterValue = "").trim()
        if (alias.isNotEmpty()) return alias
        val target = importPath.substringBefore(" as ").trim()
        return target.takeUnless { value -> value.endsWith(".*") }?.substringAfterLast('.')
    }
}
