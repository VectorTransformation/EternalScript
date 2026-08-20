package eternalscript.intellij.model

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@KaAllowAnalysisOnEdt
internal class EternalScriptConflictAnalyzerTest : BasePlatformTestCase() {
    fun testMatchesRuntimeDeclarationRulesWithoutCrossFileImportConflicts() {
        val first = myFixture.addFileToProject(
            "scripts/first.eternal.kts",
            """
                import java.util.Date
                val duplicate: Int = 1
                private val privateName: Int = 1
                fun same(value: Int): Int = value
                fun overloaded(value: Int): Int = value
            """.trimIndent()
        ) as KtFile
        val second = myFixture.addFileToProject(
            "scripts/second.eternal.kts",
            """
                import java.sql.Date
                val duplicate: Int = 2
                private val privateName: Int = 2
                fun same(other: Int): Int = other
                fun overloaded(value: String): String = value
            """.trimIndent()
        ) as KtFile

        val conflicts = EternalScriptConflictAnalyzer.analyzeAbis(
            mapOf(
                "first.eternal.kts" to render(first),
                "second.eternal.kts" to render(second)
            )
        )
        val firstDuplicate = declaration(first, "duplicate")
        val secondDuplicate = declaration(second, "duplicate")
        val firstSame = declaration(first, "same")
        val secondSame = declaration(second, "same")

        assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(firstDuplicate)])
        assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(secondDuplicate)])
        assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(firstSame)])
        assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(secondSame)])
        assertNull(conflicts[location(declaration(first, "privateName"))])
        assertNull(conflicts[location(declaration(second, "privateName"))])
        assertNull(conflicts[location(declaration(first, "overloaded"))])
        assertNull(conflicts[location(declaration(second, "overloaded"))])

        assertNull(conflicts[EternalScriptSourceLocation(first.virtualFile.url, first.importDirectives.single().textOffset)])
        assertNull(conflicts[EternalScriptSourceLocation(second.virtualFile.url, second.importDirectives.single().textOffset)])

        assertNull(conflicts[EternalScriptSourceLocation(first.virtualFile.url, first.importDirectives.single().textOffset)])
        assertNull(conflicts[EternalScriptSourceLocation(second.virtualFile.url, second.importDirectives.single().textOffset)])
    }

    fun testRenameChecksAllSharedDeclarationsButOnlyAffectedFileImports() {
        val first = myFixture.addFileToProject(
            "scripts/first.eternal.kts",
            "fun original(value: Int): Int = value"
        ) as KtFile
        val second = myFixture.addFileToProject(
            "scripts/second.eternal.kts",
            "import example.plugin.ImportedApi\nfun occupied(value: Int): Int = value"
        ) as KtFile
        val files = mapOf(
            "first.eternal.kts" to render(first),
            "second.eternal.kts" to render(second)
        )
        val target = declaration(first, "original")

        val declarationConflicts = EternalScriptConflictAnalyzer.renameConflicts(
            files,
            files.keys,
            setOf("first.eternal.kts"),
            target,
            "occupied",
            emptyList()
        )
        val duplicate = assertIs<EternalScriptConflict.DuplicateDeclaration>(declarationConflicts.single())
        assertEquals("occupied", duplicate.name)
        assertEquals(listOf("second.eternal.kts"), duplicate.paths)

        val unaffectedImportConflicts = EternalScriptConflictAnalyzer.renameConflicts(
            files,
            files.keys,
            setOf("first.eternal.kts"),
            target,
            "ImportedApi",
            emptyList()
        )
        assertTrue(unaffectedImportConflicts.isEmpty())

        val importConflicts = EternalScriptConflictAnalyzer.renameConflicts(
            files,
            files.keys,
            setOf("first.eternal.kts", "second.eternal.kts"),
            target,
            "ImportedApi",
            listOf("another.api.ImportedApi")
        )
        val import = assertIs<EternalScriptConflict.ConflictingImport>(importConflicts.single())
        assertEquals("ImportedApi", import.name)
        assertTrue("example.plugin.ImportedApi" in import.imports)
        assertTrue("another.api.ImportedApi" in import.imports)
    }

    fun testRenameIgnoresDeclarationsThatAreNotInTheActiveSharedModel() {
        val active = myFixture.addFileToProject(
            "scripts/active.eternal.kts",
            "fun original(value: Int): Int = value"
        ) as KtFile
        val disabled = myFixture.addFileToProject(
            "scripts/-disabled.eternal.kts",
            "fun disabledOnly(value: Int): Int = value"
        ) as KtFile
        val files = mapOf(
            "active.eternal.kts" to render(active),
            "-disabled.eternal.kts" to render(disabled)
        )

        val conflicts = EternalScriptConflictAnalyzer.renameConflicts(
            files,
            sharedDeclarationPaths = setOf("active.eternal.kts"),
            importScopePaths = setOf("active.eternal.kts"),
            target = declaration(active, "original"),
            newName = "disabledOnly",
            defaultImports = emptyList()
        )

        assertTrue(conflicts.isEmpty())
    }

    fun testFileLocalImportedTypesDisambiguateFunctionSignatures() {
        val utilDate = myFixture.addFileToProject(
            "scripts/util-date.eternal.kts",
            "import java.util.Date\nfun dated(value: Date): Long = value.time"
        ) as KtFile
        val sqlDate = myFixture.addFileToProject(
            "scripts/sql-date.eternal.kts",
            "import java.sql.Date\nfun dated(value: Date): Long = value.time"
        ) as KtFile
        val sameUtilDate = myFixture.addFileToProject(
            "scripts/same-util-date.eternal.kts",
            "import java.util.Date\nfun dated(value: Date): Long = value.time"
        ) as KtFile
        val utilAbi = render(utilDate)
        val sqlAbi = render(sqlDate)
        val sameUtilAbi = render(sameUtilDate)

        val distinctTypeConflicts = EternalScriptConflictAnalyzer.analyzeAbis(
            mapOf("util-date.eternal.kts" to utilAbi, "sql-date.eternal.kts" to sqlAbi)
        )
        assertTrue(distinctTypeConflicts.isEmpty())

        val sameTypeConflicts = EternalScriptConflictAnalyzer.analyzeAbis(
            mapOf("util-date.eternal.kts" to utilAbi, "same-util-date.eternal.kts" to sameUtilAbi)
        )
        assertIs<EternalScriptConflict.DuplicateDeclaration>(sameTypeConflicts[location(declaration(utilDate, "dated"))])
        assertIs<EternalScriptConflict.DuplicateDeclaration>(
            sameTypeConflicts[location(declaration(sameUtilDate, "dated"))]
        )
    }

    fun testSemanticSignaturesExpandAliasesNormalizeGenericsAndKeepExtensionReceiversDistinct() {
        val first = myFixture.addFileToProject(
            "types/first.kt",
            """
                typealias Text = String
                fun aliased(value: Text): Unit = Unit
                fun <T> generic(value: T): Unit = Unit
                fun callback(value: (named: Int) -> Unit): Unit = Unit
                @Target(AnnotationTarget.TYPE)
                annotation class TypeMarker
                fun annotated(value: @TypeMarker String): Unit = Unit
                val String.extension: Int get() = length
            """.trimIndent()
        ) as KtFile
        val second = myFixture.addFileToProject(
            "types/second.kt",
            """
                fun aliased(value: String): Unit = Unit
                fun <U> generic(value: U): Unit = Unit
                fun callback(value: (Int) -> Unit): Unit = Unit
                fun annotated(value: String): Unit = Unit
                val Int.extension: Int get() = this
            """.trimIndent()
        ) as KtFile
        val third = myFixture.addFileToProject(
            "types/third.kt",
            "val String.extension: Int get() = length"
        ) as KtFile
        val firstAbi = render(first)
        val secondAbi = render(second)
        val thirdAbi = render(third)

        val conflicts = EternalScriptConflictAnalyzer.analyzeAbis(
            mapOf("first.kt" to firstAbi, "second.kt" to secondAbi)
        )
        listOf("aliased", "generic", "callback", "annotated").forEach { name ->
            assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(declaration(first, name))])
            assertIs<EternalScriptConflict.DuplicateDeclaration>(conflicts[location(declaration(second, name))])
        }
        assertNull(conflicts[location(declaration(first, "extension"))])
        assertNull(conflicts[location(declaration(second, "extension"))])

        val extensionConflict = EternalScriptConflictAnalyzer.analyzeAbis(
            mapOf("first.kt" to firstAbi, "third.kt" to thirdAbi)
        )
        assertIs<EternalScriptConflict.DuplicateDeclaration>(
            extensionConflict[location(declaration(first, "extension"))]
        )
        assertIs<EternalScriptConflict.DuplicateDeclaration>(
            extensionConflict[location(declaration(third, "extension"))]
        )
    }

    private fun render(file: KtFile): EternalScriptFileAbi =
        allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(file, null) }

    private fun declaration(file: KtFile, name: String): KtNamedDeclaration =
        (file.script?.declarations ?: file.declarations)
            .filterIsInstance<KtNamedDeclaration>()
            .first { it.name == name }

    private fun location(declaration: KtNamedDeclaration): EternalScriptSourceLocation =
        EternalScriptSourceLocation(requireNotNull(declaration.containingKtFile.virtualFile).url, declaration.textOffset)
}
