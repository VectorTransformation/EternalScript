/*
 * Portions adapted from the Kotlin compiler.
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package eternalscript.scripting.repl.k2

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirGlobalResolveProcessor
import org.jetbrains.kotlin.fir.resolve.transformers.FirTransformerBasedResolveProcessor
import org.jetbrains.kotlin.fir.resolve.transformers.createAllCompilerResolveProcessors
import org.jetbrains.kotlin.fir.withFileAnalysisExceptionWrapping

/**
 * Runs Kotlin's compiler resolution pipeline with a batch-aware replacement for
 * the phase that calculates implicit callable types.
 */
internal fun FirSession.runBatchResolution(
    files: List<FirFile>
): ScopeSession {
    val scopeSession = ScopeSession()
    val processors = createAllCompilerResolveProcessors(this, scopeSession).map { processor ->
        if (processor.phase == FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {
            BatchImplicitTypeBodyResolveProcessor(this, scopeSession)
        } else {
            processor
        }
    }
    processors.forEach { processor ->
        processor.beforePhase()
        try {
            when (processor) {
                is FirTransformerBasedResolveProcessor -> {
                    files.forEach { file ->
                        withFileAnalysisExceptionWrapping(file) {
                            processor.processFile(file)
                        }
                    }
                }

                is FirGlobalResolveProcessor -> processor.process(files)
            }
        } finally {
            processor.afterPhase()
        }
    }
    return scopeSession
}
