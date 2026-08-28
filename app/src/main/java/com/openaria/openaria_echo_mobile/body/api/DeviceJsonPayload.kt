package com.openaria.openaria_echo_mobile.body.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal object DeviceJsonPayload {
    private val json = Json

    fun parseObject(body: String): Result {
        val root = try {
            val element = json.parseToJsonElement(body)
            (element as? JsonObject)?.toMap()
                ?: return Result.Invalid("root must be a JSON object")
        } catch (exception: SerializationException) {
            return Result.Invalid("invalid JSON: ${exception.message}")
        }
        return Result.Parsed(root)
    }

    private fun JsonObject.toMap(): Map<String, Any?> = entries.associate { (key, value) -> key to value.toAnyValue() }

    private fun JsonArray.toListValue(): List<Any?> = map { it.toAnyValue() }

    private fun JsonElement.toAnyValue(): Any? {
        return when (this) {
            JsonNull -> null
            is JsonObject -> toMap()
            is JsonArray -> toListValue()
            is JsonPrimitive -> toPrimitiveValue()
        }
    }

    private fun JsonPrimitive.toPrimitiveValue(): Any? {
        if (isString) return content
        booleanOrNull?.let { return it }
        longOrNull?.let { return it }
        doubleOrNull?.let { return it }
        return content
    }

    sealed interface Result {
        data class Parsed(val value: Map<String, Any?>) : Result
        data class Invalid(val message: String) : Result
    }
}
