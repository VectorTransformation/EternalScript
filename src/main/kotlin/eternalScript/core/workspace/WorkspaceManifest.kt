package eternalScript.core.workspace

import java.util.Locale

internal data class WorkspaceManifest(
    val schemaVersion: Int,
    val templateVersion: String,
    val files: Map<String, ManagedFileRecord>
) {
    companion object {
        fun empty(templateVersion: String) = WorkspaceManifest(
            schemaVersion = DefaultWorkspaceTemplates.SCHEMA_VERSION,
            templateVersion = templateVersion,
            files = emptyMap()
        )
    }
}

internal data class ManagedFileRecord(
    val path: String,
    val templateVersion: String,
    val templateSha256: String,
    val appliedSha256: String?
) {
    companion object {
        fun managed(template: WorkspaceTemplate, templateVersion: String) =
            ManagedFileRecord(
                path = template.target,
                templateVersion = templateVersion,
                templateSha256 = sha256(template.content),
                appliedSha256 = sha256(template.content)
            )

        fun unmanaged(template: WorkspaceTemplate, templateVersion: String) =
            ManagedFileRecord(
                path = template.target,
                templateVersion = templateVersion,
                templateSha256 = sha256(template.content),
                appliedSha256 = null
            )
    }
}

internal object WorkspaceManifestCodec {
    private val schemaPattern = Regex(""""schemaVersion"\s*:\s*(\d+)""")
    private val templateVersionPattern =
        Regex(""""templateVersion"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val recordPattern = Regex(
        """\{\s*"path"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*""" +
            """"templateVersion"\s*:\s*"((?:\\.|[^"\\])*)"\s*,\s*""" +
            """"templateSha256"\s*:\s*"([a-fA-F0-9]{64})"\s*,\s*""" +
            """"appliedSha256"\s*:\s*(null|"([a-fA-F0-9]{64})")\s*\}"""
    )

    fun encode(manifest: WorkspaceManifest): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": ${manifest.schemaVersion},")
        appendLine("  \"templateVersion\": \"${escapeJson(manifest.templateVersion)}\",")
        appendLine("  \"files\": [")
        val files = manifest.files.values.sortedBy(ManagedFileRecord::path)
        files.forEachIndexed { index, file ->
            append("    {\"path\": \"")
            append(escapeJson(file.path))
            append("\", \"templateVersion\": \"")
            append(escapeJson(file.templateVersion))
            append("\", \"templateSha256\": \"")
            append(file.templateSha256)
            append("\", \"appliedSha256\": ")
            if (file.appliedSha256 == null) {
                append("null")
            } else {
                append('"').append(file.appliedSha256).append('"')
            }
            append('}')
            if (index != files.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    fun decode(content: String): WorkspaceManifest {
        val schemaVersion = schemaPattern.find(content)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: error("Workspace manifest has no valid schemaVersion.")
        require(schemaVersion > 0) {
            "Workspace manifest schemaVersion must be positive."
        }
        val templateVersion = templateVersionPattern.find(content)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeJson)
            ?: error("Workspace manifest has no valid templateVersion.")
        require("\"files\"" in content) {
            "Workspace manifest has no files array."
        }

        val records = recordPattern.findAll(content).map { match ->
            val path = unescapeJson(match.groupValues[1])
            val recordTemplateVersion = unescapeJson(match.groupValues[2])
            val templateSha256 = match.groupValues[3].lowercase(Locale.ROOT)
            val appliedSha256 = match.groupValues[5]
                .takeIf(String::isNotEmpty)
                ?.lowercase(Locale.ROOT)
            ManagedFileRecord(
                path = path,
                templateVersion = recordTemplateVersion,
                templateSha256 = templateSha256,
                appliedSha256 = appliedSha256
            )
        }.toList()
        val pathFieldCount = Regex(""""path"\s*:""").findAll(content).count()
        require(pathFieldCount == records.size) {
            "Workspace manifest contains an invalid managed-file record."
        }
        require(records.map(ManagedFileRecord::path).distinct().size == records.size) {
            "Workspace manifest contains duplicate managed-file paths."
        }
        return WorkspaceManifest(
            schemaVersion = schemaVersion,
            templateVersion = templateVersion,
            files = records.associateBy(ManagedFileRecord::path)
        )
    }
}

private fun escapeJson(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}

private fun unescapeJson(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character != '\\') {
            append(character)
            continue
        }
        require(index < value.length) {
            "Invalid JSON escape in workspace manifest."
        }
        when (val escaped = value[index++]) {
            '\\', '"', '/' -> append(escaped)
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> {
                require(index + 4 <= value.length) {
                    "Invalid Unicode escape in workspace manifest."
                }
                append(value.substring(index, index + 4).toInt(16).toChar())
                index += 4
            }
            else -> error("Invalid JSON escape in workspace manifest: \\$escaped")
        }
    }
}
