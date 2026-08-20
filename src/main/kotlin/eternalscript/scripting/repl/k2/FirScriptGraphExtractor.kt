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

import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.contracts.description.KtCallsEffectDeclaration
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirReplSnippet
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.utils.isReplSnippetDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.originalReplSnippetSymbol
import org.jetbrains.kotlin.fir.contracts.effects
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitorVoid
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

internal object FirScriptGraphExtractor {
    @OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)
    fun classifiers(
        snippets: List<FirReplSnippet>,
        pathBySnippet: Map<FirReplSnippetSymbol, String>
    ): Map<String, List<ScriptClassifierDescriptor>> = snippets.associate { snippet ->
        val path = pathBySnippet.getValue(snippet.symbol)
        val classifiers = snippet.symbol.snippetClassSymbol.declarationSymbols
            .asSequence()
            .filter { symbol -> symbol.isReplSnippetDeclaration == true }
            .mapNotNull { symbol ->
                when (val declaration = symbol.fir) {
                    is FirRegularClass -> ScriptClassifierDescriptor(
                        declaration.name.asString(),
                        declaration.symbol.classId.asSingleFqName().asString(),
                        declaration.classKind.name
                    )
                    is FirTypeAlias -> ScriptClassifierDescriptor(
                        declaration.name.asString(),
                        declaration.symbol.classId.asSingleFqName().asString(),
                        "TYPE_ALIAS"
                    )
                    else -> null
                }
            }
            .sortedWith(compareBy(ScriptClassifierDescriptor::name, ScriptClassifierDescriptor::importPath))
            .toList()
        path to classifiers
    }

    @OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)
    fun declarationConflict(
        snippets: List<FirReplSnippet>,
        pathBySnippet: Map<FirReplSnippetSymbol, String>
    ): SharedDeclarationConflict? {
        val owners = linkedMapOf<SharedDeclarationKey, Pair<String, String>>()
        snippets.forEach { snippet ->
            val path = pathBySnippet.getValue(snippet.symbol)
            snippet.symbol.snippetClassSymbol.declarationSymbols
                .filter { symbol -> symbol.isReplSnippetDeclaration == true }
                .mapNotNull { symbol -> sharedDeclarationKey(symbol.fir) }
                .forEach { (key, displayName) ->
                    val previous = owners.putIfAbsent(key, path to displayName) ?: return@forEach
                    if (previous.first != path) {
                        return SharedDeclarationConflict(
                            path,
                            "Duplicate shared declaration '$displayName' in ${previous.first} and $path"
                        )
                    }
                }
        }
        return null
    }

    @OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)
    fun extract(
        session: FirSession,
        snippets: List<FirReplSnippet>,
        pathBySnippet: Map<FirReplSnippetSymbol, String>
    ): ScriptGraphResult {
        val dependencies = pathBySnippet.values.associateWithTo(linkedMapOf()) { linkedSetOf<String>() }
        val initializationDependencies = pathBySnippet.values.associateWithTo(linkedMapOf()) { linkedSetOf<String>() }

        snippets.forEach { snippet ->
            val consumer = pathBySnippet.getValue(snippet.symbol)
            val allReferences = CrossSnippetReferenceVisitor(session) { owner ->
                pathBySnippet[owner]?.takeIf { provider -> provider != consumer }?.let {
                    dependencies.getValue(consumer) += it
                }
            }
            snippet.accept(allReferences)

            val eagerReferences = CrossSnippetReferenceVisitor(
                session,
                skipDeferredBodies = true,
                followInvokedBodies = true
            ) { owner ->
                pathBySnippet[owner]?.takeIf { provider -> provider != consumer }?.let {
                    initializationDependencies.getValue(consumer) += it
                }
            }
            snippet.evalFunctionSymbol.fir.body?.accept(eagerReferences)
        }

        return ScriptDependencyGraph.create(
            pathBySnippet.values,
            dependencies,
            initializationDependencies
        )
    }

    private fun sharedDeclarationKey(declaration: FirDeclaration): Pair<SharedDeclarationKey, String>? =
        when (declaration) {
            is FirProperty -> SharedDeclarationKey.Property(declaration.name) to declaration.name.asString()
            is FirRegularClass -> SharedDeclarationKey.Classifier(declaration.name) to declaration.name.asString()
            is FirTypeAlias -> SharedDeclarationKey.Classifier(declaration.name) to declaration.name.asString()
            is FirNamedFunction -> {
                if (declaration.name.isSpecial) return null
                val signature = buildString {
                    append(declaration.name.asString())
                    append('<').append(declaration.typeParameters.size).append('>')
                    append('[')
                    declaration.contextParameters.forEach { parameter ->
                        append(parameter.returnTypeRef.renderType()).append(';')
                    }
                    append(']')
                    append(declaration.receiverParameter?.typeRef?.renderType().orEmpty())
                    append('(')
                    declaration.valueParameters.forEach { parameter ->
                        append(parameter.returnTypeRef.renderType()).append(';')
                    }
                    append(')')
                }
                SharedDeclarationKey.Function(signature) to declaration.name.asString()
            }
            else -> null
        }

    private fun FirTypeRef.renderType(): String =
        (this as? FirResolvedTypeRef)?.coneType?.toString() ?: toString()
}

internal data class SharedDeclarationConflict(val source: String, val message: String)

private sealed interface SharedDeclarationKey {
    data class Property(val name: Name) : SharedDeclarationKey
    data class Classifier(val name: Name) : SharedDeclarationKey
    data class Function(val signature: String) : SharedDeclarationKey
}

