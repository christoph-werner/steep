package helper

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec

/**
 * Utility functions to manipulate JSON objects and arrays
 * @author Michel Kraemer
 */
object JsonUtils {
  val mapper: ObjectMapper = DatabindCodec.mapper().copy()
      .registerKotlinModule()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  /**
   * Recursively flattens a hierarchy of JSON objects. Combines keys
   * of parents and their children by concatening them with a dot. For
   * example, consider the following object:
   *
   * ```
   * {
   *   "type": "Person",
   *   "person": {
   *     "firstName": "Clifford",
   *     "lastName": "Thompson",
   *     "age": 40,
   *     "address": {
   *       "street": "First Street",
   *       "number": 6550
   *     }
   *   }
   * }
   * ```
   *
   * This object will be flattened to the following one:
   *
   * ```
   * {
   *   "type": "Person",
   *   "person.firstName": "Clifford",
   *   "person.lastName": "Thompson",
   *   "person.age": 40,
   *   "person.address.street": "First Street",
   *   "person.address.number": 6550
   * }
   * ```
   * @param obj the object to flatten
   * @return the flattened object
   */
  fun flatten(obj: JsonObject): JsonObject {
    val result = JsonObject()
    for (key in obj.fieldNames()) {
      val value = obj.getValue(key)
      if (value is JsonObject) {
        val obj2 = flatten(value)
        for (key2 in obj2.fieldNames()) {
          result.put("$key.$key2", obj2.getValue(key2))
        }
      } else {
        result.put(key, value)
      }
    }
    return result
  }

  /**
   * Convert any given object to a Json object
   * @param obj the object to convert
   * @return the converted Json object
   */
  fun toJson(obj: Any): JsonObject {
    return JsonObject(mapper.convertValue<MutableMap<String, Any>>(obj))
  }

  /**
   * Convert any given object to a Json object
   * @param obj the object to convert
   * @return the converted Json object
   */
  inline fun <reified T> fromJson(obj: JsonObject): T {
    return mapper.convertValue(obj.map)
  }
}
