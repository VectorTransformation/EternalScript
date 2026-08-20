package eternalscript.api

public enum class ScriptOperationStatus {
    SUCCESS,
    NO_CHANGE,
    BUSY,
    NOT_FOUND,
    INVALID_PATH,
    FAILED,
    CANCELLED,
    DISABLED
}

public enum class ScriptDiagnosticPhase {
    SOURCE,
    COMPILE,
    EVALUATE,
    ACTIVATE,
    ROLLBACK
}

public data class ScriptDiagnostic(
    public val source: String,
    public val phase: ScriptDiagnosticPhase,
    public val message: String,
    public val line: Int? = null,
    public val column: Int? = null
)

public data class ScriptOperationResult(
    public val operation: ScriptOperation,
    public val status: ScriptOperationStatus,
    public val revision: Long,
    public val affectedPaths: List<String> = emptyList(),
    public val diagnostics: List<ScriptDiagnostic> = emptyList()
)
