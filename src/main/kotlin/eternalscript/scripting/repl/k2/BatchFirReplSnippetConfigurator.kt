/*
 * Portions adapted from the Kotlin compiler.
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.builder.Context
import org.jetbrains.kotlin.fir.builder.FirReplSnippetConfiguratorExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.FirFileBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirReplSnippetBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildScriptReceiverParameter
import org.jetbrains.kotlin.fir.expressions.builder.FirBlockBuilder
import org.jetbrains.kotlin.fir.resolve.providers.dependenciesSymbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplSnippetConfiguratorExtensionImpl
import kotlin.script.experimental.host.ScriptingHostConfiguration

/**
 * K2's stock REPL configurator intentionally ignores a script base class. EternalScript
 * supplies the public DSL as an explicit snippet receiver instead, so generated snippets
 * remain ordinary REPL objects while declarations are collected by one Script instance.
 */
internal class BatchFirReplSnippetConfigurator(
    session: FirSession,
    hostConfiguration: ScriptingHostConfiguration
) : FirReplSnippetConfiguratorExtension(session) {
    private val delegate = FirReplSnippetConfiguratorExtensionImpl(session, hostConfiguration)

    override fun isReplSnippetsSource(sourceFile: KtSourceFile?, scriptSource: KtSourceElement): Boolean =
        delegate.isReplSnippetsSource(sourceFile, scriptSource)

    override fun FirReplSnippetBuilder.configureContainingFile(fileBuilder: FirFileBuilder) {
        with(delegate) { this@configureContainingFile.configureContainingFile(fileBuilder) }
    }

    override fun FirReplSnippetBuilder.configure(sourceFile: KtSourceFile?, context: Context<*>) {
        with(delegate) { this@configure.configure(sourceFile, context) }

        val classId = ClassId.topLevel(FqName(requireNotNull(Script::class.qualifiedName)))
        val scriptClass = session.dependenciesSymbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
            ?: error("EternalScript Script DSL class is not on the K2 compiler classpath: $classId")
        receivers += buildScriptReceiverParameter {
            typeRef = buildResolvedTypeRef {
                source = this@configure.source.fakeElement(KtFakeSourceElementKind.ReceiverFromType)
                coneType = scriptClass.constructType(isMarkedNullable = false)
            }
            isBaseClassReceiver = false
            symbol = FirReceiverParameterSymbol()
            moduleData = session.moduleData
            origin = FirDeclarationOrigin.ScriptCustomization.Parameter
            containingDeclarationSymbol = this@configure.symbol
        }
    }

    override fun FirBlockBuilder.configureEvalBody(
        sourceFile: KtSourceFile?,
        scriptSource: KtSourceElement,
        context: Context<*>
    ) {
        with(delegate) { this@configureEvalBody.configureEvalBody(sourceFile, scriptSource, context) }
    }

    override fun MutableList<FirElement>.configure(
        sourceFile: KtSourceFile?,
        scriptSource: KtSourceElement,
        context: Context<*>
    ) {
        with(delegate) { this@configure.configure(sourceFile, scriptSource, context) }
    }

    internal companion object {
        fun getFactory(hostConfiguration: ScriptingHostConfiguration): Factory = Factory { session ->
            BatchFirReplSnippetConfigurator(session, hostConfiguration)
        }
    }
}
