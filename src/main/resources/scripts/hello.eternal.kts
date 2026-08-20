/**
 * Active starter script installed only when the scripts directory is first created.
 * Edit this file in place, or disable it with `/es unload hello.eternal.kts`.
 */

onLoad {
    feedback(
        Bukkit.getConsoleSender(),
        "EternalScript starter loaded",
        ScriptFeedbackLevel.SUCCESS
    )
}

on<PlayerJoinEvent> { event ->
    feedback(
        event.player,
        "Welcome, ${event.player.name}!",
        ScriptFeedbackLevel.SUCCESS
    )
}
