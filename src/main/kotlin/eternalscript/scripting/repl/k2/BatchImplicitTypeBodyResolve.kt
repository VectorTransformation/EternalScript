/*
 * Portions adapted from the Kotlin compiler.
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package eternalscript.scripting.repl.k2

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.utils.originalReplSnippetSymbol
import org.jetbrains.kotlin.fir.expressions.FirReplExpressionReference
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.transformers.AdapterForResolveProcessor
import org.jetbrains.kotlin.fir.resolve.transformers.FirTransformerBasedResolveProcessor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirImplicitAwareBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.ImplicitBodyResolveComputationSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.ReturnTypeCalculatorWithJump
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.hasResolvedType
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.withFileAnalysisExceptionWrapping
import java.util.IdentityHashMap

/**
 * K2 normally compiles one REPL snippet at a time, so an older snippet property
 * has already had its synthetic eval function resolved. Batch peers are all
 * visible at once. When a peer property still points at its eval expression,
 * resolve its owning FIR file in the same computation session before asking K2
 * for the property type.
 */
@OptIn(AdapterForResolveProcessor::class)
internal class BatchImplicitTypeBodyResolveProcessor(
    session: FirSession,
    scopeSession: ScopeSession
) : FirTransformerBasedResolveProcessor(
    session,
    scopeSession,
    FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE
) {
    private val batchTransformer = BatchImplicitTypeBodyResolveTransformer(
        session,
        scopeSession
    )
    override val transformer: FirTransformer<Nothing?> = batchTransformer

    override fun processFile(file: FirFile) {
        batchTransformer.resolveFile(file)
    }
}

private class BatchImplicitTypeBodyResolveTransformer(
    private val session: FirSession,
    private val scopeSession: ScopeSession
) : FirTransformer<Nothing?>() {
    private val computationSession = ImplicitBodyResolveComputationSession()
    private val fileStates = IdentityHashMap<FirFile, FileResolutionState>()
    private val returnTypeCalculator = BatchReturnTypeCalculator(
        scopeSession,
        computationSession,
        ::resolveOwner
    )

    override fun <E : FirElement> transformElement(element: E, data: Nothing?): E = element

    override fun transformFile(file: FirFile, data: Nothing?): FirFile {
        resolveFile(file)
        return file
    }

    fun resolveFile(file: FirFile) {
        when (fileStates[file]) {
            FileResolutionState.RESOLVING,
            FileResolutionState.RESOLVED -> return

            null -> Unit
        }
        fileStates[file] = FileResolutionState.RESOLVING
        try {
            withFileAnalysisExceptionWrapping(file) {
                file.transform<FirFile, ResolutionMode>(createDelegate(), ResolutionMode.ContextIndependent)
                Unit
            }
            fileStates[file] = FileResolutionState.RESOLVED
        } catch (error: Throwable) {
            fileStates.remove(file)
            throw error
        }
    }

    @OptIn(SymbolInternals::class)
    private fun resolveOwner(owner: FirReplSnippetSymbol) {
        val ownerFile = owner.fir.moduleData.session.firProvider.getFirReplSnippetContainerFile(owner)
            ?: error("REPL peer snippet has no containing FIR file: ${owner.fir.name}")
        resolveFile(ownerFile)
    }

    private fun createDelegate(): FirImplicitAwareBodyResolveTransformer =
        FirImplicitAwareBodyResolveTransformer(
            session,
            scopeSession,
            computationSession,
            FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE,
            implicitTypeOnly = true,
            returnTypeCalculator
        )

    private enum class FileResolutionState {
        RESOLVING,
        RESOLVED
    }
}

private class BatchReturnTypeCalculator(
    scopeSession: ScopeSession,
    computationSession: ImplicitBodyResolveComputationSession,
    private val resolveOwner: (FirReplSnippetSymbol) -> Unit
) : ReturnTypeCalculatorWithJump(scopeSession, computationSession) {
    @OptIn(SymbolInternals::class)
    override fun tryCalculateReturnTypeOrNull(declaration: FirCallableDeclaration): FirResolvedTypeRef {
        val property = declaration as? FirProperty
        val owner = property?.originalReplSnippetSymbol
        if (property != null && owner != null && property.hasUnresolvedReplExpression) {
            resolveOwner(owner)
            (property.returnTypeRef as? FirResolvedTypeRef)?.let { return it }
        }
        return super.tryCalculateReturnTypeOrNull(declaration)
    }

    private val FirProperty.hasUnresolvedReplExpression: Boolean
        get() {
            val initializer = initializer as? FirReplExpressionReference
            val delegate = delegate as? FirReplExpressionReference
            return (initializer != null || delegate != null) &&
                initializer?.hasResolvedType != true &&
                delegate?.hasResolvedType != true
        }
}
