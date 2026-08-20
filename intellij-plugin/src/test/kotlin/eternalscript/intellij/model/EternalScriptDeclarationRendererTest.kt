package eternalscript.intellij.model

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import kotlin.io.path.exists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@KaAllowAnalysisOnEdt
internal class EternalScriptDeclarationRendererTest : BasePlatformTestCase() {
    fun testRendersAllSharedDeclarationKindsFromOriginalPsi() {
        val provider = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            """
                import java.time.Instant

                val inferred = 7
                internal var explicit: String = "value"
                fun String.decorate(prefix: String = "["): String = prefix + this
                fun overloaded(value: Int): Int = value
                fun overloaded(value: String): String = value
                abstract fun abstractWithDefault(value: Int = 1): String
                data class SharedType(val value: String)
                object SharedObject
                interface SharedInterface
                class SharedOwner {
                    companion object {
                        fun create(): SharedOwner = SharedOwner()
                    }
                }
                fun interface SharedFactory {
                    fun create(): SharedType
                }
                annotation class SharedMarker(val value: String = "default")
                enum class SharedEnum { ONE }
                typealias SharedNames = List<String>
                private val hidden = Instant.EPOCH
            """.trimIndent()
        ) as KtFile
        val parseErrors = provider.collectDescendantsOfType<PsiErrorElement>()
        assertTrue(parseErrors.isEmpty(), parseErrors.joinToString { error -> error.errorDescription })

