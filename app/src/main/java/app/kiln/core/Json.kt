package app.kiln.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val KJ = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; isLenient = true }
val KJPretty = Json(KJ) { prettyPrint = true }

/** Tiny builders so request/transcript JSON reads like the wire format. */
fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to v.toJson() })

fun arr(vararg items: Any?): JsonArray = JsonArray(items.filterNotNull().map { it.toJson() })
fun arrOf(items: List<Any?>): JsonArray = JsonArray(items.filterNotNull().map { it.toJson() })

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(this.entries.filter { it.value != null }.associate { (k, v) -> k.toString() to v.toJson() })
    is List<*> -> JsonArray(this.filterNotNull().map { it.toJson() })
    is Array<*> -> JsonArray(this.filterNotNull().map { it.toJson() })
    else -> JsonPrimitive(this.toString())
}

operator fun JsonObject.plus(other: Map<String, JsonElement>): JsonObject = JsonObject(this.toMap() + other)

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray

fun parseJson(s: String): JsonElement = KJ.parseToJsonElement(s)
fun JsonElement.compact(): String = KJ.encodeToString(JsonElement.serializer(), this)
fun JsonElement.pretty(): String = KJPretty.encodeToString(JsonElement.serializer(), this)
