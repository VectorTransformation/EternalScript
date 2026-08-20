/**
 * Lifecycle hooks run on activation and in reverse initialization order on unload.
 * They may run again when this script or one of its providers is replaced.
 */

onLoad {
    feedback(
        Bukkit.getConsoleSender(),
        "Lifecycle example activated",
        ScriptFeedbackLevel.SUCCESS
    )
}

onUnload {
    feedback(
        Bukkit.getConsoleSender(),
        "Lifecycle example deactivated",
        ScriptFeedbackLevel.INFO
    )
}
