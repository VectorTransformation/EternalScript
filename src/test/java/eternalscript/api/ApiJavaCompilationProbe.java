package eternalscript.api;

import java.util.concurrent.CompletionStage;

final class ApiJavaCompilationProbe {
    EternalScriptApi resolve() {
        EternalScriptApi nullable = EternalScriptApi.getOrNull();
        return nullable != null ? nullable : EternalScriptApi.get();
    }

    CompletionStage<ScriptOperationResult> use(EternalScriptApi api) {
        ScriptSnapshot snapshot = api.snapshot();
        ScriptEngineState state = snapshot.getState();
        int version = EternalScriptApi.API_VERSION;
        if (state == ScriptEngineState.DISABLED || version < 1) {
            return api.cancel();
        }
        return api.reload().thenApply(result -> {
            ScriptOperationStatus status = result.getStatus();
            long revision = result.getRevision();
            if (status == ScriptOperationStatus.SUCCESS && revision < snapshot.getRevision()) {
                throw new IllegalStateException("Revision moved backwards");
            }
            return result;
        });
    }
}
