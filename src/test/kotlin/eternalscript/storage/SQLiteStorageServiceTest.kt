package eternalscript.storage

import eternalscript.api.script.Script
import eternalscript.api.script.storage.StorageTypeMismatchException
import eternalscript.api.script.storage.StorageTransaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteStorageServiceTest {
    private val keys = object : Script() {}

    @Test
    fun `persists supported values with defaults and defensive byte copies`() = withDatabase { service, _ ->
        val scope = service.storage("example.data").player(UUID.randomUUID())
        val text = keys.stringKey("text")
        val enabled = keys.booleanKey("enabled")
        val count = keys.intKey("count", 3)
        val total = keys.longKey("total")
        val ratio = keys.doubleKey("ratio")
        val uuid = keys.uuidKey("uuid")
        val bytes = keys.bytesKey("bytes")
        val list = keys.stringListKey("list")
        val set = keys.stringSetKey("set")
        val json = keys.jsonKey("json")

        runBlocking {
            assertNull(scope[text])
            assertFalse(scope[enabled])
            assertEquals(3, scope[count])
            assertEquals(0L, scope[total])
            assertEquals(0.0, scope[ratio])
            assertNull(scope[uuid])
            assertNull(scope[bytes])
            assertEquals(emptyList(), scope[list])
            assertEquals(emptySet(), scope[set])
            assertNull(scope[json])

            val expectedUuid = UUID.randomUUID()
            val sourceBytes = byteArrayOf(1, 2, 3)
            scope[text] = "hello"
            scope[enabled] = true
            scope[count] = 8
            scope[total] = 12L
            scope[ratio] = 1.25
            scope[uuid] = expectedUuid
            scope[bytes] = sourceBytes
            scope[list] = listOf("b", "a")
            scope[set] = linkedSetOf("b", "a")
            scope[json] = JsonObject(mapOf("z" to JsonPrimitive(1), "a" to JsonArray(emptyList())))
            sourceBytes[0] = 9

            assertEquals("hello", scope[text])
            assertTrue(scope[enabled])
            assertEquals(8, scope[count])
            assertEquals(12L, scope[total])
            assertEquals(1.25, scope[ratio])
            assertEquals(expectedUuid, scope[uuid])
            val firstRead = requireNotNull(scope[bytes])
            assertContentEquals(byteArrayOf(1, 2, 3), firstRead)
            firstRead[1] = 9
            assertContentEquals(byteArrayOf(1, 2, 3), requireNotNull(scope[bytes]))
            assertEquals(listOf("b", "a"), scope[list])
            assertEquals(linkedSetOf("a", "b"), scope[set])
            assertEquals(
                JsonObject(mapOf("a" to JsonArray(emptyList()), "z" to JsonPrimitive(1))),
                scope[json]
            )
        }
    }

    @Test
    fun `snapshots mutable defaults when keys are declared`() = withDatabase { service, _ ->
        val mutableBytes = byteArrayOf(1, 2)
        val mutableList = mutableListOf("one")
        val mutableSet = linkedSetOf("one")
        val bytes = keys.bytesKey("bytes", mutableBytes)
        val list = keys.stringListKey("list", mutableList)
        val set = keys.stringSetKey("set", mutableSet)
        mutableBytes[0] = 9
        mutableList += "two"
        mutableSet += "two"

        runBlocking {
            val scope = service.storage("defaults").global()
            assertContentEquals(byteArrayOf(1, 2), scope[bytes])
            assertEquals(listOf("one"), scope[list])
            assertEquals(setOf("one"), scope[set])
        }
    }

    @Test
    fun `isolates namespaces and scopes and persists after reopen`() = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        val player = UUID.randomUUID()
        val world = UUID.randomUUID()
        val key = keys.longKey("value")

        SQLiteStorageService(database).use { first ->
            first.open()
            runBlocking {
                first.storage("one").global()[key] = 1L
                first.storage("one").player(player)[key] = 2L
                first.storage("one").world(world)[key] = 3L
                first.storage("two").player(player)[key] = 4L
            }
        }

        SQLiteStorageService(database).use { second ->
            second.open()
            runBlocking {
                assertEquals(1L, second.storage("one").global()[key])
                assertEquals(2L, second.storage("one").player(player)[key])
                assertEquals(3L, second.storage("one").world(world)[key])
                assertEquals(4L, second.storage("two").player(player)[key])
            }
        }
    }

    @Test
    fun `commits and rolls back atomic updates`() = withDatabase { service, _ ->
        val scope = service.storage("economy").player(UUID.randomUUID())
        val balance = keys.longKey("balance")
        val label = keys.stringKey("label")

        runBlocking {
            scope.update {
                this[balance] = 10L
                this[label] = "ready"
            }
            assertEquals(10L, scope[balance])
            assertEquals("ready", scope[label])

            assertFailsWith<IllegalStateException> {
                scope.update {
                    this[balance] = 99L
                    error("rollback")
                }
            }
            assertEquals(10L, scope[balance])
        }
    }

    @Test
    fun `transaction receiver cannot escape its update block`() = withDatabase { service, _ ->
        val scope = service.storage("transaction").global()
        val key = keys.longKey("value")
        var escaped: StorageTransaction? = null

        runBlocking {
            scope.update {
                escaped = this
                this[key] = 1L
            }
        }
        assertFailsWith<IllegalStateException> { requireNotNull(escaped)[key] }
    }

    @Test
    fun `serializes concurrent increments without lost updates`() = withDatabase { service, _ ->
        val scope = service.storage("economy").global()
        val count = keys.longKey("count")

        runBlocking {
            coroutineScope {
                List(100) {
                    async {
                        scope.update { this[count] = this[count] + 1L }
                    }
                }.awaitAll()
            }
            assertEquals(100L, scope[count])
        }
    }

    @Test
    fun `rejects type changes without overwriting the original value`() = withDatabase { service, _ ->
        val scope = service.storage("types").global()
        val number = keys.longKey("same")
        val text = keys.stringKey("same")

        runBlocking {
            scope[number] = 7L
            val failure = assertFailsWith<StorageTypeMismatchException> { scope[text] }
            assertEquals("types/global/global/same", failure.path)
            assertFailsWith<StorageTypeMismatchException> { scope[text] = "wrong" }
            assertEquals(7L, scope[number])
        }
    }

    @Test
    fun `remove commits and nullable assignment removes`() = withDatabase { service, _ ->
        val scope = service.storage("remove").global()
        val text = keys.stringKey("text")

        runBlocking {
            scope[text] = "value"
            assertTrue(scope.contains(text))
            scope.remove(text)
            assertFalse(scope.contains(text))
            scope[text] = "again"
            scope[text] = null
            assertNull(scope[text])
        }
    }

    @Test
    fun `rejects invalid namespace and key names`() {
        withDatabase { service, _ ->
            assertFailsWith<IllegalArgumentException> { service.storage("Invalid") }
            assertFailsWith<IllegalArgumentException> { service.storage("a".repeat(65)) }
        }
        assertFailsWith<IllegalArgumentException> { keys.longKey("Upper") }
        assertFailsWith<IllegalArgumentException> { keys.longKey("a".repeat(129)) }
    }

    @Test
    fun `preserves corrupt database bytes when opening fails`() = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        val original = "not-a-sqlite-database".toByteArray()
        database.writeBytes(original)
        val service = SQLiteStorageService(database)
        try {
            assertFailsWith<Throwable> { service.open() }
        } finally {
            runCatching(service::close)
        }
        assertContentEquals(original, database.readBytes())
    }

    @Test
    fun `rejects unknown schema version without replacing it`() = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        SQLiteStorageService(database).use { service -> service.open() }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE storage_metadata SET schema_version = 999")
            }
        }

        val rejected = SQLiteStorageService(database)
        try {
            assertFailsWith<IllegalStateException> { rejected.open() }
        } finally {
            runCatching(rejected::close)
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT schema_version FROM storage_metadata").use { result ->
                    assertTrue(result.next())
                    assertEquals(999, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rejects an unknown existing database schema`() = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE unrelated(value TEXT NOT NULL)")
                statement.execute("INSERT INTO unrelated(value) VALUES ('preserve')")
            }
        }
        val service = SQLiteStorageService(database)
        try {
            assertFailsWith<IllegalStateException> { service.open() }
        } finally {
            runCatching(service::close)
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM unrelated").use { result ->
                    assertTrue(result.next())
                    assertEquals("preserve", result.getString(1))
                }
            }
        }
    }

    @Test
    fun `fails on an unavailable data directory without replacing the obstacle`() = withDirectory { directory ->
        val obstacle = directory.resolve("data")
        val sentinel = "keep-me".toByteArray()
        obstacle.writeBytes(sentinel)
        val service = SQLiteStorageService(obstacle.resolve("storage.db"))
        try {
            assertFailsWith<Throwable> { service.open() }
        } finally {
            runCatching(service::close)
        }
        assertContentEquals(sentinel, obstacle.readBytes())
    }

    @Test
    fun `rejects a read only database during activation`() = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        SQLiteStorageService(database).use { service -> service.open() }
        val dos = Files.getFileAttributeView(database, DosFileAttributeView::class.java)
            ?: return@withDirectory
        dos.setReadOnly(true)
        try {
            val service = SQLiteStorageService(database)
            try {
                assertFailsWith<Throwable> { service.open() }
            } finally {
                runCatching(service::close)
            }
        } finally {
            dos.setReadOnly(false)
        }
    }

    private fun withDatabase(block: (SQLiteStorageService, Path) -> Unit) = withDirectory { directory ->
        val database = directory.resolve("storage.db")
        SQLiteStorageService(database).use { service ->
            service.open()
            block(service, database)
        }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val base = Path.of("codex", "temp", "storage-tests")
        Files.createDirectories(base)
        val directory = Files.createTempDirectory(base, "case-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
