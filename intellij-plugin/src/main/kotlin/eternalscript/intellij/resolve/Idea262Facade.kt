package eternalscript.intellij.resolve

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiInvalidElementAccessException
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.evaluateAsAnnotationValue
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.base.annotations.KaRendererAnnotationsFilter
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaTypeParameterTypeRenderer
import org.jetbrains.kotlin.analysis.api.resolution.resolveCall
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.fullyExpandedType
import org.jetbrains.kotlin.analysis.utils.printer.PrettyPrinter
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.core.script.k2.ReloadScriptConfigurationService
import org.jetbrains.kotlin.idea.core.script.k2.configurations.KotlinScriptService
import org.jetbrains.kotlin.idea.core.script.k2.definitions.ScriptDefinitionsModificationTracker
import org.jetbrains.kotlin.idea.core.script.v1.ScriptDependencyAware
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.name.render as renderKotlinSource
import java.util.concurrent.CancellationException

internal data class Idea262CanonicalCallableSignature(
    val typeParameterCount: Int,
    val receiverType: String?,
    val parameterTypes: List<String>
)

/** All API calls that are intentionally pinned to the IntelliJ/Kotlin 262 ABI. */
internal object Idea262Facade {
    private val log = Logger.getInstance(Idea262Facade::class.java)

    fun isProjectTrusted(project: Project): Boolean = runCatching {
        val trustedProjects = Class.forName("com.intellij.ide.impl.TrustedProjects")
        trustedProjects.getMethod("isTrusted", Project::class.java).invoke(null, project) as Boolean
    }.getOrDefault(false)

