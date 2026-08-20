/*
 * Portions adapted from the Kotlin compiler.
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress(
    "INVISIBLE_MEMBER",
    "INVISIBLE_REFERENCE",
    "DEPRECATION",
    "DEPRECATION_ERROR"
)

package eternalscript.scripting.repl.k2

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.registerExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.backend.DelicateDeclarationStorageApi
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.Fir2IrReplSnippetConfiguratorExtension
import org.jetbrains.kotlin.fir.backend.Fir2IrVisitor
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirImport
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirReplSnippet
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.utils.isReplSnippetDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.originalReplSnippetSymbol
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirReplHistoryProvider
import org.jetbrains.kotlin.fir.extensions.FirReplSnippetResolveExtension
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.providers.dependenciesSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitorVoid
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrReplSnippet
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firImportsFromDefaultImports
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.getOrLoadConfiguration
import org.jetbrains.kotlin.scripting.compiler.plugin.services.replStateObjectFqName
import org.jetbrains.kotlin.scripting.resolve.FirReplHistoryScope
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.repl
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.ScriptingHostConfiguration

internal class BatchFirReplCompilerExtensionRegistrar(
    private val hostConfiguration: ScriptingHostConfiguration
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +BatchFirReplSnippetConfigurator.getFactory(hostConfiguration)
        +BatchFirReplSnippetResolveExtension.getFactory(hostConfiguration)
        +BatchFir2IrReplSnippetConfigurator.getFactory(hostConfiguration)
    }
}

@OptIn(ExperimentalCompilerApi::class)
internal class BatchReplCompilerPluginRegistrar(
    private val hostConfiguration: ScriptingHostConfiguration
) : CompilerPluginRegistrar() {
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrar.registerExtension(BatchFirReplCompilerExtensionRegistrar(hostConfiguration))
    }

    override val pluginId: String = "eternalscript.batch-repl"
    override val supportsK2: Boolean = true
}

/**
 * Resolves current source peers and previously compiled provider snippet classes
 * through one unqualified REPL scope.
 */
internal class BatchFirReplSnippetResolveExtension(
    session: FirSession,
    private val hostConfiguration: ScriptingHostConfiguration
) : FirReplSnippetResolveExtension(session) {
    private val history: FirReplHistoryProvider = requireNotNull(
        hostConfiguration[ScriptingHostConfiguration.repl.firReplHistoryProvider]
    )
    private val providers = hostConfiguration[ScriptingHostConfiguration.repl.providerSnippets].orEmpty()
    override fun getSnippetDefaultImports(sourceFile: KtSourceFile, snippet: FirReplSnippet): List<FirImport>? =
        getOrLoadConfiguration(snippet.moduleData.session, sourceFile)?.valueOrNull()?.let { configuration ->
            configuration[ScriptCompilationConfiguration.defaultImports]
                ?.distinct()
                ?.firImportsFromDefaultImports(snippet.source.fakeElement(KtFakeSourceElementKind.ImplicitImport))
        }.orEmpty()

    @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
    override fun getSnippetScope(currentSnippet: FirReplSnippet, useSiteSession: FirSession): FirScope {
        val properties = linkedMapOf<Name, ArrayList<org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol<*>>>()
        val functions = linkedMapOf<Name, ArrayList<FirNamedFunctionSymbol>>()
        val classLikes = linkedMapOf<Name, org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol<*>>()

        history.getSnippets().forEach { snippet ->
            if (snippet == currentSnippet) return@forEach
            snippet.snippetClassSymbol.declarationSymbols
                .filter { symbol -> symbol.isReplSnippetDeclaration == true }
                .forEach { symbol ->
                    symbol.fir.originalReplSnippetSymbol = snippet
                    addToScope(symbol.fir, properties, functions, classLikes)
                }
        }
        providers.forEach { provider ->
            val classId = ClassId.topLevel(FqName(provider.className))
            val providerClass = useSiteSession.dependenciesSymbolProvider
                .getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
                ?: return@forEach
            providerClass.processAllDeclarations(useSiteSession, FirResolvePhase.TYPES) { symbol ->
                addToScope(symbol.fir, properties, functions, classLikes)
            }
        }
        return FirReplHistoryScope(properties, functions, classLikes, useSiteSession)
    }

    private fun addToScope(
        declaration: FirDeclaration,
        properties: MutableMap<Name, ArrayList<org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol<*>>>,
        functions: MutableMap<Name, ArrayList<FirNamedFunctionSymbol>>,
        classLikes: MutableMap<Name, org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol<*>>
    ) {
        when (declaration) {
            is FirProperty -> properties.getOrPut(declaration.name) { arrayListOf() } += declaration.symbol
            is FirNamedFunction -> functions.getOrPut(declaration.name) { arrayListOf() } += declaration.symbol
            is FirRegularClass -> classLikes[declaration.name] = declaration.symbol
            is FirTypeAlias -> classLikes[declaration.name] = declaration.symbol
            else -> Unit
        }
    }

    override fun updateResolved(snippet: FirReplSnippet) {
        history.putSnippet(snippet.symbol)
    }

    internal companion object {
        fun getFactory(hostConfiguration: ScriptingHostConfiguration): Factory = Factory { session ->
            BatchFirReplSnippetResolveExtension(session, hostConfiguration)
        }
    }
}

