package eternalScript.core.architecture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PresentationBoundaryContractTest {
    private val sourceRoot = locateProductionSources()

    @Test
    fun `domain and runtime sources do not own locale keys`() {
        val localeKeys = bundledLocaleKeys()
        val violations = boundarySources().flatMap { path ->
            val source = read(path)
            buildList {
                if (LOCALE_KEY_FIELD.containsMatchIn(source)) {
                    add("${relative(path)} declares a locale-key field")
                }
                localeKeys
                    .filter { key -> source.contains("\"$key\"") }
                    .forEach { key -> add("${relative(path)} owns locale key $key") }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Domain/runtime sources must expose semantic values and leave locale-key mapping " +
                "to the feedback presenter:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `LocaleCatalog stays in localization integration files`() {
        assertOwnersConfinedTo(
            symbol = "LocaleCatalog",
            allowedOwners = setOf(
                "eternalScript/core/feedback/LocaleCatalog.kt",
                "eternalScript/core/manager/ReloadManager.kt",
                "eternalScript/core/feedback/LocalizedUserFeedback.kt"
            )
        )
    }

    @Test
    fun `CommandSender stays in script command API and localized feedback adapter`() {
        assertOwnersConfinedTo(
            symbol = "CommandSender",
            allowedOwners = setOf(
                "eternalScript/core/script/command/ScriptCommand.kt",
                "eternalScript/core/script/command/ScriptCommandBuilder.kt",
                "eternalScript/core/feedback/LocalizedUserFeedback.kt"
            )
        )
    }

    private fun boundarySources(): List<Path> {
        val roots = listOf(
            "eternalScript/core/manager",
            "eternalScript/core/operation",
            "eternalScript/core/workspace",
            "eternalScript/core/script/generation"
        )
        return roots
            .flatMap { root -> kotlinSources(sourceRoot.resolve(root)) }
            .filterNot { path -> relative(path) in LOCALIZATION_INTEGRATION_FILES }
    }

    private fun assertOwnersConfinedTo(symbol: String, allowedOwners: Set<String>) {
        val pattern = Regex("\\b${Regex.escape(symbol)}\\b")
        val actualOwners = kotlinSources(sourceRoot)
            .filter { path -> pattern.containsMatchIn(read(path)) }
            .mapTo(linkedSetOf(), ::relative)
        val unexpectedOwners = actualOwners - allowedOwners

        assertTrue(
            unexpectedOwners.isEmpty(),
            "$symbol crossed its adapter/API boundary: ${unexpectedOwners.sorted()}"
        )
    }

    private fun bundledLocaleKeys(): Set<String> {
        val path = "/lang/en_US.json"
        val content = assertNotNull(javaClass.getResourceAsStream(path), path)
            .bufferedReader(StandardCharsets.UTF_8)
            .use { reader -> reader.readText() }
        return Json.decodeFromString<JsonObject>(content).keys - "_schema"
    }

    private fun kotlinSources(root: Path): List<Path> {
        val sources = mutableListOf<Path>()
        Files.walk(root).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
                .forEach(sources::add)
        }
        return sources.sortedBy(::relative)
    }

    private fun read(path: Path): String =
        Files.readString(path, StandardCharsets.UTF_8)

    private fun relative(path: Path): String =
        sourceRoot.relativize(path).toString().replace('\\', '/')

    private fun locateProductionSources(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            val candidate = current.resolve("src/main/kotlin")
            if (Files.isDirectory(candidate)) return candidate
            current = current.parent
                ?: error("Could not locate src/main/kotlin from ${Path.of("").toAbsolutePath()}")
        }
    }

    private companion object {
        val LOCALIZATION_INTEGRATION_FILES = setOf(
            "eternalScript/core/manager/ReloadManager.kt"
        )
        val LOCALE_KEY_FIELD = Regex(
            """\b(?:val|var)\s+(?:labelKey|scopeKey|localeKey|messageKey|translationKey)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
