package eternalscript.api.script.storage

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal interface StorageValueCodec<T> {
    val typeId: String

    fun encode(value: T): ByteArray

    fun decode(bytes: ByteArray): T
}

internal object StorageKeys {
    fun string(name: String): StorageKey<String?> = nullable(name, StringCodec)

    fun string(name: String, default: String): StorageKey<String> =
        key(name, StringCodec) { default }

    fun boolean(name: String, default: Boolean): StorageKey<Boolean> =
        key(name, BooleanCodec) { default }

    fun int(name: String, default: Int): StorageKey<Int> = key(name, IntCodec) { default }

    fun long(name: String, default: Long): StorageKey<Long> = key(name, LongCodec) { default }

    fun double(name: String, default: Double): StorageKey<Double> =
        key(name, DoubleCodec) { default }

    fun uuid(name: String): StorageKey<UUID?> = nullable(name, UuidCodec)

    fun uuid(name: String, default: UUID): StorageKey<UUID> = key(name, UuidCodec) { default }

    fun bytes(name: String): StorageKey<ByteArray?> = nullable(name, BytesCodec)

    fun bytes(name: String, default: ByteArray): StorageKey<ByteArray> {
        val snapshot = default.copyOf()
        return key(name, BytesCodec) { snapshot.copyOf() }
    }

    fun stringList(name: String, default: List<String>): StorageKey<List<String>> {
        val snapshot = default.toList()
        return key(name, StringListCodec) { snapshot.toList() }
    }

    fun stringSet(name: String, default: Set<String>): StorageKey<Set<String>> {
        val snapshot = default.toSet()
        return key(name, StringSetCodec) { snapshot.toSet() }
    }

    fun json(name: String): StorageKey<JsonElement?> = nullable(name, JsonElementCodec)

    fun json(name: String, default: JsonElement): StorageKey<JsonElement> =
        key(name, JsonElementCodec) { default }

    private fun <T> key(
        name: String,
        codec: StorageValueCodec<T>,
        defaultValue: () -> T
    ): StorageKey<T> = StorageKey(StorageNames.requireKey(name), codec, defaultValue)

    private fun <T : Any> nullable(
        name: String,
        codec: StorageValueCodec<T>
    ): StorageKey<T?> = StorageKey(
        StorageNames.requireKey(name),
        NullableStorageValueCodec(codec)
    ) { null }
}

internal object StorageNames {
    private val namespacePattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val keyPattern = Regex("[a-z0-9][a-z0-9._-]{0,127}")

    fun requireNamespace(value: String): String {
        require(namespacePattern.matches(value)) {
            "Storage namespace must match ${namespacePattern.pattern}: $value"
        }
        return value
    }

    fun requireKey(value: String): String {
        require(keyPattern.matches(value)) {
            "Storage key must match ${keyPattern.pattern}: $value"
        }
        return value
    }
}

private class NullableStorageValueCodec<T : Any>(
    private val delegate: StorageValueCodec<T>
) : StorageValueCodec<T?> {
    override val typeId: String = delegate.typeId

    override fun encode(value: T?): ByteArray = delegate.encode(requireNotNull(value))

    override fun decode(bytes: ByteArray): T = delegate.decode(bytes)
}

private object StringCodec : StorageValueCodec<String> {
    override val typeId: String = "string-v1"
    override fun encode(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
    override fun decode(bytes: ByteArray): String = decodeUtf8(bytes)
}

private object BooleanCodec : StorageValueCodec<Boolean> {
    override val typeId: String = "boolean-v1"
    override fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
    override fun decode(bytes: ByteArray): Boolean {
        require(bytes.size == 1 && (bytes[0] == 0.toByte() || bytes[0] == 1.toByte())) {
            "Invalid persisted Boolean value"
        }
        return bytes[0] == 1.toByte()
    }
}

private object IntCodec : StorageValueCodec<Int> {
    override val typeId: String = "int-v1"
    override fun encode(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
    override fun decode(bytes: ByteArray): Int {
        require(bytes.size == Int.SIZE_BYTES) { "Invalid persisted Int value" }
        return ByteBuffer.wrap(bytes).int
    }
}

private object LongCodec : StorageValueCodec<Long> {
    override val typeId: String = "long-v1"
    override fun encode(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    override fun decode(bytes: ByteArray): Long {
        require(bytes.size == Long.SIZE_BYTES) { "Invalid persisted Long value" }
        return ByteBuffer.wrap(bytes).long
    }
}

private object DoubleCodec : StorageValueCodec<Double> {
    override val typeId: String = "double-v1"
    override fun encode(value: Double): ByteArray =
        ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(value).array()

    override fun decode(bytes: ByteArray): Double {
        require(bytes.size == Double.SIZE_BYTES) { "Invalid persisted Double value" }
        return ByteBuffer.wrap(bytes).double
    }
}

private object UuidCodec : StorageValueCodec<UUID> {
    override val typeId: String = "uuid-v1"
    override fun encode(value: UUID): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    override fun decode(bytes: ByteArray): UUID {
        require(bytes.size == Long.SIZE_BYTES * 2) { "Invalid persisted UUID value" }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

private object BytesCodec : StorageValueCodec<ByteArray> {
    override val typeId: String = "bytes-v1"
    override fun encode(value: ByteArray): ByteArray = value.copyOf()
    override fun decode(bytes: ByteArray): ByteArray = bytes.copyOf()
}

private object StringListCodec : StorageValueCodec<List<String>> {
    override val typeId: String = "string-list-v1"
    override fun encode(value: List<String>): ByteArray = encodeJson(
        JsonArray(value.map(::JsonPrimitive))
    )

    override fun decode(bytes: ByteArray): List<String> = decodeStringArray(bytes)
}

private object StringSetCodec : StorageValueCodec<Set<String>> {
    override val typeId: String = "string-set-v1"
    override fun encode(value: Set<String>): ByteArray = encodeJson(
        JsonArray(value.sorted().map(::JsonPrimitive))
    )

    override fun decode(bytes: ByteArray): Set<String> = linkedSetOf<String>().apply {
        addAll(decodeStringArray(bytes))
    }
}

private object JsonElementCodec : StorageValueCodec<JsonElement> {
    override val typeId: String = "json-v1"
    override fun encode(value: JsonElement): ByteArray = encodeJson(canonicalJson(value))
    override fun decode(bytes: ByteArray): JsonElement = JSON.parseToJsonElement(decodeUtf8(bytes))
}

private fun decodeStringArray(bytes: ByteArray): List<String> {
    val array = JSON.parseToJsonElement(decodeUtf8(bytes)) as? JsonArray
        ?: error("Persisted string collection is not a JSON array")
    return array.map { element ->
        val primitive = element as? JsonPrimitive
            ?: error("Persisted string collection contains a non-primitive value")
        require(primitive.isString) { "Persisted string collection contains a non-string value" }
        primitive.content
    }
}

private fun encodeJson(value: JsonElement): ByteArray =
    value.toString().toByteArray(StandardCharsets.UTF_8)

private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map(::canonicalJson))
    is JsonObject -> JsonObject(
        value.entries.sortedBy { entry -> entry.key }
            .associate { (key, element) -> key to canonicalJson(element) }
    )
    else -> value
}

private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private val JSON: Json = Json {
    explicitNulls = true
    ignoreUnknownKeys = false
}
