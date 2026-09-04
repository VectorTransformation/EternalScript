import org.bukkit.event.player.PlayerJoinEvent

val exampleProfiles = storage("example.profiles")
val exampleCoins = longKey("coins", 0L)
val exampleCharacter = stringKey("character")

on<PlayerJoinEvent> { event ->
    storageTask {
        try {
            val profile = exampleProfiles.player(event.player.uniqueId)
            val balance = profile.update {
                val next = this[exampleCoins] + 100L
                this[exampleCoins] = next
                if (this[exampleCharacter] == null) this[exampleCharacter] = "starter"
                next
            }
            log.debug { "Stored example profile balance=$balance" }
            notify(event.player).success("Persistent balance: $balance")
        } catch (error: Throwable) {
            log.error("Could not save example profile", error)
            notify(event.player).error("Could not save profile: ${error.message}")
        }
    }
}
