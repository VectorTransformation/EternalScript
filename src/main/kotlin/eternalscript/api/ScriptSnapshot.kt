package eternalscript.api

public enum class ScriptEngineState {
    STARTING,
    READY,
    DISABLED
}

public enum class ScriptOperation {
    RELOAD,
    RECOMPILE,
    LOAD,
    UNLOAD,
    CLEAR
}

public data class ScriptInfo(
    public val path: String
)

public data class ScriptSnapshot(
    public val revision: Long,
    public val state: ScriptEngineState,
    public val busyOperation: ScriptOperation?,
    public val scripts: List<ScriptInfo>
)
