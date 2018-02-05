package com.dozingcatsoftware.util

import org.json.JSONArray
import org.json.JSONObject

fun convertJsonValue(value: Any): Any {
    // JSONObject.NULL will be unmodified.
    return when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}

fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
    val m = mutableMapOf<String, Any>()
    for (key in json.keys()) {
        m[key] = convertJsonValue(json.get(key))
    }
    return m
}

fun jsonArrayToList(json: JSONArray): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until json.length()) {
        list.add(convertJsonValue(json.get(i)))
    }
    return list
}

fun jsonStringToMap(s: String) = jsonObjectToMap(JSONObject(s))

fun mapToJsonString(map: Map<String, Any>) = JSONObject(map).toString()
