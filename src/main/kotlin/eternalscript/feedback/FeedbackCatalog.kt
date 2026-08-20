package eternalscript.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

internal const val FEEDBACK_CATALOG_SCHEMA: Int = 4
internal const val DEFAULT_FEEDBACK_LOCALE: String = "en_US"

internal data class FeedbackCatalogIssue(
    val file: String,
    val reason: String
)

internal data class FeedbackCatalogLoad(
    val catalogs: FeedbackCatalogs,
    val issues: List<FeedbackCatalogIssue>
)

internal class FeedbackCatalogs internal constructor(
    private val catalogs: Map<String, FeedbackCatalog>
) {
    fun catalog(locale: String): FeedbackCatalog =
        catalogs[normalizeLocale(locale)] ?: catalogs.getValue(normalizeLocale(DEFAULT_FEEDBACK_LOCALE))

    fun locales(): Set<String> = catalogs.keys
}

internal class FeedbackCatalog internal constructor(
    internal val locale: String,
    internal val templates: Map<FeedbackKey, CompiledFeedbackTemplate>
) {
    fun template(key: FeedbackKey): CompiledFeedbackTemplate = templates.getValue(key)
}

internal sealed interface FeedbackTemplateToken {
    data class Literal(val value: String) : FeedbackTemplateToken
    data class Placeholder(val name: String) : FeedbackTemplateToken
}

internal data class CompiledFeedbackTemplate(
    val tokens: List<FeedbackTemplateToken>
)

internal object FeedbackCatalogLoader {
    private val json = Json { ignoreUnknownKeys = false }
    private val placeholderPattern = Regex("<[a-z][a-z0-9_]*>")

    fun load(
        bundledCatalogs: Map<String, String>,
        externalDirectory: File
    ): FeedbackCatalogLoad {
        val bundled = bundledCatalogs.map { (locale, source) ->
            normalizeLocale(locale) to parseCatalog(
                file = "bundled:$locale",
                expectedLocale = locale,
                source = source,
                requireEveryMessage = true
            )
        }.toMap()
        check(normalizeLocale(DEFAULT_FEEDBACK_LOCALE) in bundled) {
            "Bundled feedback catalog $DEFAULT_FEEDBACK_LOCALE is missing"
        }

        val catalogs = bundled.toMutableMap()
        val issues = mutableListOf<FeedbackCatalogIssue>()
        val externalFiles = externalDirectory.listFiles().orEmpty()
            .filter { file ->
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.extension.equals("json", ignoreCase = true)
            }
            .sortedWith(compareBy({ file -> file.name.lowercase(Locale.ROOT) }, File::getName))
        val duplicateLocales = externalFiles.groupBy { file -> normalizeLocale(file.nameWithoutExtension) }
            .filterValues { files -> files.size > 1 }
            .keys

        externalFiles.forEach { file ->
            val locale = normalizeLocale(file.nameWithoutExtension)
            if (locale in duplicateLocales) {
                issues += FeedbackCatalogIssue(file.name, "Another catalog has the same locale name")
                return@forEach
            }
            val override = runCatching {
                parseCatalog(
                    file = file.absolutePath,
                    expectedLocale = file.nameWithoutExtension,
                    source = file.readText(Charsets.UTF_8),
                    requireEveryMessage = false
                )
            }.getOrElse { error ->
                issues += FeedbackCatalogIssue(file.name, error.message ?: error.javaClass.simpleName)
                return@forEach
            }
            val base = catalogs[locale]
                ?: bundled.getValue(normalizeLocale(DEFAULT_FEEDBACK_LOCALE))
            catalogs[locale] = FeedbackCatalog(
                locale,
                FeedbackKey.entries.associateWith { key ->
                    override.templates[key] ?: base.template(key)
                }
            )
        }

        return FeedbackCatalogLoad(FeedbackCatalogs(catalogs.toMap()), issues.toList())
    }

    private fun parseCatalog(
        file: String,
        expectedLocale: String,
        source: String,
        requireEveryMessage: Boolean
    ): FeedbackCatalog {
        val root = runCatching { json.parseToJsonElement(source).jsonObject }
            .getOrElse { error -> throw IllegalArgumentException("Invalid JSON: ${error.message}", error) }
        val unknownRootKeys = root.keys - setOf("_schema", "_locale", "messages")
        require(unknownRootKeys.isEmpty()) {
            "Unknown catalog field(s): ${unknownRootKeys.sorted().joinToString()}"
        }
        val schemaPrimitive = root["_schema"] as? JsonPrimitive
        val schema = schemaPrimitive?.takeUnless(JsonPrimitive::isString)?.intOrNull
        require(schema == FEEDBACK_CATALOG_SCHEMA) {
            "Expected _schema=$FEEDBACK_CATALOG_SCHEMA but found ${schema ?: "none"}"
        }
        val declaredLocale = (root["_locale"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?: throw IllegalArgumentException("_locale is required")
        require(normalizeLocale(declaredLocale) == normalizeLocale(expectedLocale)) {
            "Catalog locale '$declaredLocale' does not match '$expectedLocale'"
        }
        val messages = root["messages"] as? JsonObject
            ?: throw IllegalArgumentException("messages must be a JSON object")
        val keysById = FeedbackKey.entries.associateBy(FeedbackKey::id)
        val unknown = messages.keys - keysById.keys
        require(unknown.isEmpty()) { "Unknown message key(s): ${unknown.sorted().joinToString()}" }
        if (requireEveryMessage) {
            val missing = keysById.keys - messages.keys
            require(missing.isEmpty()) { "Missing message key(s): ${missing.sorted().joinToString()}" }
        }

        val templates = messages.map { (id, element) ->
            val key = keysById.getValue(id)
            val template = (element as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?: throw IllegalArgumentException("Message '$id' must be a string")
            key to compileTemplate(key, template, file)
        }.toMap()
        return FeedbackCatalog(normalizeLocale(declaredLocale), templates)
    }

    private fun compileTemplate(
        key: FeedbackKey,
        template: String,
        file: String
    ): CompiledFeedbackTemplate {
        require('\n' !in template && '\r' !in template) {
            "Message '${key.id}' in $file must be a single line"
        }
        val tokens = mutableListOf<FeedbackTemplateToken>()
        var position = 0
        placeholderPattern.findAll(template).forEach { match ->
            if (match.range.first > position) {
                tokens += FeedbackTemplateToken.Literal(template.substring(position, match.range.first))
            }
            tokens += FeedbackTemplateToken.Placeholder(match.value.substring(1, match.value.length - 1))
            position = match.range.last + 1
        }
        if (position < template.length) tokens += FeedbackTemplateToken.Literal(template.substring(position))
        require(tokens.filterIsInstance<FeedbackTemplateToken.Literal>().none { token ->
            '<' in token.value || '>' in token.value
        }) {
            "Message '${key.id}' in $file contains an invalid placeholder"
        }
        val placeholders = tokens.filterIsInstance<FeedbackTemplateToken.Placeholder>()
            .map(FeedbackTemplateToken.Placeholder::name)
        require(placeholders.toSet() == key.placeholders && placeholders.size == key.placeholders.size) {
            "Message '${key.id}' in $file requires placeholders ${key.placeholders.sorted()}"
        }
        return CompiledFeedbackTemplate(tokens.toList())
    }
}

internal fun normalizeLocale(locale: String): String =
    locale.trim().replace('-', '_').lowercase(Locale.ROOT)