/**
 * The stock configurator assumes every referenced snippet was compiled in an older module.
 * A batch contains source peers in the current module, whose IR declarations must be reused
 * instead of recreated as lazy external declarations.
 */
internal class BatchFir2IrReplSnippetConfigurator(
    private val firSession: FirSession,
    private val hostConfiguration: ScriptingHostConfiguration
) : Fir2IrReplSnippetConfiguratorExtension(firSession) {
    @OptIn(SymbolInternals::class, UnsafeDuringIrConstructionAPI::class, DelicateDeclarationStorageApi::class)
    override fun Fir2IrComponents.prepareSnippet(
        fir2IrVisitor: Fir2IrVisitor,
        firReplSnippet: FirReplSnippet,
        irSnippet: IrReplSnippet
    ) {
        val properties = linkedMapOf<FirPropertySymbol, FirReplSnippetSymbol>()
        val functions = linkedMapOf<FirNamedFunctionSymbol, FirReplSnippetSymbol>()
        val classes = linkedMapOf<FirRegularClassSymbol, FirReplSnippetSymbol>()
        val binaryProperties = linkedMapOf<FirPropertySymbol, ProviderSnippetDescriptor>()
        val binaryFunctions = linkedMapOf<FirNamedFunctionSymbol, ProviderSnippetDescriptor>()
        val binaryClasses = linkedMapOf<FirRegularClassSymbol, ProviderSnippetDescriptor>()
        val snippets = linkedSetOf<FirReplSnippetSymbol>()

        BatchCrossSnippetAccessCollector(
            firSession,
            hostConfiguration[ScriptingHostConfiguration.repl.providerSnippets].orEmpty(),
            properties,
            functions,
            classes,
            binaryProperties,
            binaryFunctions,
            binaryClasses,
            snippets
        )
            .visitReplSnippet(firReplSnippet)
        snippets.remove(firReplSnippet.symbol)

        val sourcePeers = snippets.filter { symbol -> symbol.moduleData.session === firSession }.toSet()
        val externalPeers = snippets - sourcePeers

        externalPeers.forEach { symbol ->
            val packageFragment = declarationStorage.getIrExternalPackageFragment(
                symbol.fir.snippetClass.symbol.classId.packageFqName,
                symbol.moduleData
            )
            classifierStorage.createAndCacheEarlierSnippetClass(symbol, packageFragment)
        }

        properties.forEach { (symbol, ownerSnippet) ->
            if (ownerSnippet == firReplSnippet.symbol || ownerSnippet in sourcePeers) {
                val property = (declarationStorage.getIrPropertySymbol(symbol) as IrPropertySymbol).owner
                addPeerDeclaration(irSnippet, property)
                property.getter?.let { getter -> addPeerDeclaration(irSnippet, getter) }
                property.setter?.let { setter -> addPeerDeclaration(irSnippet, setter) }
            } else {
                val owner = classifierStorage.getCachedEarlierSnippetClass(ownerSnippet) ?: return@forEach
                val property = declarationStorage.createAndCacheIrProperty(
                    symbol.fir,
                    owner,
                    org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.REPL_FROM_OTHER_SNIPPET,
                    allowLazyDeclarationsCreation = true
                )
                property.parent = owner
                property.getter?.apply {
                    parent = owner
                    origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.REPL_FROM_OTHER_SNIPPET
                    addPeerDeclaration(irSnippet, this)
                }
                property.setter?.apply {
                    parent = owner
                    origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.REPL_FROM_OTHER_SNIPPET
                    addPeerDeclaration(irSnippet, this)
                }
                addPeerDeclaration(irSnippet, property)
            }
        }

        functions.forEach { (symbol, ownerSnippet) ->
            if (ownerSnippet == firReplSnippet.symbol || ownerSnippet in sourcePeers) {
                val function = declarationStorage.getCachedIrFunctionSymbol(symbol.fir)?.owner ?: return@forEach
                addPeerDeclaration(irSnippet, function)
            } else {
                val owner = classifierStorage.getCachedEarlierSnippetClass(ownerSnippet) ?: return@forEach
                val function = declarationStorage.createAndCacheIrFunction(
                    symbol.fir,
                    owner,
                    predefinedOrigin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.REPL_FROM_OTHER_SNIPPET,
                    allowLazyDeclarationsCreation = true
                )
                function.parent = owner
                function.visibility = DescriptorVisibilities.PUBLIC
                addPeerDeclaration(irSnippet, function)
            }
        }

        // Source peer classes are already created and cached by FIR2IR's header phase.
        // External class handling remains delegated to the stock earlier-snippet cache above.
        classes.forEach { (symbol, ownerSnippet) ->
            if (ownerSnippet != firReplSnippet.symbol && ownerSnippet !in sourcePeers) {
                classifierStorage.getCachedEarlierSnippetClass(ownerSnippet)?.let { owner ->
                    classifierStorage.getFir2IrLazyClass(symbol.fir).apply {
                        parent = owner
                        origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.REPL_FROM_OTHER_SNIPPET
                        addPeerDeclaration(irSnippet, this)
                    }
                }
            }
        }

        binaryProperties.keys.forEach { symbol ->
            val property = (declarationStorage.getIrPropertySymbol(symbol) as IrPropertySymbol).owner
            addPeerDeclaration(irSnippet, property)
            property.getter?.let { getter -> addPeerDeclaration(irSnippet, getter) }
            property.setter?.let { setter -> addPeerDeclaration(irSnippet, setter) }
        }
        binaryFunctions.keys.forEach { symbol ->
            addPeerDeclaration(irSnippet, declarationStorage.getIrFunctionSymbol(symbol).owner)
        }
        binaryClasses.keys.forEach { symbol ->
            addPeerDeclaration(irSnippet, classifierStorage.getFir2IrLazyClass(symbol.fir))
        }

        irSnippet.stateObject = externalStateClass().symbol
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun addPeerDeclaration(snippet: IrReplSnippet, declaration: IrDeclaration) {
        if (declaration !in snippet.declarationsFromOtherSnippets) {
            snippet.declarationsFromOtherSnippets += declaration
        }
    }

    @OptIn(SymbolInternals::class)
    private fun Fir2IrComponents.externalStateClass(): org.jetbrains.kotlin.ir.declarations.IrClass {
        val stateName = requireNotNull(
            hostConfiguration[ScriptingHostConfiguration.repl.replStateObjectFqName]
        ) { "A batch REPL state object must be configured" }
        val fqName = FqName(stateName)
        val classId = ClassId(fqName.parent(), fqName.shortName())
        val firClass = (firSession.dependenciesSymbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol)
            ?.fir
            ?: error("Batch REPL state class is not on the script classpath: $stateName")
        val parent = declarationStorage.getIrExternalPackageFragment(classId.packageFqName, firSession.moduleData)
        return lazyDeclarationsGenerator.createIrLazyClass(firClass, parent, IrClassSymbolImpl())
    }

    internal companion object {
        fun getFactory(hostConfiguration: ScriptingHostConfiguration): Factory = Factory { session ->
            BatchFir2IrReplSnippetConfigurator(session, hostConfiguration)
        }
    }
}

private class BatchCrossSnippetAccessCollector(
    private val session: FirSession,
    private val providers: List<ProviderSnippetDescriptor>,
    private val properties: MutableMap<FirPropertySymbol, FirReplSnippetSymbol>,
    private val functions: MutableMap<FirNamedFunctionSymbol, FirReplSnippetSymbol>,
    private val classes: MutableMap<FirRegularClassSymbol, FirReplSnippetSymbol>,
    private val binaryProperties: MutableMap<FirPropertySymbol, ProviderSnippetDescriptor>,
    private val binaryFunctions: MutableMap<FirNamedFunctionSymbol, ProviderSnippetDescriptor>,
    private val binaryClasses: MutableMap<FirRegularClassSymbol, ProviderSnippetDescriptor>,
    private val snippets: MutableSet<FirReplSnippetSymbol>
) : FirDefaultVisitorVoid() {
    @OptIn(SymbolInternals::class)
    private fun store(symbol: FirBasedSymbol<FirDeclaration>) {
        val owner = symbol.fir.originalReplSnippetSymbol
        if (owner != null) {
            snippets += owner
            when (symbol) {
                is FirPropertySymbol -> properties[symbol] = owner
                is FirNamedFunctionSymbol -> functions[symbol] = owner
                is FirRegularClassSymbol -> classes[symbol] = owner
            }
            return
        }
        val provider = providerOf(symbol) ?: return
        when (symbol) {
            is FirPropertySymbol -> binaryProperties[symbol] = provider
            is FirNamedFunctionSymbol -> binaryFunctions[symbol] = provider
            is FirRegularClassSymbol -> binaryClasses[symbol] = provider
        }
    }

    private fun providerOf(symbol: FirBasedSymbol<FirDeclaration>): ProviderSnippetDescriptor? {
        val ownerName = when (symbol) {
            is FirCallableSymbol<*> -> symbol.callableId?.classId?.asSingleFqName()?.asString()
            is FirRegularClassSymbol -> symbol.classId.asSingleFqName().asString()
            else -> null
        } ?: return null
        return providers.firstOrNull { provider ->
            ownerName == provider.className || ownerName.startsWith(provider.className + ".")
        }
    }

    @OptIn(UnresolvedExpressionTypeAccess::class)
    override fun visitElement(element: FirElement) {
        (element as? FirExpression)?.coneTypeOrNull?.toClassSymbol(session)?.let(::store)
        element.acceptChildren(this)
    }

    @OptIn(SymbolInternals::class)
    override fun visitResolvedNamedReference(resolvedNamedReference: FirResolvedNamedReference) {
        val resolved = resolvedNamedReference.resolvedSymbol
        val symbol = when (resolved) {
            is FirConstructorSymbol -> (resolved.fir.returnTypeRef as? FirResolvedTypeRef)?.coneType?.toSymbol(session)
            is FirCallableSymbol<*> -> {
                resolved.resolvedReturnTypeRef.accept(this)
                resolved
            }
            else -> null
        } ?: resolved
        store(symbol)
    }

    override fun visitResolvedTypeRef(resolvedTypeRef: FirResolvedTypeRef) {
        resolvedTypeRef.coneType.toClassSymbol(session)?.let(::store)
        resolvedTypeRef.coneType.typeArguments.forEach { argument ->
            argument.type?.toClassSymbol(session)?.let(::store)
        }
    }
}
