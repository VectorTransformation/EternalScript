package eternalScript.core.workspace

import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class WorkspaceTemplate(
    val target: String,
    val content: ByteArray,
    val executable: Boolean = false
)

internal data class WorkspaceTemplateCatalog(
    val templateVersion: String,
    val managed: List<WorkspaceTemplate>,
    val localOverride: WorkspaceTemplate
) {
    init {
        require(managed.isNotEmpty()) {
            "At least one managed workspace template is required."
        }
        val targets = (managed + localOverride).map(WorkspaceTemplate::target)
        require(targets.size == targets.distinct().size) {
            "Workspace template targets must be unique."
        }
        targets.forEach(::requireSafeTemplateTarget)
    }
}

internal object DefaultWorkspaceTemplates {
    const val SCHEMA_VERSION = 1
    const val TEMPLATE_VERSION = "4"
    const val GRADLE_VERSION = "9.6.1"
    const val KOTLIN_VERSION = "2.4.10"
    const val JAVA_VERSION = 25
    const val WRAPPER_JAR_SHA256 =
        "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
    const val GRADLE_DISTRIBUTION_SHA256 =
        "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"

    private const val RESOURCE_ROOT = "/workspace-template/v1"

    private data class ResourceTemplate(
        val target: String,
        val resource: String = target,
        val executable: Boolean = false,
        val base64Encoded: Boolean = false
    )

    private val managed = listOf(
        ResourceTemplate("build.gradle.kts"),
        ResourceTemplate("settings.gradle.kts"),
        ResourceTemplate("gradlew", executable = true),
        ResourceTemplate("gradlew.bat"),
        ResourceTemplate(
            target = "gradle/wrapper/gradle-wrapper.jar",
            resource = "gradle/wrapper/gradle-wrapper.jar.base64",
            base64Encoded = true
        ),
        ResourceTemplate("gradle/wrapper/gradle-wrapper.properties"),
        ResourceTemplate("WORKSPACE.md")
    )

    private val localOverride = ResourceTemplate("workspace.local.gradle.kts")

    fun load(
        resourceLoader: (String) -> ByteArray? = ::loadClasspathResource
    ): WorkspaceTemplateCatalog {
        fun ResourceTemplate.load(): WorkspaceTemplate {
            val resourcePath = "$RESOURCE_ROOT/$resource"
            val packaged = requireNotNull(resourceLoader(resourcePath)) {
                "Missing packaged workspace template: $resourcePath"
            }
            val content = if (base64Encoded) {
                Base64.getMimeDecoder().decode(packaged.toString(StandardCharsets.US_ASCII))
            } else {
                packaged
            }
            return WorkspaceTemplate(
                target = target,
                content = content,
                executable = executable
            )
        }

        return WorkspaceTemplateCatalog(
            templateVersion = TEMPLATE_VERSION,
            managed = managed.map(ResourceTemplate::load),
            localOverride = localOverride.load()
        )
    }

    private fun loadClasspathResource(path: String): ByteArray? =
        DefaultWorkspaceTemplates::class.java.getResourceAsStream(path)?.use { it.readBytes() }
}

private val protectedWorkspaceRoots = setOf(
    "scripts",
    "config.yml",
    "lang",
    "libs",
    "cache"
)

private fun requireSafeTemplateTarget(target: String) {
    require(target.isNotBlank()) {
        "Workspace template target must not be blank."
    }
    require('\\' !in target) {
        "Workspace template target must use forward slashes: $target"
    }
    val parts = target.split('/')
    require(parts.none { it.isBlank() || it == "." || it == ".." }) {
        "Unsafe workspace template target: $target"
    }
    require(parts.first().lowercase() !in protectedWorkspaceRoots) {
        "Workspace templates cannot manage protected server data: $target"
    }
}