private class CrossSnippetReferenceVisitor(
    private val session: FirSession,
    private val skipDeferredBodies: Boolean = false,
    private val followInvokedBodies: Boolean = false,
    private val record: (FirReplSnippetSymbol) -> Unit
) : FirDefaultVisitorVoid() {
    private val followedDeclarations: MutableSet<FirDeclaration> =
        Collections.newSetFromMap(IdentityHashMap())

    @OptIn(SymbolInternals::class)
    private fun store(symbol: FirBasedSymbol<*>) {
        symbol.fir.originalReplSnippetSymbol?.let(record)
    }

    private fun storeType(type: ConeKotlinType) {
        type.abbreviatedType?.toSymbol(session)?.let(::store)
        type.toSymbol(session)?.let(::store)
        type.typeArguments.forEach { argument -> argument.type?.let(::storeType) }
    }

    @OptIn(UnresolvedExpressionTypeAccess::class)
    override fun visitElement(element: FirElement) {
        (element as? FirExpression)?.coneTypeOrNull?.let(::storeType)
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
        if (followInvokedBodies && resolved.fir is FirProperty) followInvokedDeclaration(resolved)
    }

    @OptIn(SymbolInternals::class)
    private fun followInvokedDeclaration(symbol: FirBasedSymbol<*>, call: FirFunctionCall? = null) {
        val declaration = symbol.fir
        if (declaration is FirRegularClass) {
            followClassInitialization(declaration)
            return
        }
        if (!followedDeclarations.add(declaration)) return
        when (declaration) {
            is FirNamedFunction -> {
                followDefaultArguments(declaration, call)
                declaration.body?.accept(this)
            }
            is FirConstructor -> {
                followDefaultArguments(declaration, call)
                declaration.body?.accept(this)
                (declaration.returnTypeRef as? FirResolvedTypeRef)
                    ?.coneType
                    ?.toClassSymbol(session)
                    ?.fir
                    ?.let { owner -> (owner as? FirRegularClass)?.let(::followClassInitialization) }
            }
            is FirProperty -> declaration.getter?.body?.accept(this)
            else -> Unit
        }
    }

    private fun followDefaultArguments(
        function: org.jetbrains.kotlin.fir.declarations.FirFunction,
        call: FirFunctionCall?
    ) {
        val supplied = call?.resolvedArgumentMapping?.values.orEmpty().toSet()
        function.valueParameters.forEach { parameter ->
            if (parameter !in supplied) parameter.defaultValue?.accept(this)
        }
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun followClassInitialization(regularClass: FirRegularClass) {
        if (!followedDeclarations.add(regularClass)) return
        regularClass.declarations.forEach { declaration ->
            when (declaration) {
                is FirProperty -> {
                    declaration.initializer?.accept(this)
                    declaration.delegate?.accept(this)
                }
                is FirAnonymousInitializer -> declaration.body?.accept(this)
                else -> Unit
            }
        }
    }

    @OptIn(SymbolInternals::class)
    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        super.visitFunctionCall(functionCall)
        if (!followInvokedBodies) return
        val symbol = (functionCall.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol ?: return
        followInvokedDeclaration(symbol, functionCall)
        followCallsInPlaceArguments(functionCall, symbol)
    }

    @OptIn(SymbolInternals::class)
    private fun followCallsInPlaceArguments(call: FirFunctionCall, symbol: FirBasedSymbol<*>) {
        val function = symbol.fir as? FirNamedFunction ?: return
        val eagerParameters = function.contractDescription?.effects.orEmpty()
            .mapNotNull { effect -> effect.effect as? KtCallsEffectDeclaration<*, *> }
            .filter { effect ->
                effect.kind == EventOccurrencesRange.EXACTLY_ONCE ||
                    effect.kind == EventOccurrencesRange.AT_LEAST_ONCE ||
                    effect.kind == EventOccurrencesRange.MORE_THAN_ONCE
            }
            .mapTo(hashSetOf()) { effect ->
                effect.valueParameterReference.name to effect.valueParameterReference.parameterIndex
            }
        if (eagerParameters.isEmpty()) return
        call.resolvedArgumentMapping.orEmpty().forEach { (argument, parameter) ->
            val index = function.valueParameters.indexOf(parameter)
            val isEager = eagerParameters.any { (name, parameterIndex) ->
                name == parameter.name.asString() || parameterIndex == index
            }
            if (isEager) argument.unwrapAnonymousFunctionExpression()?.body?.accept(this)
        }
    }

    override fun visitResolvedTypeRef(resolvedTypeRef: FirResolvedTypeRef) {
        storeType(resolvedTypeRef.coneType)
    }

    override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) {
        if (
            !skipDeferredBodies ||
            anonymousFunctionExpression.anonymousFunction.origin == FirDeclarationOrigin.Synthetic.ReplEvalFunction
        ) {
            super.visitAnonymousFunctionExpression(anonymousFunctionExpression)
        }
    }

    override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
        if (!skipDeferredBodies || anonymousFunction.origin == FirDeclarationOrigin.Synthetic.ReplEvalFunction) {
            super.visitAnonymousFunction(anonymousFunction)
        }
    }

    override fun visitNamedFunction(namedFunction: FirNamedFunction) {
        if (!skipDeferredBodies) super.visitNamedFunction(namedFunction)
    }

    override fun visitRegularClass(regularClass: FirRegularClass) {
        if (!skipDeferredBodies) super.visitRegularClass(regularClass)
    }
}
