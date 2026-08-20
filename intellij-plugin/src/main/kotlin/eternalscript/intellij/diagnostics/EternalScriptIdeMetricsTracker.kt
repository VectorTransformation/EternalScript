package eternalscript.intellij.diagnostics

import eternalscript.intellij.model.EternalScriptIdeMetrics
import java.util.concurrent.atomic.AtomicLong

internal class EternalScriptIdeMetricsTracker {
    private val workspaceScans = AtomicLong()
    private val changedFiles = AtomicLong()
    private val abiAnalyses = AtomicLong()
    private val configurationReloads = AtomicLong()
    private val cancellations = AtomicLong()
    private val lastAnalysisMillis = AtomicLong()

    fun workspaceScan() { workspaceScans.incrementAndGet() }
    fun changedFiles(count: Long) { changedFiles.addAndGet(count) }
    fun abiAnalysis() { abiAnalyses.incrementAndGet() }
    fun configurationReload() { configurationReloads.incrementAndGet() }
    fun cancellation() { cancellations.incrementAndGet() }
    fun analysisFinished(millis: Long) { lastAnalysisMillis.set(millis.coerceAtLeast(0)) }

    fun snapshot(): EternalScriptIdeMetrics = EternalScriptIdeMetrics(
        workspaceScans.get(),
        changedFiles.get(),
        abiAnalyses.get(),
        configurationReloads.get(),
        cancellations.get(),
        lastAnalysisMillis.get()
    )
}
