package eternalscript.intellij.model

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import eternalscript.ide.protocol.IdeEnvironment
import eternalscript.ide.protocol.IdeEnvironmentCodec
import eternalscript.ide.protocol.IdeProtocol
import eternalscript.intellij.resolve.Idea262Facade
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.idea.core.script.v1.ScriptDependencyAware
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import org.jetbrains.kotlin.scripting.resolve.VirtualFileScriptSource
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.JarURLConnection
import java.util.UUID
import kotlin.io.path.exists
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.valueOrNull
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EternalScriptK2IntegrationTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    @KaAllowAnalysisOnEdt
    fun testSourceNativeResolutionAndNavigation() {
        val scriptRoot = Path.of(myFixture.tempDirPath).resolve("scripts")
        val provider = myFixture.addFileToProject(
            "scripts/z-provider.eternal.kts",
            """
                import java.time.Instant

                /** Shared documentation. */
                val sharedValue = 7
                val providerEpoch: Instant = Instant.EPOCH
                internal val internalShared = 3
                private val hiddenValue = 99
                fun sharedFunction(): Int = sharedValue
                fun collision(): Int = sharedValue
                fun overloaded(value: Int): Int = value
                fun overloaded(value: String): String = value
                fun String.decorated(): String = "[${'$'}this]"
                class SharedType
                typealias SharedAlias = SharedType
                class SharedOwner {
                    companion object {
                        fun create(): SharedOwner = SharedOwner()
                    }
                }
                open class SharedBase protected constructor(protected open val label: String) {
                    protected open fun normalize(value: Int): String = label + value
                }
                class SharedDerived : SharedBase {
                    constructor(value: String) : super(value)
                    protected override fun normalize(value: Int): String = label + value
                    fun expose(value: Int): String = normalize(value)
                }
                fun interface SharedFactory {
                    fun create(): SharedType
                }
                annotation class SharedMarker(val value: String = "default")
            """.trimIndent()
        ) as KtFile
        val consumer = myFixture.addFileToProject(
            "scripts/a-consumer.eternal.kts",
            """
                import example.plugin.InstalledApi as ImportedPluginApi
                import example.plugin.installedValue
                import example.plugin.installedValue as SharedInstalledValue
                import example.star.*
                import org.bukkit.Bukkit
                import org.bukkit.event.player.PlayerJoinEvent

                val result = sharedFunction()
                val internalResult = internalShared
                val overloadedInt = overloaded(1)
                val overloadedString = overloaded("value")
                val extensionResult = "value".decorated()
                val alias: SharedAlias = SharedType()
                val serverName: String = Bukkit.getName()
                val eventType = PlayerJoinEvent::class
                val sharedImportValue = ImportedPluginApi::class
                val directImportedFunction = installedValue()
                val sharedImportedFunction = SharedInstalledValue()
                val starImportedFunction = starInstalledValue()
                val starImportedProperty = starInstalledProperty
                val companionValue = SharedOwner.create()
                val constructedResult: String = SharedDerived("ready").expose(2)
                val samValue = SharedFactory { SharedType() }
                @SharedMarker val annotationValue = 1
                val disabledFileResult: Int = disabledFileValue
                val disabledFolderResult: Int = ignoredDirectoryValue
                onLoad { }
            """.trimIndent()
        ) as KtFile
        val importIsolationConsumer = myFixture.addFileToProject(
            "scripts/import-isolation.eternal.kts",
            "private val leakedProviderImport = Instant.EPOCH"
        ) as KtFile
        val disabledConsumer = myFixture.addFileToProject(
            "scripts/-disabled.eternal.kts",
            "val disabledResult: Int = sharedFunction()"
        ) as KtFile
        val disabledFileProvider = myFixture.addFileToProject(
            "scripts/-disabled-provider.eternal.kts",
            "val disabledFileValue: Int = 99\nfun disabledFunction(): Int = disabledFileValue"
        ) as KtFile
        val ignoredDirectoryScript = myFixture.addFileToProject(
            "scripts/-ignored/provider.eternal.kts",
            """
                val ignoredDirectoryValue = 101
                val ignoredDirectoryResult: Int = sharedFunction()
            """.trimIndent()
        ) as KtFile
        val cycleA = myFixture.addFileToProject(
            "scripts/cycle-a.eternal.kts",
            "fun cycleA(value: Int): Int = cycleB(value)"
        ) as KtFile
        val cycleB = myFixture.addFileToProject(
            "scripts/cycle-b.eternal.kts",
            "fun cycleB(value: Int): Int = cycleA(value)"
        ) as KtFile
        installEnvironment(scriptRoot)
        val model = EternalScriptProjectService.getInstance(project)
        model.setScriptConfigurationReloadsEnabledForTests(false)
        allowAnalysisOnEdt { model.rebuildSynchronouslyForTests(Path.of(myFixture.tempDirPath)) }
        val scriptDefinitionProvider = project.service<ScriptDefinitionProvider>()
        val currentDefinitions = scriptDefinitionProvider.currentDefinitions.toList()
        val selectedDefinition = scriptDefinitionProvider.findDefinition(KtFileScriptSource(consumer))
        val disabledFileDefinition = scriptDefinitionProvider.findDefinition(KtFileScriptSource(disabledConsumer))
        val ignoredDirectoryDefinition = scriptDefinitionProvider.findDefinition(KtFileScriptSource(ignoredDirectoryScript))
        assertEquals("EternalScript", disabledFileDefinition?.name)
        assertTrue(
            ignoredDirectoryDefinition?.name == "EternalScript",
            "A disabled directory must remain editable with EternalScript support: $ignoredDirectoryDefinition; " +
                "definitions=${currentDefinitions.joinToString { definition -> definition.name }}"
        )
        assertEquals("EternalScript", selectedDefinition?.name)
        assertTrue(
            requireNotNull(selectedDefinition).isScript(VirtualFileScriptSource(consumer.virtualFile)),
            "The definition must match the VirtualFileScriptSource used by IDEA K2"
        )
        val definitionImports = selectedDefinition.compilationConfiguration[
            ScriptCompilationConfiguration.defaultImports
        ].orEmpty()
        assertFalse("org.bukkit.Bukkit" in definitionImports)
        assertFalse("org.bukkit.event.player.PlayerJoinEvent" in definitionImports)
        assertFalse("example.plugin.InstalledApi" in definitionImports)
        assertFalse("example.plugin.DefaultInstalledValue" in definitionImports)
        assertFalse("example.plugin.installedValue as SharedInstalledValue" in definitionImports)
        assertFalse("example.plugin.installedValue" in definitionImports)
        assertFalse("example.star.*" in definitionImports)
        assertFalse("java.time.Instant" in definitionImports)
        assertFalse("example.plugin.InstalledApi as ImportedPluginApi" in definitionImports)
        model.setScriptConfigurationReloadsEnabledForTests(true)
        myFixture.configureFromExistingVirtualFile(consumer.virtualFile)

        allowAnalysisOnEdt { model.rebuildSynchronouslyForTests() }
        assertNotNull(
            model.current().workspaceFor(consumer.virtualFile),
            "model=${System.identityHashCode(model)}; consumer=${consumer.virtualFile.path}; " +
                "workspaces=${model.current().workspaces.map { it.scriptRoot }}"
        )
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val dependencies = project.service<ScriptConfigurationsProvider>() as ScriptDependencyAware
        var observedConfiguration = "not queried"
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "EternalScript configuration was not refined",
                {
                    val classpath = dependencies.getScriptDependenciesClassFiles(consumer.virtualFile)
                    observedConfiguration = "classpath=${classpath.map { file -> file.url }}"
                    classpath.isNotEmpty()
                },
                30
            )
        } catch (failure: AssertionError) {
            throw AssertionError(
                failure.message + "\n" + observedConfiguration + "\n" + model.current(),
                failure
            )
        }
        assertTrue(
            model.scriptConfigurationChangedForTests(consumer.virtualFile),
            observedConfiguration
        )
        val loadedConfiguration = requireNotNull(
            project.service<ScriptConfigurationsProvider>()
                .getScriptCompilationConfiguration(KtFileScriptSource(consumer))
                ?.valueOrNull()
        )
        assertFalse("org.bukkit.Bukkit" in loadedConfiguration.defaultImports)
        assertFalse("org.bukkit.event.player.PlayerJoinEvent" in loadedConfiguration.defaultImports)
        assertFalse("example.plugin.InstalledApi" in loadedConfiguration.defaultImports)
        assertFalse("example.plugin.DefaultInstalledValue" in loadedConfiguration.defaultImports)
        assertTrue(
            loadedConfiguration.defaultImports.any { imported ->
                imported.startsWith("eternalscript.ide.synthetic.w") && imported.endsWith(".*")
            }
        )
        assertFalse("example.plugin.installedValue as SharedInstalledValue" in loadedConfiguration.defaultImports)
        assertFalse("example.plugin.installedValue" in loadedConfiguration.defaultImports)
        assertFalse("example.star.*" in loadedConfiguration.defaultImports)
        assertFalse("java.time.Instant" in loadedConfiguration.defaultImports)
        assertScriptConfigurationLoaded(disabledConsumer)
        assertScriptConfigurationLoaded(ignoredDirectoryScript)
        assertScriptConfigurationLoaded(provider)
        allowAnalysisOnEdt { model.rebuildSynchronouslyForTests() }
        val initialWorkspace = requireNotNull(model.current().workspaceFor(provider.virtualFile))
        assertContains(initialWorkspace.generatedText, "fun sharedFunction")
        val completionOverlay = initialWorkspace.generatedFiles.single { generated ->
            generated.fileName.startsWith("EternalScriptShared_")
        }
        assertFalse(completionOverlay.text.contains("\nimport "))
        val resultProperty = requireNotNull(consumer.script).declarations
            .filterIsInstance<KtProperty>()
            .first { property -> property.name == "result" }
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "K2 did not resolve the shared declaration overlay",
                {
                    runCatching { allowAnalysisOnEdt { Idea262Facade.renderReturnType(resultProperty) } == "kotlin.Int" }
                        .getOrDefault(false)
                },
                30
            )
        } catch (failure: AssertionError) {
            throw AssertionError(failure.message + "\n" + completionOverlay.text, failure)
        }
        assertEquals("kotlin.Int", allowAnalysisOnEdt { Idea262Facade.renderReturnType(resultProperty) })
        val sharedImportProperty = requireNotNull(consumer.script).declarations
            .filterIsInstance<KtProperty>()
            .first { property -> property.name == "sharedImportValue" }
        val sharedImportType = requireNotNull(
            allowAnalysisOnEdt { Idea262Facade.renderReturnType(sharedImportProperty) }
        )
        assertTrue(sharedImportType.endsWith(".InstalledApi>"), sharedImportType)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Inferred cross-script ABI was not propagated",
                {
                    model.current().workspaceFor(provider.virtualFile)?.generatedText
                        ?.contains("val result: kotlin.Int") == true
                },
                30
            )
        } catch (failure: AssertionError) {
            throw AssertionError(
                failure.message + "\n" + model.current() + "\n" +
                    model.current().workspaceFor(provider.virtualFile)?.generatedText,
                failure
            )
        }
        val expectedReferences = listOf(
            "sharedFunction",
            "SharedAlias",
            "SharedType",
            "internalShared",
            "overloaded",
            "decorated",
            "Bukkit",
            "PlayerJoinEvent",
            "ImportedPluginApi",
            "installedValue",
            "SharedInstalledValue",
            "starInstalledValue",
            "starInstalledProperty",
            "SharedOwner",
            "create",
            "SharedDerived",
            "expose",
            "SharedFactory",
            "SharedMarker",
            "onLoad"
        )
        var unresolvedReferences = expectedReferences
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "K2 did not stabilize after the in-memory ABI update",
                {
                    runCatching {
                        allowAnalysisOnEdt {
                            unresolvedReferences = expectedReferences.filter { name ->
                                Idea262Facade.resolveReferenceForTest(reference(consumer, name)) == null
                            }
                            unresolvedReferences.isEmpty()
                        }
                    }.getOrDefault(false)
                },
                30
            )
        } catch (failure: AssertionError) {
            throw AssertionError(
                failure.message + "\nunresolved=$unresolvedReferences\n" +
                    model.current().workspaceFor(provider.virtualFile)?.generatedText,
                failure
            )
        }

        val consumerProperties = requireNotNull(consumer.script).declarations.filterIsInstance<KtProperty>()
        assertEquals(
            "kotlin.Int",
            allowAnalysisOnEdt {
                Idea262Facade.renderReturnType(
                    consumerProperties.first { property -> property.name == "sharedImportedFunction" }
                )
            }
        )
        assertEquals(
            "kotlin.Int",
            allowAnalysisOnEdt {
                Idea262Facade.renderReturnType(
                    consumerProperties.first { property -> property.name == "starImportedFunction" }
                )
            }
        )
        assertEquals(
            "kotlin.Int",
            allowAnalysisOnEdt {
                Idea262Facade.renderReturnType(
                    consumerProperties.first { property -> property.name == "starImportedProperty" }
                )
            }
        )
        assertTrue(
            requireNotNull(
                allowAnalysisOnEdt {
                    Idea262Facade.renderReturnType(
                        consumerProperties.first { property -> property.name == "companionValue" }
                    )
                }
            ).endsWith(".SharedOwner")
        )
        assertEquals(
            "kotlin.String",
            allowAnalysisOnEdt {
                Idea262Facade.renderReturnType(
                    consumerProperties.first { property -> property.name == "constructedResult" }
                )
            }
        )
        assertTrue(
            requireNotNull(
                allowAnalysisOnEdt {
                    Idea262Facade.renderReturnType(
                        consumerProperties.first { property -> property.name == "samValue" }
                    )
                }
            ).endsWith(".SharedFactory")
        )

        assertUnsavedProviderEditIsObserved(provider, model)
        assertCompletes(consumer, "sharedF", "sharedFunction")
        assertCompletes(consumer, "Bukk", "Bukkit")
        assertCompletes(consumer, "onL", "onLoad")
        assertCompletes(consumer, "disabledFileV", "disabledFileValue")
        assertCompletes(consumer, "ignoredDirectoryV", "ignoredDirectoryValue")
        assertCompletes(disabledConsumer, "sharedF", "sharedFunction")
        assertAutoImports(disabledConsumer, "Bukk", "Bukkit", "org.bukkit.Bukkit", '\n')
        assertAutoImports(disabledConsumer, "Bukk", "Bukkit", "org.bukkit.Bukkit", '\t')
        assertCompletes(disabledConsumer, "onL", "onLoad")
        assertCompletes(ignoredDirectoryScript, "sharedF", "sharedFunction")
        assertCompletes(ignoredDirectoryScript, "Bukk", "Bukkit")
        assertCompletes(ignoredDirectoryScript, "onL", "onLoad")
        allowAnalysisOnEdt { model.rebuildSynchronouslyForTests() }

        val workspace = requireNotNull(model.current().workspaceFor(provider.virtualFile))
        assertTrue(workspace.sourceUrls.contains(disabledConsumer.virtualFile.url))
        assertTrue(workspace.sourceUrls.contains(disabledFileProvider.virtualFile.url))
        assertTrue(workspace.sourceUrls.contains(ignoredDirectoryScript.virtualFile.url))
        assertEquals(workspace.id, model.current().workspaceFor(ignoredDirectoryScript.virtualFile)?.id)
        val sharedOverlay = workspace.generatedFiles.single()
        assertTrue(sharedOverlay.fileName.startsWith("EternalScriptShared_"))
        assertFalse(sharedOverlay.text.contains("\nimport "))
        assertContains(workspace.generatedText, "sharedFunction")
        assertContains(workspace.generatedText, "SharedAlias")
        assertContains(workspace.generatedText, "companion object")
        assertContains(workspace.generatedText, "fun interface SharedFactory")
        assertContains(workspace.generatedText, "annotation class SharedMarker(val value: kotlin.String = \"default\")")
        assertContains(workspace.generatedText, "@SharedMarker\nval annotationValue: kotlin.Int")
        assertContains(workspace.generatedText, "Shared documentation")
        assertContains(sharedOverlay.text, "java.time.Instant")
        assertFalse(sharedOverlay.text.contains("ImportedPluginApi"))
        assertFalse(sharedOverlay.text.contains("SharedInstalledValue"))
        assertFalse(sharedOverlay.text.contains("example.star.*"))
        assertContains(workspace.generatedText, "disabledFileValue")
        assertContains(workspace.generatedText, "ignoredDirectoryValue")
        assertFalse(workspace.generatedText.contains("hiddenValue"))
        workspace.generatedFiles.forEach { generated ->
            assertFalse(scriptRoot.resolve(generated.fileName).exists())
        }
        assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(consumer, "ImportedPluginApi"))
            },
            "A file-local classifier import alias must resolve in its own script"
        )
        assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(consumer, "SharedInstalledValue"))
            },
            "A file-local callable import alias must resolve in its own script"
        )
        assertNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(importIsolationConsumer, "Instant"))
            },
            "An explicit import from another script must not leak into this script"
        )
        val sharedFunctionReference = reference(consumer, "sharedFunction")
        val originalFunction = allowAnalysisOnEdt {
            val resolvedFunction = requireNotNull(Idea262Facade.resolveReferenceForTest(sharedFunctionReference)) {
                buildString {
                    appendLine(
                        "definitions=" + ScriptDefinitionsProvider.EP_NAME.getExtensionList(project)
                            .joinToString { provider -> provider.javaClass.name }
                    )
                    appendLine(
                        "resolveExtensions=" + KaResolveExtensionProvider.EP_NAME.getExtensionList(project)
                            .joinToString { provider -> provider.javaClass.name }
                    )
                    appendLine("currentDefinitions=" + currentDefinitions.joinToString { definition -> definition.name })
                    appendLine("selectedDefinition=${selectedDefinition.name}")
                    appendLine(
                        "definitionImports=" + requireNotNull(selectedDefinition)
                            .compilationConfiguration[ScriptCompilationConfiguration.defaultImports].orEmpty()
                    )
                    appendLine(
                        "refinedClasspath=" + dependencies.getScriptDependenciesClassFiles(consumer.virtualFile)
                            .map { file -> file.url }
                    )
                }
            }
            requireNotNull(sourceDeclaration(model, resolvedFunction))
        }
        assertEquals("z-provider.eternal.kts", originalFunction.containingFile.name)
        assertEquals("sharedFunction", originalFunction.name)

        listOf(
            "SharedAlias",
            "SharedType",
            "Bukkit",
            "PlayerJoinEvent",
            "installedValue",
            "SharedOwner",
            "SharedDerived",
            "expose",
            "SharedFactory",
            "SharedMarker",
            "onLoad"
        )
            .forEach { name ->
            assertNotNull(
                allowAnalysisOnEdt { Idea262Facade.resolveReferenceForTest(reference(consumer, name)) },
                "Could not resolve $name"
            )
        }
        assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(disabledConsumer, "sharedFunction"))
            },
            "A disabled script must still consume active declarations"
        )
        assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(ignoredDirectoryScript, "sharedFunction"))
            },
            "A script in a disabled directory must still consume active declarations"
        )
        val disabledFileResolved = assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(consumer, "disabledFileValue"))
            },
            "An active script must resolve declarations from a leading-'-' file"
        )
        assertEquals(
            requireNotNull(disabledFileProvider.script).declarations.filterIsInstance<KtProperty>()
                .single { property -> property.name == "disabledFileValue" },
            sourceDeclaration(model, disabledFileResolved)
        )
        val disabledDirectoryResolved = assertNotNull(
            allowAnalysisOnEdt {
                Idea262Facade.resolveReferenceForTest(reference(consumer, "ignoredDirectoryValue"))
            },
            "An active script must resolve declarations below a leading-'-' directory"
        )
        assertEquals(
            requireNotNull(ignoredDirectoryScript.script).declarations.filterIsInstance<KtProperty>()
                .single { property -> property.name == "ignoredDirectoryValue" },
            sourceDeclaration(model, disabledDirectoryResolved)
        )
        assertNotNull(
            allowAnalysisOnEdt { Idea262Facade.resolveReferenceForTest(reference(cycleA, "cycleB")) },
            "The first half of a typed SCC did not resolve"
        )
        assertNotNull(
            allowAnalysisOnEdt { Idea262Facade.resolveReferenceForTest(reference(cycleB, "cycleA")) },
            "The second half of a typed SCC did not resolve"
        )

    }

    private fun sourceDeclaration(
        model: EternalScriptProjectService,
        element: PsiElement
    ): KtNamedDeclaration? {
        val containingFile = element.containingFile ?: return null
        val generated = model.current().workspaces.asSequence()
            .mapNotNull { workspace -> workspace.generatedFile(containingFile.name) }
            .firstOrNull() ?: return null
        val declarationName = (element as? KtNamedDeclaration
            ?: element.getParentOfType<KtNamedDeclaration>(strict = false))?.name
        val candidates = declarationName?.let { name ->
            generated.mappings.filter { mapping -> mapping.sourcePointer.element?.name == name }
        }.orEmpty()
        return candidates.firstOrNull { mapping -> mapping.range.containsOffset(element.textOffset) }
            ?.sourcePointer?.element
            ?: candidates.singleOrNull()?.sourcePointer?.element
    }

    private fun installEnvironment(scriptRoot: Path) {
        val scriptClassResource = requireNotNull(
            eternalscript.api.script.Script::class.java.getResource("Script.class")
        )
        val classpathRoot = when (scriptClassResource.protocol) {
            "file" -> generateSequence(Path.of(scriptClassResource.toURI()), Path::getParent)
                .drop(4)
                .first()
            "jar" -> Path.of(
                (scriptClassResource.openConnection() as JarURLConnection).jarFileURL.toURI()
            )
            else -> error("Unsupported test class resource: $scriptClassResource")
        }
            .toAbsolutePath()
            .normalize()
        assertTrue(
            classpathRoot.resolve("eternalscript/api/script/Script.class").exists() ||
                (Files.isRegularFile(classpathRoot) && classpathRoot.exists())
        )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classpathRoot)?.refresh(true, true)
        val environment = IdeEnvironment(
            UUID.nameUUIDFromBytes(scriptRoot.toString().toByteArray()).toString(),
            "fixture-environment",
            "scripts",
            listOf(classpathRoot.toUri())
        )
        val text = String(IdeEnvironmentCodec.encode(environment), StandardCharsets.UTF_8)
        val manifest = myFixture.tempDirFixture.createFile(IdeProtocol.ENVIRONMENT_FILE, text)
        VirtualFileManager.getInstance().syncRefresh()
        assertTrue(manifest.exists())
    }

    private fun reference(file: KtFile, name: String): KtNameReferenceExpression =
        file.collectDescendantsOfType<KtNameReferenceExpression>().first { expression ->
            expression.getReferencedName() == name
        }

    private fun assertCompletes(file: KtFile, prefix: String, expected: String) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        val original = document.text
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\n$prefix")
            myFixture.editor.caretModel.moveToOffset(document.textLength)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        var variants = emptyList<String>()
        var completedText = document.text.substring(original.length)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "$prefix did not complete to $expected",
                {
                    variants = myFixture.completeBasic()?.map { element -> element.lookupString }.orEmpty()
                    completedText = document.text.substring(original.length)
                    expected in variants || expected in completedText
                },
                10
            )
        } catch (failure: AssertionError) {
            throw AssertionError(
                "$prefix did not complete to $expected; variants=$variants; inserted=$completedText",
                failure
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(original)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun assertAutoImports(
        file: KtFile,
        prefix: String,
        expected: String,
        importPath: String,
        completionChar: Char
    ) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        val original = document.text
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "\n$prefix")
                myFixture.editor.caretModel.moveToOffset(document.textLength)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val variants = myFixture.completeBasic().orEmpty()
            if (myFixture.lookup != null) {
                val item = variants.firstOrNull { element -> element.lookupString == expected }
                requireNotNull(item) { "$expected was not offered; variants=${variants.map { it.lookupString }}" }
                myFixture.lookup.currentItem = item
                myFixture.finishLookup(completionChar)
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertContains(document.text, "import $importPath")
        } finally {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(original)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    private fun assertScriptConfigurationLoaded(file: KtFile) {
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val dependencies = project.service<ScriptConfigurationsProvider>() as ScriptDependencyAware
        PlatformTestUtil.waitWithEventsDispatching(
            "EternalScript configuration was not loaded for ${file.virtualFile.path}",
            { dependencies.getScriptDependenciesClassFiles(file.virtualFile).isNotEmpty() },
            30
        )
    }

    @KaAllowAnalysisOnEdt
    private fun assertUnsavedProviderEditIsObserved(
        provider: KtFile,
        model: EternalScriptProjectService
    ) {
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(provider))
        val original = document.text
        val diskBefore = Files.readString(provider.virtualFile.toNioPath())
        val start = original.indexOf("sharedFunction")
        assertTrue(start >= 0)
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(start, start + "sharedFunction".length, "liveFunction")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            allowAnalysisOnEdt { model.rebuildSynchronouslyForTests() }
            val workspace = requireNotNull(model.current().workspaceFor(provider.virtualFile))
            val providerAbi = workspace.fileAbis[provider.virtualFile.url]
            assertTrue(
                "liveFunction" in workspace.generatedText,
                "document=${document.text.contains("liveFunction")}, " +
                    "psi=${provider.text.contains("liveFunction")}, " +
                    "abi=${providerAbi?.callables?.map(EternalScriptRenderedDeclaration::name)}"
            )
            assertEquals(diskBefore, Files.readString(provider.virtualFile.toNioPath()))
        } finally {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(original)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            FileDocumentManager.getInstance().saveDocument(document)
            allowAnalysisOnEdt { model.rebuildSynchronouslyForTests() }
        }
    }
}