    fun subscribeToScriptConfigurationChanges(
        project: Project,
        parentDisposable: Disposable,
        listener: (VirtualFile) -> Unit
    ) {
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            ReloadScriptConfigurationService.TOPIC,
            object : ReloadScriptConfigurationService.Listener {
                override fun onNotificationChanged(virtualFile: VirtualFile) {
                    if (!project.isDisposed) listener(virtualFile)
                }
            }
        )
    }

    /**
     * Recomputes one script configuration without calling IDEA's user-facing reload action.
     *
     * `ReloadScriptConfigurationService.reloadScriptData` always publishes one success or
     * failure balloon per file in IDEA 262. EternalScript refreshes several open files when an
     * environment changes, so using that action API would flood the notification center. The
     * underlying Kotlin service performs the same cache invalidation and load without UI, after
     * which we publish only the completion topic consumed by our coordinator.
     */
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    fun reloadScriptConfigurationSilently(file: KtFile): Boolean {
        val project = file.project
        val virtualFile = file.virtualFile ?: return false
        val service = KotlinScriptService.getInstance(project)
        service.coroutineScope.launch {
            try {
                service.reload(virtualFile)
            } finally {
                if (!project.isDisposed) {
                    ApplicationManager.getApplication().messageBus
                        .syncPublisher(ReloadScriptConfigurationService.TOPIC)
                        .onNotificationChanged(virtualFile)
                }
            }
        }
        return true
    }

    fun invalidateScriptDefinitions(project: Project) {
        ScriptDefinitionsModificationTracker.getInstance(project).incModificationCount()
    }

    @OptIn(KaPlatformInterface::class)
    fun invalidateSyntheticScriptModel(project: Project) {
        val publish = {
            project.analysisMessageBus
                .syncPublisher(KotlinModificationEvent.TOPIC)
                .onModification(KotlinGlobalSourceOutOfBlockModificationEvent)
        }
        val application = ApplicationManager.getApplication()
        if (application.isWriteAccessAllowed) publish() else application.runWriteAction(publish)
    }

    fun renderReturnType(declaration: KtCallableDeclaration): String? = runAnalysisSafely {
        ProgressManager.checkCanceled()
        analyze(declaration) {
            val type = (declaration.symbol as? KaCallableSymbol)?.returnType ?: return@analyze null
            if (type.containsErrorType()) return@analyze null
            type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        }
    }

    fun renderType(reference: KtTypeReference): String? = runAnalysisSafely {
        ProgressManager.checkCanceled()
        analyze(reference) {
            val type = reference.type
            if (type.containsErrorType()) return@analyze null
            type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        }
    }

    fun renderExpressionType(expression: KtExpression): String? = runAnalysisSafely {
        ProgressManager.checkCanceled()
        analyze(expression) {
            val type = expression.expressionType ?: return@analyze null
            if (type.containsErrorType()) return@analyze null
            type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        }
    }

    /**
     * Builds the overload-relevant part of a callable signature from K2 symbols.
     *
     * Type aliases are fully expanded, function-type parameter labels are omitted, and the
     * callable's own type parameters are represented by declaration-order slots. This keeps
     * conflict detection independent from import spelling and generic parameter names.
     */
    fun canonicalCallableSignature(declaration: KtCallableDeclaration): Idea262CanonicalCallableSignature? =
        runAnalysisSafely {
            ProgressManager.checkCanceled()
            analyze(declaration) {
                val symbol = declaration.symbol as? KaCallableSymbol ?: return@analyze null
                val typeParameterNames = declaration.typeParameters.mapNotNull { parameter -> parameter.name }
                val renderer = KaTypeRendererForSource.WITH_QUALIFIED_NAMES_WITHOUT_PARAMETER_NAMES.with {
                    expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_EXPANDED_TYPE
                    annotationsRenderer = annotationsRenderer.with {
                        annotationFilter = KaRendererAnnotationsFilter.NONE
                    }
                    typeParameterTypeRenderer = object : KaTypeParameterTypeRenderer {
                        override fun renderType(
                            analysisSession: KaSession,
                            type: KaTypeParameterType,
                            typeRenderer: KaTypeRenderer,
                            printer: PrettyPrinter
                        ) {
                            val index = typeParameterNames.indexOf(type.name.asString())
                            printer.append(if (index >= 0) "#$index" else type.name.asString())
                        }
                    }
                }

                fun canonical(type: KaType): String? {
                    val expanded = type.fullyExpandedType
                    if (expanded.containsErrorType()) return null
                    return localizeSyntheticWorkspaceTypes(
                        expanded.render(renderer, Variance.INVARIANT)
                    )
                }

                val receiverType = symbol.receiverParameter?.returnType?.let(::canonical)
                if (symbol.receiverParameter != null && receiverType == null) return@analyze null
                val parameterTypes = (symbol as? KaFunctionSymbol)?.valueParameters.orEmpty().map { parameter ->
                    canonical(parameter.returnType) ?: return@analyze null
                }
                Idea262CanonicalCallableSignature(declaration.typeParameters.size, receiverType, parameterTypes)
            }
        }

    fun renderAnnotationDefault(expression: org.jetbrains.kotlin.psi.KtExpression): String? = runAnalysisSafely {
        ProgressManager.checkCanceled()
        analyze(expression) {
            val value = expression.evaluateAsAnnotationValue() ?: return@analyze null
            renderAnnotationValue(value) { type ->
                if (type.containsErrorType()) null else {
                    localizeSyntheticWorkspaceTypes(
                        type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
                    )
                }
            }
        }
    }

    /** Resolves one PSI-owned annotation without losing its original use-site target. */
    fun renderAnnotationEntry(entry: KtAnnotationEntry): String? = runAnalysisSafely {
        ProgressManager.checkCanceled()
        analyze(entry) {
            val classId = entry.resolveCall()?.signature?.symbol?.containingClassId
                ?: return@analyze null
            val arguments = ArrayList<String>(entry.valueArguments.size)
            entry.valueArguments.forEach { argument ->
                val valueArgument = argument as? KtValueArgument ?: return@analyze null
                val expression = valueArgument.getArgumentExpression() ?: return@analyze null
                val value = expression.evaluateAsAnnotationValue() ?: return@analyze null
                val rendered = renderAnnotationValue(value) { type ->
                    if (type.containsErrorType()) null else {
                        localizeSyntheticWorkspaceTypes(
                            type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
                        )
                    }
                } ?: return@analyze null
                arguments += buildString {
                    valueArgument.getArgumentName()?.asName?.renderKotlinSource()?.let { name ->
                        append(name).append(" = ")
                    }
                    if (valueArgument.isSpread) append('*')
                    append(rendered)
                }
            }
            buildString {
                append('@')
                entry.useSiteTarget?.text?.removeSuffix(":")?.let { target ->
                    append(target).append(':')
                }
                append(classId.asSingleFqName().renderSyntheticAggregateReference())
                if (entry.valueArgumentList != null) {
                    append('(').append(arguments.joinToString(", ")).append(')')
                }
            }
        }
    }

    fun scriptDependenciesReady(file: KtFile): Boolean = runAnalysisSafely {
        ProgressManager.checkCanceled()
        val virtualFile = file.virtualFile ?: return@runAnalysisSafely false
        val dependencies = file.project.service<ScriptConfigurationsProvider>() as? ScriptDependencyAware
            ?: return@runAnalysisSafely false
        dependencies.getScriptDependenciesClassFiles(virtualFile).isNotEmpty()
    } ?: false

    @TestOnly
    fun resolveReferenceForTest(expression: KtNameReferenceExpression): PsiElement? = analyze(expression) {
        expression.mainReference.resolveToSymbol()?.psi
    }

    /** Keeps peer references inside a resolve-extension file in its file scope. */
    fun localizeSyntheticWorkspaceTypes(rendered: String): String =
        rendered.replace(SYNTHETIC_WORKSPACE_QUALIFIER, "")

    private inline fun <T> runAnalysisSafely(action: () -> T): T? = try {
        action()
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: IndexNotReadyException) {
        log.debug("K2 analysis is unavailable while indexes are updating", unavailable)
        null
    } catch (stalePsi: PsiInvalidElementAccessException) {
        log.debug("K2 analysis skipped a stale PSI element", stalePsi)
        null
    }

    private fun KaType.containsErrorType(): Boolean = when (this) {
        is KaErrorType -> true
        is KaClassType -> typeArguments.any { projection ->
            projection is KaTypeArgumentWithVariance && projection.type.containsErrorType()
        }
        is KaFlexibleType -> lowerBound.containsErrorType() || upperBound.containsErrorType()
        is KaDefinitelyNotNullType -> original.containsErrorType()
        is KaIntersectionType -> conjuncts.any { type -> type.containsErrorType() }
        is KaCapturedType -> (projection as? KaTypeArgumentWithVariance)?.type?.containsErrorType() == true
        else -> false
    }

    private fun renderAnnotationValue(
        value: KaAnnotationValue,
        renderType: (KaType) -> String?
    ): String? = when (value) {
        is KaAnnotationValue.ConstantValue -> value.value.render()
        is KaAnnotationValue.EnumEntryValue ->
            value.callableId?.asSingleFqName()?.renderSyntheticAggregateReference()
        is KaAnnotationValue.ClassLiteralValue -> renderType(value.type)?.plus("::class")
        is KaAnnotationValue.ArrayValue -> {
            val rendered = ArrayList<String>(value.values.size)
            value.values.forEach { nested ->
                rendered += renderAnnotationValue(nested, renderType) ?: return null
            }
            rendered.joinToString(", ", "[", "]")
        }
        is KaAnnotationValue.NestedAnnotationValue -> {
            val className = value.annotation.classId?.asSingleFqName()
                ?.renderSyntheticAggregateReference() ?: return null
            val rendered = ArrayList<String>(value.annotation.arguments.size)
            value.annotation.arguments.forEach { argument ->
                val argumentValue = renderAnnotationValue(argument.expression, renderType) ?: return null
                rendered += "${argument.name.renderKotlinSource()} = $argumentValue"
            }
            if (rendered.isEmpty()) className else "$className(${rendered.joinToString(", ")})"
        }
        is KaAnnotationValue.UnsupportedValue -> null
    }

    private fun org.jetbrains.kotlin.name.FqName.renderSyntheticAggregateReference(): String =
        if (asString().startsWith(SYNTHETIC_WORKSPACE_PACKAGE_PREFIX)) {
            shortName().renderKotlinSource()
        } else {
            renderKotlinSource()
        }

    private const val SYNTHETIC_WORKSPACE_PACKAGE_PREFIX = "eternalscript.ide.synthetic.w"
    private val SYNTHETIC_WORKSPACE_QUALIFIER =
        Regex("\\beternalscript\\.ide\\.synthetic\\.w[A-Za-z0-9_]+\\.")

}