        val abi = allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(provider, null) }
        val renderedFiles = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            listOf(abi)
        )
        val rendered = renderedFiles.single()
        assertTrue(rendered.fileName.startsWith("EternalScriptShared_"))

        assertContains(rendered.text, "val inferred: kotlin.Int")
        assertContains(
            rendered.text,
            "var explicit: kotlin.String = kotlin.error(\"EternalScript IDE declaration\")"
        )
        assertContains(
            rendered.text,
            "fun kotlin.String.decorate(prefix: kotlin.String = kotlin.error(\"EternalScript IDE default\")): " +
                "kotlin.String"
        )
        assertContains(rendered.text, "fun overloaded(value: kotlin.Int): kotlin.Int")
        assertContains(rendered.text, "fun overloaded(value: kotlin.String): kotlin.String")
        assertContains(
            rendered.text,
            "fun abstractWithDefault(value: kotlin.Int = kotlin.error(\"EternalScript IDE default\")): " +
                "kotlin.String = kotlin.error(\"EternalScript IDE declaration\")"
        )
        assertFalse(rendered.text.contains("abstract fun abstractWithDefault"))
        assertContains(rendered.text, "data class SharedType")
        assertContains(rendered.text, "object SharedObject")
        assertContains(rendered.text, "interface SharedInterface")
        assertContains(rendered.text, "companion object")
        assertContains(rendered.text, "fun interface SharedFactory")
        assertContains(rendered.text, "fun create(): SharedType\n")
        assertContains(rendered.text, "annotation class SharedMarker(val value: kotlin.String = \"default\")")
        assertContains(rendered.text, "enum class SharedEnum")
        assertContains(rendered.text, "typealias SharedNames")
        assertFalse(rendered.text.contains("hidden"))
        assertTrue(rendered.topLevelClassifierNames.any { name -> name.asString() == "SharedType" })
        assertTrue(rendered.topLevelCallableNames.any { name -> name.asString() == "inferred" })
        assertTrue(rendered.topLevelCallableNames.any { name -> name.asString() == "overloaded" })
        assertContains(
            rendered.text,
            "abstract class EternalScriptSharedReceiver_workspace : eternalscript.api.script.Script()"
        )
        assertFalse(rendered.text.contains("\nimport "))

        val original = requireNotNull(provider.script).declarations.first { declaration ->
            declaration.text.contains("inferred")
        }
        val mapping = rendered.mappings.first { candidate ->
            candidate.source.offset == original.textOffset
        }
        assertTrue(original === mapping.sourcePointer.element)
        assertFalse(myFixture.tempDirFixture.getFile("EternalScriptShared_workspace.kt")?.toNioPath()?.exists() == true)

    }

    fun testBrokenDocumentKeepsLastGoodAbiWithoutWritingGeneratedSource() {
        val provider = myFixture.addFileToProject(
            "scripts/provider.eternal.kts",
            "val stable: String = \"ready\""
        ) as KtFile
        val previous = allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(provider, null) }
        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(provider))

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("val stable: String =")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val currentAbi = allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(provider, previous) }
        assertFalse(currentAbi.retryable)
        val current = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            listOf(currentAbi)
        ).single()

        assertContains(current.text, "val stable: kotlin.String")
        assertFalse(current.text.contains("ready"))
        assertNotNull(current.mappings.single().sourcePointer.element)
        assertFalse(myFixture.tempDirFixture.getFile("EternalScriptShared_workspace.kt")?.toNioPath()?.exists() == true)
    }

    fun testPublishesOneImportFreeAggregateForAllSources() {
        val instantProvider = myFixture.addFileToProject(
            "scripts/instant.eternal.kts",
            """
                import java.time.Instant

                val sharedInstant: Instant = Instant.EPOCH
            """.trimIndent()
        ) as KtFile
        val durationProvider = myFixture.addFileToProject(
            "scripts/duration.eternal.kts",
            """
                import java.time.Duration as Span

                val sharedDuration: Span = Span.ZERO
            """.trimIndent()
        ) as KtFile

        val files = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            listOf(
                allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(instantProvider, null) },
                allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(durationProvider, null) }
            )
        )

        val aggregate = files.single()
        assertTrue(aggregate.fileName.startsWith("EternalScriptShared_"))
        assertFalse(aggregate.text.contains("\nimport "))
        assertContains(aggregate.text, "val sharedInstant: java.time.Instant")
        assertContains(aggregate.text, "val sharedDuration: java.time.Duration")
    }

    fun testAggregateUsesQualifiedTypesWhenLocalAliasesAndStarsShareNames() {
        val utilAlias = myFixture.addFileToProject(
            "scripts/util-alias.eternal.kts",
            """
                import java.util.Date as Stamp

                fun utilAliasStamp(value: Stamp): Int = 1
                fun <Stamp> identity(value: Stamp): Stamp = value
            """.trimIndent()
        ) as KtFile
        val sqlAlias = myFixture.addFileToProject(
            "scripts/sql-alias.eternal.kts",
            """
                import java.sql.Date as Stamp

                fun sqlAliasStamp(value: Stamp): Int = 1
            """.trimIndent()
        ) as KtFile
        myFixture.addFileToProject("types/util/Clash.kt", "package example.util\nclass Clash")
        myFixture.addFileToProject("types/sql/Clash.kt", "package example.sql\nclass Clash")
        myFixture.addFileToProject("types/defaults/Mode.kt", "package example.defaults\nenum class Mode { ON }")
        val utilStar = myFixture.addFileToProject(
            "scripts/util-star.kt",
            """
                import example.util.*

                fun utilStarStamp(value: Clash): Int = 1
            """.trimIndent()
        ) as KtFile
        val sqlStar = myFixture.addFileToProject(
            "scripts/sql-star.kt",
            """
                import example.sql.*

                fun sqlStarStamp(value: Clash): Int = 1
            """.trimIndent()
        ) as KtFile
        val annotationDefault = myFixture.addFileToProject(
            "scripts/annotation-default.kt",
            """
                import example.defaults.Mode

                annotation class UsesMode(val value: Mode = Mode.ON)
            """.trimIndent()
        ) as KtFile

        val abis = listOf(utilAlias, sqlAlias, utilStar, sqlStar, annotationDefault).map { file ->
            allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(file, null) }
        }
        assertTrue(abis.all(EternalScriptFileAbi::stable))
        val rendered = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            abis
        )
        val aggregate = rendered.single { generated -> generated.fileName.startsWith("EternalScriptShared_") }

        assertFalse(aggregate.text.contains("\nimport "))
        assertContains(aggregate.text, "fun utilAliasStamp(value: java.util.Date): kotlin.Int")
        assertContains(aggregate.text, "fun sqlAliasStamp(value: java.sql.Date): kotlin.Int")
        assertContains(aggregate.text, "fun <Stamp> identity(value: Stamp): Stamp")
        assertContains(aggregate.text, "fun utilStarStamp(value: example.util.Clash): kotlin.Int")
        assertContains(aggregate.text, "fun sqlStarStamp(value: example.sql.Clash): kotlin.Int")
        assertContains(
            aggregate.text,
            "annotation class UsesMode(val value: example.defaults.Mode = example.defaults.Mode.ON)"
        )
        assertEquals(1, rendered.size)
    }

    fun testResolvesOnlyKnownSharedAnnotationsWhenK2AnnotationCallsAreUnavailable() {
        val provider = myFixture.addFileToProject(
            "scripts/z-provider.eternal.kts",
            "annotation class SharedMarker(val value: String = \"default\")"
        ) as KtFile
        val providerAbi = allowAnalysisOnEdt {
            EternalScriptDeclarationRenderer.renderFile(provider, null)
        }
        val availableDeclarations = (providerAbi.callables + providerAbi.classifiers)
            .groupBy(EternalScriptRenderedDeclaration::name)
        val consumer = myFixture.addFileToProject(
            "scripts/a-consumer.eternal.kts",
            "@SharedMarker val annotationValue = 1"
        ) as KtFile
        val consumerAbi = allowAnalysisOnEdt {
            EternalScriptDeclarationRenderer.renderFile(consumer, null, availableDeclarations)
        }

        assertTrue(
            consumerAbi.stable,
            "callables=${consumerAbi.callables.map(EternalScriptRenderedDeclaration::name)}"
        )
        val aggregate = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            listOf(providerAbi, consumerAbi)
        ).single()
        assertContains(aggregate.text, "@SharedMarker\nval annotationValue: kotlin.Int")
        assertValidAggregate(aggregate.text)

        val unresolved = myFixture.addFileToProject(
            "types/unresolved-annotation.kt",
            "import missing.pkg.Marker\n@Marker(\"x\") val unresolvedAnnotation = 1"
        ) as KtFile
        val unresolvedAbi = allowAnalysisOnEdt {
            EternalScriptDeclarationRenderer.renderFile(unresolved, null)
        }
        assertFalse(unresolvedAbi.stable)
        assertTrue(unresolvedAbi.callables.none { declaration -> declaration.name == "unresolvedAnnotation" })
    }

    fun testRendersBacktickedAnnotationNamesAsKotlinSource() {
        val provider = myFixture.addFileToProject(
            "types/backticked-annotations.kt",
            """
                package sample.`when`

                annotation class Nested(val `when`: String)
                enum class Choice { `when` }
                annotation class Marker(val `is`: Nested, val choice: Choice)

                @Marker(`is` = Nested(`when` = "ok"), choice = Choice.`when`)
                val tagged = 1
            """.trimIndent()
        ) as KtFile
        val abi = allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(provider, null) }

        assertTrue(
            abi.stable,
            "callables=${abi.callables.map(EternalScriptRenderedDeclaration::name)}, " +
                "classifiers=${abi.classifiers.map(EternalScriptRenderedDeclaration::name)}"
        )
        val tagged = abi.callables.single { declaration -> declaration.name == "tagged" }
        assertContains(
            tagged.text,
            "@sample.`when`.Marker(`is` = sample.`when`.Nested(`when` = \"ok\"), " +
                "choice = sample.`when`.Choice.`when`)"
        )
    }

    fun testPreservesConstructorsAndMemberContractsInClassifierStubs() {
        val provider = myFixture.addFileToProject(
            "types/constructors.kt",
            """
                open class SharedBase protected constructor(protected open val label: String) {
                    protected open fun normalize(value: Int): String = value.toString()
                }

                class SharedDerived : SharedBase {
                    internal constructor(value: String) : super(value)
                    protected override val label: String = "derived"
                    protected final override fun normalize(value: Int): String = value.toString()
                }

                class Chained private constructor(val value: Int) {
                    internal constructor(value: Int, marker: String) : this(value)
                }

                class SecondaryLocked {
                    private constructor(value: Int)
                }

                abstract class AbstractApi {
                    protected abstract val name: String
                }

                @Target(
                    AnnotationTarget.PROPERTY_GETTER,
                    AnnotationTarget.PROPERTY_SETTER,
                    AnnotationTarget.VALUE_PARAMETER
                )
                annotation class AccessorMarker

                interface PropertyContract {
                    val implicit: String
                    var mutable: String
                    @get:AccessorMarker val annotated: String
                }

                abstract class AccessorContract {
                    abstract var abstractValue: String
                        @AccessorMarker protected set

                    lateinit var lateValue: String
                        @AccessorMarker private set
                }

                @Target(AnnotationTarget.FIELD)
                annotation class DelegateMarker

                @delegate:DelegateMarker
                val delegatedValue: String by lazy { "delegated" }

                @delegate:DelegateMarker
                var delegatedMutable: String by kotlin.properties.Delegates.observable("delegated") { _, _, _ -> }

                @JvmInline
                value class SharedId(val value: String)

                interface SharedDelegate {
                    fun message(): String
                }

                class Delegated(private val delegate: SharedDelegate) : SharedDelegate by delegate

                open class OverloadedBase {
                    protected constructor(value: Int)
                    protected constructor(value: String)
                }

                class OverloadedChild(value: String) : OverloadedBase(value)

                fun <T> constrained(value: T): T where T : Any = value

                annotation class Names(vararg val values: String)

                enum class RenderedEnum(val code: Int) {
                    ONE(1) {
                        override fun display(): String = "one"
                    },
                    TWO(2) {
                        init { require(code == 2) }
                        override fun display(): String = "two"
                    };

                    abstract fun display(): String
                }

                @JvmName("consumeStrings")
                fun List<String>.consume(): Int = size

                @JvmName("consumeInts")
                fun List<Int>.consume(): Int = size

                annotation class Nested(val first: Int, val second: Int)
                annotation class Marker(val nested: Nested)

                class AnnotatedHolder(
                    @field:Marker(nested = Nested(first = 1, second = 2))
                    @get:Marker(nested = Nested(first = 3, second = 4))
                    val value: String
                )

                class AccessorHolder {
                    @field:Marker(nested = Nested(first = 5, second = 6))
                    val fieldValue: String = "field"

                    var accessorValue: String = "accessor"
                        @Marker(Nested(first = 7, second = 8)) get
                        @Marker(Nested(first = 9, second = 10))
                        set(@Marker(Nested(first = 11, second = 12)) value) { field = value }
                }
            """.trimIndent()
        ) as KtFile

        val abi = allowAnalysisOnEdt { EternalScriptDeclarationRenderer.renderFile(provider, null) }
        assertTrue(
            abi.stable,
            "callables=${abi.callables.map(EternalScriptRenderedDeclaration::name)}, " +
                "classifiers=${abi.classifiers.map(EternalScriptRenderedDeclaration::name)}"
        )
        val aggregate = EternalScriptDeclarationRenderer.render(
            "workspace",
            "eternalscript.ide.synthetic.workspace",
            "EternalScriptSharedReceiver_workspace",
            listOf(abi)
        ).single()

        assertContains(
            aggregate.text,
            "open class SharedBase protected constructor(protected open val label: kotlin.String)"
        )
        assertContains(aggregate.text, "protected open fun normalize(value: kotlin.Int): kotlin.String")
        assertContains(aggregate.text, "internal constructor(value: kotlin.String) : super(")
        assertContains(aggregate.text, "protected override val label: kotlin.String")
        assertContains(
            aggregate.text,
            "protected final override fun normalize(value: kotlin.Int): kotlin.String"
        )
        assertContains(aggregate.text, "class Chained private constructor(val value: kotlin.Int)")
        assertContains(
            aggregate.text,
            "internal constructor(value: kotlin.Int, marker: kotlin.String) : this("
        )
        assertContains(aggregate.text, "private constructor(value: kotlin.Int) {}")
        assertContains(aggregate.text, "protected abstract val name: kotlin.String\n")
        assertFalse(aggregate.text.contains("protected abstract val name: kotlin.String\n    get()"))
        assertContains(aggregate.text, "interface PropertyContract {")
        assertContains(aggregate.text, "val implicit: kotlin.String\n")
        assertFalse(aggregate.text.contains("val implicit: kotlin.String\n    get"))
        assertContains(aggregate.text, "var mutable: kotlin.String\n")
        assertFalse(aggregate.text.contains("var mutable: kotlin.String\n    get"))
        assertContains(aggregate.text, "@get:AccessorMarker\nval annotated: kotlin.String")
        assertContains(
            aggregate.text,
            "abstract var abstractValue: kotlin.String\n    @AccessorMarker protected set"
        )
        assertContains(
            aggregate.text,
            "lateinit var lateValue: kotlin.String\n    @AccessorMarker private set"
        )
        assertContains(aggregate.text, "@delegate:DelegateMarker\nval delegatedValue: kotlin.String by object")
        assertContains(aggregate.text, "kotlin.properties.ReadOnlyProperty<kotlin.Nothing?, kotlin.String>")
        assertContains(aggregate.text, "override operator fun getValue(")
        assertContains(aggregate.text, "@delegate:DelegateMarker\nvar delegatedMutable: kotlin.String by object")
        assertContains(aggregate.text, "kotlin.properties.ReadWriteProperty<kotlin.Nothing?, kotlin.String>")
        assertContains(aggregate.text, "override operator fun setValue(")
        assertFalse(aggregate.text.contains("} = kotlin.error(\"EternalScript IDE declaration\")"))
        assertContains(aggregate.text, "@kotlin.jvm.JvmInline\nvalue class SharedId(val value: kotlin.String)")
        assertContains(
            aggregate.text,
            "class Delegated(private val delegate: SharedDelegate) : SharedDelegate by " +
                "(kotlin.error(\"EternalScript IDE constructor argument\") as SharedDelegate)"
        )
        assertContains(
            aggregate.text,
            "class OverloadedChild(value: kotlin.String) : OverloadedBase(" +
                "(kotlin.error(\"EternalScript IDE constructor argument\") as kotlin.String))"
        )
        assertContains(aggregate.text, "fun <T> constrained(value: T): T where T : kotlin.Any")
        assertContains(aggregate.text, "annotation class Names(vararg val values: kotlin.String)")
        assertContains(
            aggregate.text,
            "ONE((kotlin.error(\"EternalScript IDE constructor argument\") as kotlin.Int)) {"
        )
        assertContains(
            aggregate.text,
            "TWO((kotlin.error(\"EternalScript IDE constructor argument\") as kotlin.Int)) {"
        )
        assertFalse(aggregate.text.contains("init {"))
        assertContains(aggregate.text, "override fun display(): kotlin.String")
        assertContains(aggregate.text, "@kotlin.jvm.JvmName")
        assertContains(aggregate.text, "consumeStrings")
        assertContains(aggregate.text, "consumeInts")
        assertContains(
            aggregate.text,
            "@field:Marker(nested = Nested(first = 1, second = 2))"
        )
        assertContains(
            aggregate.text,
            "@get:Marker(nested = Nested(first = 3, second = 4))"
        )
        assertContains(
            aggregate.text,
            "@field:Marker(nested = Nested(first = 5, second = 6))"
        )
        assertContains(
            aggregate.text,
            "val fieldValue: kotlin.String = kotlin.error(\"EternalScript IDE declaration\")"
        )
        assertContains(aggregate.text, "@Marker(Nested(first = 7, second = 8)) get() = field")
        assertContains(
            aggregate.text,
            "set(@Marker(Nested(first = 11, second = 12)) value) { field = value }"
        )
        assertFalse(aggregate.text.contains("sample/Nested"))
        assertFalse(aggregate.text.contains(", ,"))
        assertValidAggregate(aggregate.text)
    }

    private fun assertValidAggregate(text: String) {
        val standaloneText = text.lineSequence()
            .filterNot { line -> line.startsWith("abstract class EternalScriptSharedReceiver_") }
            .joinToString("\n")
        val generated = myFixture.addFileToProject(
            "generated/EternalScriptShared_workspace.kt",
            standaloneText
        ) as KtFile
        val parseErrors = generated.collectDescendantsOfType<PsiErrorElement>()
        assertTrue(parseErrors.isEmpty(), parseErrors.joinToString("\n") { error -> error.errorDescription })
        val errors = allowAnalysisOnEdt {
            analyze(generated) {
                generated.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .filter { diagnostic -> diagnostic.severity == KaSeverity.ERROR }
                    .filter { diagnostic ->
                        AGGREGATE_REGRESSION_DIAGNOSTICS.any { marker -> marker in diagnostic.factoryName }
                    }
                    .map { diagnostic -> "${diagnostic.factoryName}: ${diagnostic.defaultMessage}" }
            }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n") + "\n" + standaloneText)
    }

    private companion object {
        val AGGREGATE_REGRESSION_DIAGNOSTICS = setOf(
            "ABSTRACT_MEMBER_NOT_IMPLEMENTED",
            "ABSTRACT_PROPERTY_IN_NON_ABSTRACT_CLASS",
            "ABSTRACT_PROPERTY_WITH_GETTER",
            "ABSTRACT_PROPERTY_WITH_INITIALIZER",
            "ANNOTATION_ARGUMENT_MUST_BE_CONST",
            "ASSIGNMENT_IN_EXPRESSION_CONTEXT",
            "ARGUMENT_TYPE_MISMATCH",
            "CONFLICTING_OVERLOADS",
            "DELEGATE_SPECIAL_FUNCTION",
            "INVALID_TYPE_OF_ANNOTATION_MEMBER",
            "MUST_BE_INITIALIZED",
            "NAMED_ARGUMENTS_NOT_ALLOWED",
            "NOT_AN_ANNOTATION_CLASS",
            "NO_VALUE_FOR_PARAMETER",
            "OVERLOAD_RESOLUTION_AMBIGUITY",
            "PLATFORM_DECLARATION_CLASH",
            "TOO_MANY_ARGUMENTS",
            "WRONG_ANNOTATION_TARGET"
        )
    }
}
