package eternalscript.api.script.storage

import java.util.UUID

/** A persistent namespace shared by every script that opens the same name. */
public interface Storage {
    public val namespace: String

    public fun global(): StorageScope

    public fun player(uuid: UUID): StorageScope

    public fun world(uuid: UUID): StorageScope
}

/** A global, player, or world key space inside a [Storage] namespace. */
public interface StorageScope {
    public suspend operator fun <T> get(key: StorageKey<T>): T

    public suspend operator fun <T> set(key: StorageKey<T>, value: T)

    public suspend fun contains(key: StorageKey<*>): Boolean

    public suspend fun remove(key: StorageKey<*>)

    /** Runs [block] as one SQLite transaction. Do not access Paper APIs from the block. */
    public suspend fun <R> update(block: StorageTransaction.() -> R): R
}

/** Synchronous view used only while a storage transaction owns the database thread. */
public interface StorageTransaction {
    public operator fun <T> get(key: StorageKey<T>): T

    public operator fun <T> set(key: StorageKey<T>, value: T)

    public fun contains(key: StorageKey<*>): Boolean

    public fun remove(key: StorageKey<*>)
}

/** A typed persistent key. Instances are created by the key factories on the script DSL. */
public class StorageKey<T> internal constructor(
    public val name: String,
    internal val codec: StorageValueCodec<T>,
    internal val defaultValue: () -> T
) {
    override fun toString(): String = "StorageKey(name=$name, type=${codec.typeId})"
}

/** Handle for an asynchronous script-owned storage operation. */
public interface ScriptTask {
    public val isActive: Boolean

    public fun cancel()
}

/** Raised when an existing value was written with a different key type. */
public class StorageTypeMismatchException(
    public val path: String,
    public val expectedType: String,
    public val actualType: String
) : IllegalStateException(
    "Storage type mismatch at '$path': expected '$expectedType', found '$actualType'"
)

