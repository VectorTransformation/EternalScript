/*
 * Portions adapted from the Kotlin compiler.
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress(
    "INVISIBLE_MEMBER",
    "INVISIBLE_REFERENCE",
    "DEPRECATION",
    "DEPRECATION_ERROR"
)

package eternalscript.scripting.repl.k2

import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.util.Sha256
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.cli.common.LegacyK2CliPipeline
import org.jetbrains.kotlin.cli.common.fir.reportToMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.PsiBasedProjectFileSearchScope
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.ModuleCompilerEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.convertAnalyzedFirToIr
import org.jetbrains.kotlin.cli.jvm.compiler.legacy.pipeline.generateCodeFromIr
import org.jetbrains.kotlin.cli.jvm.compiler.toVfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirReplSnippet
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.runCheckers
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompilationState
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.KJvmCompiledModuleInMemoryImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ReplModuleDataProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.createIsolatedCompilationContext
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.extractResultFields
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.getScriptKtFile
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.makeCompiledScript
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplHistoryProviderImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.definitions.K1SpecificScriptingServiceAccessor
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptPriorities
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import org.jetbrains.kotlin.scripting.resolve.getKtFile
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.repl
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

internal data class BatchCompiledScript(
    val source: SharedReplSource,
    val className: String,
    val stateKey: String,
    val resultFieldName: String?,
    val classifiers: List<ScriptClassifierDescriptor> = emptyList(),
    val compiledScript: KJvmCompiledScript? = null
)

internal data class ScriptClassifierDescriptor(
    val name: String,
    val importPath: String,
    val kind: String
)

internal data class BatchCompiledGeneration(
    val scripts: List<BatchCompiledScript>,
    val outputFiles: Map<String, ByteArray>,
    val graph: ScriptDependencyGraph
)

internal sealed interface BatchCompilationResult {
    data class Success(val generation: BatchCompiledGeneration) : BatchCompilationResult
    data class Failure(val diagnostic: SharedReplDiagnostic) : BatchCompilationResult
}

internal sealed interface BatchAnalysisResult {
    data class Success(val graph: ScriptDependencyGraph) : BatchAnalysisResult
    data class Failure(val diagnostic: SharedReplDiagnostic) : BatchAnalysisResult
}

internal class BatchK2Compiler(
    configuration: ScriptCompilationConfiguration,
    private val hostConfiguration: ScriptingHostConfiguration = batchScriptingHostConfiguration()
) : AutoCloseable {
    private val configuration = configuration
    private val disposable = org.jetbrains.kotlin.com.intellij.openapi.util.Disposer.newDisposable("eternalscript-batch-k2")
    private val collector = ScriptDiagnosticsMessageCollector(null)
    private val state: K2ReplCompilationState

    init {
        check(KotlinCompilerVersion.VERSION == K2_REPL_COMPILER_ABI.substringBefore("-eternalscript")) {
            "Unsupported Kotlin compiler ${KotlinCompilerVersion.VERSION}; expected ${K2_REPL_COMPILER_ABI.substringBefore("-eternalscript")}"
        }
        state = createState(collector, configuration, hostConfiguration, disposable)
    }

    @OptIn(
        LegacyK2CliPipeline::class,
        SessionConfiguration::class,
        KtNonPublicApi::class,
        DirectDeclarationsAccess::class,
        K1SpecificScriptingServiceAccessor::class
    )
    fun analyze(sources: List<SharedReplSource>): BatchAnalysisResult {
        if (sources.isEmpty()) {
            val graph = (ScriptDependencyGraph.create(emptyList(), emptyMap(), emptyMap()) as ScriptGraphResult.Success).graph
            return BatchAnalysisResult.Success(graph)
        }
        collector.clear()
        val definition = ScriptDefinition.FromConfigurations(
            configuration[ScriptCompilationConfiguration.hostConfiguration] ?: defaultJvmScriptingHostConfiguration,
            configuration,
            null
        )
        val project = state.projectEnvironment.project
        val compilerConfiguration = state.compilerContext.environment.configuration.copy()
        val reporter = DiagnosticsCollectorImpl()
        val sourceCodes = sources.map(::BatchSourceCode)
        val ktFiles = sourceCodes.mapIndexed { index, source ->
            val file = getScriptKtFile(source, configuration, project, collector).valueOrNull()
                ?: return analysisFailure(source.source.name, sourceCodes)
            file.script?.apply {
                markAsReplSnippet()
                putUserData(ScriptPriorities.PRIORITY_KEY, index)
            }
            val ktSource = KtFileScriptSource(file)
            state.scriptConfigurationsProvider?.getScriptCompilationConfiguration(ktSource, configuration)
            ktSource.getKtFile(definition, project)
        }
        val extensionRegistrars = compilerConfiguration.getCompilerExtensions(FirExtensionRegistrar)
        val moduleData = state.moduleDataProvider.addNewSnippetModuleData(Name.special("<eternalscript-analysis>"))
        val session = FirJvmSessionFactory.createSourceSession(
            moduleData,
            AbstractProjectFileSearchScope.EMPTY,
            createIncrementalCompilationSymbolProviders = { null },
            extensionRegistrars,
            compilerConfiguration,
            context = state.sessionFactoryContext,
            needRegisterJavaElementFinder = true,
            isForLeafHmppModule = false,
            init = {}
        )
        val rawFir = session.buildFirFromKtFiles(ktFiles)
        val rawSnippets = rawFir.flatMap { file -> file.declarations.filterIsInstance<FirReplSnippet>() }
        val history = requireNotNull(
            hostConfiguration[ScriptingHostConfiguration.repl.firReplHistoryProvider]
        ) as FirReplHistoryProviderImpl
        rawSnippets.forEach { snippet -> history.putSnippet(snippet.symbol) }
        val pathBySnippet = rawSnippets.zip(sources).associate { (snippet, source) -> snippet.symbol to source.name }
        val scopeSession = session.runBatchResolution(rawFir)
        val resolvedFir = rawFir
        session.runCheckers(scopeSession, resolvedFir, reporter, MppCheckerKind.Common)
        session.runCheckers(scopeSession, resolvedFir, reporter, MppCheckerKind.Platform)
        reporter.reportToMessageCollector(collector, false)
        if (reporter.hasErrors) return analysisFailure(sources.first().name, sourceCodes)
        FirScriptGraphExtractor.declarationConflict(rawSnippets, pathBySnippet)?.let { conflict ->
            return BatchAnalysisResult.Failure(SharedReplDiagnostic(conflict.source, conflict.message))
        }

        val graph = when (
            val result = FirScriptGraphExtractor.extract(
                session,
                resolvedFir.flatMap { file -> file.declarations.filterIsInstance<FirReplSnippet>() },
                pathBySnippet
            )
        ) {
            is ScriptGraphResult.Success -> result.graph
            is ScriptGraphResult.InitializationCycle -> return BatchAnalysisResult.Failure(
                SharedReplDiagnostic(
                    result.paths.firstOrNull() ?: "<initialization>",
                    "Top-level initialization cycle: ${result.paths.joinToString(" -> ")}"
                )
            )
        }
        return BatchAnalysisResult.Success(graph)
    }

    @OptIn(
        LegacyK2CliPipeline::class,
        SessionConfiguration::class,
        KtNonPublicApi::class,
        DirectDeclarationsAccess::class
    )
    fun compile(sources: List<SharedReplSource>): BatchCompilationResult {
        if (sources.isEmpty()) {
            val graph = (ScriptDependencyGraph.create(emptyList(), emptyMap(), emptyMap()) as ScriptGraphResult.Success).graph
            return BatchCompilationResult.Success(BatchCompiledGeneration(emptyList(), emptyMap(), graph))
        }
        collector.clear()
        val definition = ScriptDefinition.FromConfigurations(
            configuration[ScriptCompilationConfiguration.hostConfiguration] ?: defaultJvmScriptingHostConfiguration,
            configuration,
            null
        )
        val project = state.projectEnvironment.project
        val compilerConfiguration = state.compilerContext.environment.configuration.copy()
        val reporter = DiagnosticsCollectorImpl()
        val compilerEnvironment = ModuleCompilerEnvironment(state.projectEnvironment, reporter)
        val targetId = TargetId("eternalscript-batch", "java-production")

        val sourceCodes = sources.map(::BatchSourceCode)
        val ktFiles = sourceCodes.mapIndexed { index, source ->
            val file = getScriptKtFile(source, configuration, project, collector).valueOrNull()
                ?: return failure(source.source.name, sourceCodes)
            file.script?.apply {
                markAsReplSnippet()
                putUserData(ScriptPriorities.PRIORITY_KEY, index)
            }
            val ktSource = KtFileScriptSource(file)
            state.scriptConfigurationsProvider?.getScriptCompilationConfiguration(ktSource, configuration)
            ktSource.getKtFile(definition, project)
        }

        val extensionRegistrars = compilerConfiguration.getCompilerExtensions(FirExtensionRegistrar)
        val moduleData = state.moduleDataProvider.addNewSnippetModuleData(Name.special("<eternalscript-batch>"))
        val session = FirJvmSessionFactory.createSourceSession(
            moduleData,
            AbstractProjectFileSearchScope.EMPTY,
            createIncrementalCompilationSymbolProviders = { null },
            extensionRegistrars,
            compilerConfiguration,
            context = state.sessionFactoryContext,
            needRegisterJavaElementFinder = true,
            isForLeafHmppModule = false,
            init = {}
        )
        val rawFir = session.buildFirFromKtFiles(ktFiles)
        val history = requireNotNull(
            hostConfiguration[ScriptingHostConfiguration.repl.firReplHistoryProvider]
        ) as FirReplHistoryProviderImpl
        val rawSnippets = rawFir.flatMap { file -> file.declarations.filterIsInstance<FirReplSnippet>() }
        rawSnippets.forEach { snippet -> history.putSnippet(snippet.symbol) }
        val pathBySnippet = rawSnippets.zip(sources).associate { (snippet, source) -> snippet.symbol to source.name }
        val scopeSession = session.runBatchResolution(rawFir)
        val resolvedFir = rawFir
        session.runCheckers(scopeSession, resolvedFir, reporter, MppCheckerKind.Common)
        session.runCheckers(scopeSession, resolvedFir, reporter, MppCheckerKind.Platform)
        if (reporter.hasErrors) {
            reporter.reportToMessageCollector(collector, false)
            return failure(sources.first().name, sourceCodes)
        }
        val resolvedSnippets = resolvedFir.flatMap { file -> file.declarations.filterIsInstance<FirReplSnippet>() }
        FirScriptGraphExtractor.declarationConflict(resolvedSnippets, pathBySnippet)?.let { conflict ->
            return BatchCompilationResult.Failure(SharedReplDiagnostic(conflict.source, conflict.message))
        }
        val graph = when (
            val result = FirScriptGraphExtractor.extract(
                session,
                resolvedSnippets,
                pathBySnippet
            )
        ) {
            is ScriptGraphResult.Success -> result.graph
            is ScriptGraphResult.InitializationCycle -> return BatchCompilationResult.Failure(
                SharedReplDiagnostic(
                    result.paths.firstOrNull() ?: "<initialization>",
                    "Top-level initialization cycle: ${result.paths.joinToString(" -> ")}"
                )
            )
        }
        val classifiersByPath = FirScriptGraphExtractor.classifiers(resolvedSnippets, pathBySnippet)

        val frontend = AllModulesFrontendOutput(
            listOf(SingleModuleFrontendOutput(session, scopeSession, resolvedFir))
        )
        val irInput = try {
            convertAnalyzedFirToIr(compilerConfiguration, targetId, frontend, compilerEnvironment)
        } catch (error: Throwable) {
            return BatchCompilationResult.Failure(
                SharedReplDiagnostic("<batch-ir>", error.message ?: error.javaClass.name, cause = error)
            )
        }
        val generationState = try {
            generateCodeFromIr(irInput, compilerEnvironment)
        } catch (error: Throwable) {
            return BatchCompilationResult.Failure(
                SharedReplDiagnostic("<batch-codegen>", error.message ?: error.javaClass.name, cause = error)
            )
        }
        reporter.reportToMessageCollector(collector, false)
        if (reporter.hasErrors) return failure(sources.first().name, sourceCodes)

        val classNames = ktFiles.map { file ->
            file.declarations.firstIsInstance<org.jetbrains.kotlin.psi.KtScript>().fqName
        }
        val classNameByLocation = sourceCodes.zip(classNames).associate { (source, fqName) ->
            source.locationId to fqName
        }
        val resultFields = extractResultFields(irInput.irModuleFragment)
        val compiled = sourceCodes.mapIndexed { index, source ->
            val result = makeCompiledScript(
                generationState,
                source,
                { candidate -> classNameByLocation[candidate.locationId] },
                emptyList(),
                { configuration },
                resultFields
            )
            val script = result.valueOrNull()
                ?: return failure(source.source.name, sourceCodes)
            BatchCompiledScript(
                source.source,
                classNames[index].asString(),
                classNames[index].shortName().asString(),
                script.resultField?.first,
                classifiersByPath[source.source.name].orEmpty(),
                script
            )
        }
        val module = compiled.first().compiledScript?.getCompiledModule() as? KJvmCompiledModuleInMemoryImpl
            ?: return BatchCompilationResult.Failure(
                SharedReplDiagnostic("<batch-module>", "K2 compiler did not produce an in-memory JVM module")
            )
        return BatchCompilationResult.Success(
            BatchCompiledGeneration(compiled, module.compilerOutputFiles, graph)
        )
    }

    override fun close() {
        org.jetbrains.kotlin.com.intellij.openapi.util.Disposer.dispose(disposable)
    }

    private fun failure(
        source: String,
        sourceCodes: List<BatchSourceCode> = emptyList()
    ): BatchCompilationResult.Failure {
        val errors = collector.diagnostics.filter { report ->
            report.severity >= kotlin.script.experimental.api.ScriptDiagnostic.Severity.ERROR
        }
        val diagnostic = errors.firstOrNull()
            ?: collector.diagnostics.firstOrNull()
        return BatchCompilationResult.Failure(
            SharedReplDiagnostic(
                diagnostic?.sourcePath?.let { reported ->
                    sourceCodes.firstOrNull { candidate -> candidate.name == reported }?.source?.name ?: reported
                } ?: source,
                errors.takeIf(List<*>::isNotEmpty)?.joinToString("\n") { report -> report.message }
                    ?: diagnostic?.message
                    ?: "K2 batch compilation failed",
                diagnostic?.location?.start?.line,
                diagnostic?.location?.start?.col,
                diagnostic?.exception
            )
        )
    }

    private fun analysisFailure(
        source: String,
        sourceCodes: List<BatchSourceCode> = emptyList()
    ): BatchAnalysisResult.Failure {
        val failure = failure(source, sourceCodes).diagnostic
        return BatchAnalysisResult.Failure(failure)
    }

    private class BatchSourceCode(val source: SharedReplSource) : SourceCode {
        override val text: String = source.text
        override val name: String = buildString {
            append(Sha256.text(source.name).take(12))
            append('-')
            append(source.name.substringAfterLast('/'))
        }
        override val locationId: String = source.name
    }

    companion object {
        @OptIn(K1SpecificScriptingServiceAccessor::class, ExperimentalCompilerApi::class)
        private fun createState(
            collector: ScriptDiagnosticsMessageCollector,
            configuration: ScriptCompilationConfiguration,
            hostConfiguration: ScriptingHostConfiguration,
            disposable: org.jetbrains.kotlin.com.intellij.openapi.Disposable
        ): K2ReplCompilationState {
            val moduleName = Name.special("<EternalScript-REPL>")
            val compilerContext = createIsolatedCompilationContext(
                configuration,
                hostConfiguration,
                collector,
                disposable
            ) {
                add(
                    CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS,
                    BatchReplCompilerPluginRegistrar(hostConfiguration)
                )
            }
            val project = compilerContext.environment.project
            val languageSettings = compilerContext.environment.configuration.languageVersionSettings
            val classpath = configuration[ScriptCompilationConfiguration.dependencies].orEmpty().flatMap { dependency ->
                (dependency as? JvmDependency)?.classpath ?: emptyList()
            }
            compilerContext.environment.updateClasspath(classpath.map(::JvmClasspathRoot))
            val projectEnvironment = compilerContext.environment.toVfsBasedProjectEnvironment()
            val registrars = compilerContext.environment.configuration.getCompilerExtensions(FirExtensionRegistrar)
            val searchScope = PsiBasedProjectFileSearchScope(
                org.jetbrains.kotlin.com.intellij.psi.search.ProjectScope.getLibrariesScope(project)
            )
            val moduleDataProvider = ReplModuleDataProvider(classpath.map(File::toPath))
            val sessionContext = FirJvmSessionFactory.Context(
                configuration = compilerContext.environment.configuration,
                projectEnvironment = projectEnvironment,
                librariesScope = searchScope
            )
            val sharedLibrarySession = FirJvmSessionFactory.createSharedLibrarySession(
                mainModuleName = moduleName,
                extensionRegistrars = registrars,
                languageVersionSettings = languageSettings,
                context = sessionContext
            )
            FirJvmSessionFactory.createLibrarySession(
                sharedLibrarySession,
                moduleDataProvider = moduleDataProvider,
                extensionRegistrars = registrars,
                languageVersionSettings = languageSettings,
                context = sessionContext
            )
            return K2ReplCompilationState(
                configuration,
                hostConfiguration,
                projectEnvironment,
                moduleDataProvider,
                collector,
                compilerContext,
                sharedLibrarySession,
                sessionContext,
                ScriptConfigurationsProvider.getInstance(project)
            )
        }
    }
}
