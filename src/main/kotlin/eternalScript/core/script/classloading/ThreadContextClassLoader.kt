package eternalScript.core.script.classloading

internal fun <T> withThreadContextClassLoader(
    classLoader: ClassLoader,
    block: () -> T
): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    if (previous === classLoader) return block()
    thread.contextClassLoader = classLoader
    return try {
        block()
    } finally {
        thread.contextClassLoader = previous
    }
}

internal fun isParentFirstScriptClass(name: String): Boolean =
    PARENT_FIRST_CLASS_PREFIXES.any(name::startsWith)

private val PARENT_FIRST_CLASS_PREFIXES = listOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "kotlinx.",
    "org.bukkit.",
    "io.papermc.",
    "com.destroystokyo.paper.",
    "org.spigotmc.",
    "net.minecraft.",
    "net.kyori.",
    "com.mojang.brigadier.",
    "eternalScript."
)
