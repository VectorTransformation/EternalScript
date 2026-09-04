package eternalscript.storage

import eternalscript.api.script.storage.Storage
import eternalscript.api.script.storage.StorageKey
import eternalscript.api.script.storage.StorageNames
import eternalscript.api.script.storage.StorageScope
import eternalscript.api.script.storage.StorageTransaction
import eternalscript.api.script.storage.StorageTypeMismatchException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

internal class SQLiteStorageService(
    private val databaseFile: Path
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EternalScript-Storage").apply {
            isDaemon = true
            contextClassLoader = SQLiteStorageService::class.java.classLoader
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val opened = AtomicBoolean()
    private val accepting = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var connection: Connection? = null

    fun open() {
        check(!closed.get()) { "Storage service is closed" }
        check(opened.compareAndSet(false, true)) { "Storage service is already open" }
        try {
            executor.submit {
                Files.createDirectories(requireNotNull(databaseFile.parent))
                Class.forName("org.sqlite.JDBC")
                val openedConnection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
                try {
                    configure(openedConnection)
                    initializeOrValidateSchema(openedConnection)
                    verifyWritable(openedConnection)
                    connection = openedConnection
                } catch (error: Throwable) {
                    runCatching(openedConnection::close).exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            }.get()
            accepting.set(true)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: Throwable) {
            throw error
        }
    }

    fun storage(namespace: String): Storage = SQLiteStorage(
        this,
        StorageNames.requireNamespace(namespace)
    )

    suspend fun <T> read(scope: StorageAddress, key: StorageKey<T>): T = onDatabase { database ->
        readValue(database, scope, key)
    }

    suspend fun contains(scope: StorageAddress, key: StorageKey<*>): Boolean = onDatabase { database ->
        findStoredValue(database, scope, key.name)?.also { stored ->
            requireType(scope, key, stored.type)
        } != null
    }

    suspend fun set(scope: StorageAddress, key: StorageKey<*>, value: Any?) {
        databaseTransaction(scope) {
            setUnchecked(key, value)
        }
    }

    suspend fun remove(scope: StorageAddress, key: StorageKey<*>) {
        databaseTransaction(scope) {
            remove(key)
        }
    }

    suspend fun <R> transaction(
        scope: StorageAddress,
        block: StorageTransaction.() -> R
    ): R = databaseTransaction(scope) { block(this) }

    private suspend fun <R> databaseTransaction(
        scope: StorageAddress,
        block: SQLiteStorageTransaction.() -> R
    ): R = onDatabase { database ->
        check(database.autoCommit) { "Storage connection unexpectedly has an active transaction" }
        database.autoCommit = false
        val transaction = SQLiteStorageTransaction(database, scope)
        try {
            val result = transaction.block()
            database.commit()
            result
        } catch (error: Throwable) {
            runCatching(database::rollback).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            transaction.finish()
            database.autoCommit = true
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        accepting.set(false)
        try {
            executor.submit {
                connection?.close()
                connection = null
            }.get()
        } catch (error: Throwable) {
            val failure = (error as? ExecutionException)?.cause ?: error
            executor.shutdownNow()
            throw failure
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    private suspend fun <T> onDatabase(block: (Connection) -> T): T {
        check(accepting.get()) { "Persistent storage is unavailable because EternalScript is stopping" }
        return withContext(dispatcher) {
            check(accepting.get()) { "Persistent storage is unavailable because EternalScript is stopping" }
            block(requireNotNull(connection) { "Persistent storage is not open" })
        }
    }

    private fun configure(database: Connection) {
        database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA journal_mode=WAL").use { result ->
                check(result.next() && result.getString(1).equals("wal", ignoreCase = true)) {
                    "SQLite did not enable WAL journal mode"
                }
            }
            statement.execute("PRAGMA synchronous=FULL")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }

    private fun initializeOrValidateSchema(database: Connection) {
        val tables = database.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(result.getString(1))
            } }
        }
        when {
            tables.isEmpty() -> createSchema(database)
            tables == listOf("storage_entries", "storage_metadata") -> validateSchema(database)
            else -> error("Incomplete or unknown EternalScript storage schema: ${tables.joinToString()}")
        }
    }

    private fun createSchema(database: Connection) {
        database.autoCommit = false
        try {
            database.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE storage_metadata (
                        schema_version INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE storage_entries (
                        namespace  TEXT NOT NULL,
                        scope_type TEXT NOT NULL,
                        scope_id   TEXT NOT NULL,
                        data_key   TEXT NOT NULL,
                        value_type TEXT NOT NULL,
                        value      BLOB NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (namespace, scope_type, scope_id, data_key)
                    )
                    """.trimIndent()
                )
            }
            database.prepareStatement(
                "INSERT INTO storage_metadata(schema_version) VALUES (?)"
            ).use { statement ->
                statement.setInt(1, SCHEMA_VERSION)
                statement.executeUpdate()
            }
            database.commit()
        } catch (error: Throwable) {
            runCatching(database::rollback).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            database.autoCommit = true
        }
    }

    private fun validateSchema(database: Connection) {
        requireColumns(
            database,
            "storage_metadata",
            listOf(Column("schema_version", "INTEGER", notNull = true, primaryKey = 0))
        )
        requireColumns(
            database,
            "storage_entries",
            listOf(
                Column("namespace", "TEXT", true, 1),
                Column("scope_type", "TEXT", true, 2),
                Column("scope_id", "TEXT", true, 3),
                Column("data_key", "TEXT", true, 4),
                Column("value_type", "TEXT", true, 0),
                Column("value", "BLOB", true, 0),
                Column("updated_at", "INTEGER", true, 0)
            )
        )
        val versions = database.createStatement().use { statement ->
            statement.executeQuery("SELECT schema_version FROM storage_metadata").use { result -> buildList {
                while (result.next()) add(result.getInt(1))
            } }
        }
        check(versions == listOf(SCHEMA_VERSION)) {
            "Unsupported EternalScript storage schema version: ${versions.joinToString().ifEmpty { "missing" }}"
        }
    }

    private fun verifyWritable(database: Connection) {
        database.autoCommit = false
        try {
            database.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE storage_metadata SET schema_version = schema_version"
                )
            }
            database.rollback()
        } catch (error: Throwable) {
            runCatching(database::rollback).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            database.autoCommit = true
        }
    }

    private fun requireColumns(database: Connection, table: String, expected: List<Column>) {
        val actual = database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result -> buildList {
                while (result.next()) {
                    add(
                        Column(
                            result.getString("name"),
                            result.getString("type").uppercase(Locale.ROOT),
                            result.getInt("notnull") == 1,
                            result.getInt("pk")
                        )
                    )
                }
            } }
        }
        check(actual == expected) { "Unexpected SQLite schema for $table: $actual" }
    }

    private companion object {
        const val SCHEMA_VERSION: Int = 1
        const val CLOSE_TIMEOUT_SECONDS: Long = 30
    }
}

private class SQLiteStorage(
    private val service: SQLiteStorageService,
    override val namespace: String
) : Storage {
    override fun global(): StorageScope = SQLiteStorageScope(
        service,
        StorageAddress(namespace, "global", "global")
    )

    override fun player(uuid: UUID): StorageScope = SQLiteStorageScope(
        service,
        StorageAddress(namespace, "player", uuid.toString())
    )

    override fun world(uuid: UUID): StorageScope = SQLiteStorageScope(
        service,
        StorageAddress(namespace, "world", uuid.toString())
    )
}

private class SQLiteStorageScope(
    private val service: SQLiteStorageService,
    private val address: StorageAddress
) : StorageScope {
    override suspend fun <T> get(key: StorageKey<T>): T = service.read(address, key)

    override suspend fun <T> set(key: StorageKey<T>, value: T) {
        service.set(address, key, value)
    }

    override suspend fun contains(key: StorageKey<*>): Boolean = service.contains(address, key)

    override suspend fun remove(key: StorageKey<*>) {
        service.remove(address, key)
    }

    override suspend fun <R> update(block: StorageTransaction.() -> R): R =
        service.transaction(address, block)
}

private class SQLiteStorageTransaction(
    private val database: Connection,
    private val scope: StorageAddress
) : StorageTransaction {
    private var active = true

    override fun <T> get(key: StorageKey<T>): T {
        requireActive()
        return readValue(database, scope, key)
    }

    override fun <T> set(key: StorageKey<T>, value: T) {
        requireActive()
        setUnchecked(key, value)
    }

    override fun contains(key: StorageKey<*>): Boolean {
        requireActive()
        return findStoredValue(database, scope, key.name)?.also { stored ->
            requireType(scope, key, stored.type)
        } != null
    }

    override fun remove(key: StorageKey<*>) {
        requireActive()
        findStoredValue(database, scope, key.name)?.let { stored ->
            requireType(scope, key, stored.type)
            database.prepareStatement(
                "DELETE FROM storage_entries WHERE namespace = ? AND scope_type = ? AND scope_id = ? AND data_key = ?"
            ).use { statement ->
                statement.bind(scope, key.name)
                statement.executeUpdate()
            }
        }
    }

    fun setUnchecked(key: StorageKey<*>, value: Any?) {
        requireActive()
        if (value == null) {
            remove(key)
            return
        }
        findStoredValue(database, scope, key.name)?.let { stored ->
            requireType(scope, key, stored.type)
        }
        @Suppress("UNCHECKED_CAST")
        val encoded = (key.codec as eternalscript.api.script.storage.StorageValueCodec<Any>).encode(value)
        database.prepareStatement(
            """
            INSERT INTO storage_entries(namespace, scope_type, scope_id, data_key, value_type, value, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(namespace, scope_type, scope_id, data_key) DO UPDATE SET
                value_type = excluded.value_type,
                value = excluded.value,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.namespace)
            statement.setString(2, scope.type)
            statement.setString(3, scope.id)
            statement.setString(4, key.name)
            statement.setString(5, key.codec.typeId)
            statement.setBytes(6, encoded)
            statement.setLong(7, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun finish() {
        active = false
    }

    private fun requireActive() {
        check(active) { "Storage transaction is no longer active" }
    }
}

internal data class StorageAddress(
    val namespace: String,
    val type: String,
    val id: String
) {
    fun path(key: String): String = "$namespace/$type/$id/$key"
}

private data class StoredValue(val type: String, val bytes: ByteArray)

private data class Column(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKey: Int
)

private fun <T> readValue(database: Connection, scope: StorageAddress, key: StorageKey<T>): T {
    val stored = findStoredValue(database, scope, key.name) ?: return key.defaultValue()
    requireType(scope, key, stored.type)
    return key.codec.decode(stored.bytes)
}

private fun findStoredValue(
    database: Connection,
    scope: StorageAddress,
    key: String
): StoredValue? = database.prepareStatement(
    "SELECT value_type, value FROM storage_entries WHERE namespace = ? AND scope_type = ? AND scope_id = ? AND data_key = ?"
).use { statement ->
    statement.bind(scope, key)
    statement.executeQuery().use { result ->
        if (!result.next()) null else StoredValue(result.getString(1), result.getBytes(2))
    }
}

private fun requireType(scope: StorageAddress, key: StorageKey<*>, actual: String) {
    if (actual != key.codec.typeId) {
        throw StorageTypeMismatchException(scope.path(key.name), key.codec.typeId, actual)
    }
}

private fun java.sql.PreparedStatement.bind(scope: StorageAddress, key: String) {
    setString(1, scope.namespace)
    setString(2, scope.type)
    setString(3, scope.id)
    setString(4, key)
}
