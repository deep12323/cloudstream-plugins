package com.externalplayers

import org.json.JSONArray
import org.json.JSONObject

data class ExternalPlayerItem(
    val id: String,
    val name: String,
    val packageName: String,
    val activityName: String? = null,
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
    val slotIndex: Int = 0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("packageName", packageName)
            put("activityName", activityName ?: "")
            put("isBuiltIn", isBuiltIn)
            put("isEnabled", isEnabled)
            put("slotIndex", slotIndex)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): ExternalPlayerItem {
            return ExternalPlayerItem(
                id = obj.optString("id"),
                name = obj.optString("name"),
                packageName = obj.optString("packageName"),
                activityName = obj.optString("activityName").takeIf { it.isNotBlank() },
                isBuiltIn = obj.optBoolean("isBuiltIn", false),
                isEnabled = obj.optBoolean("isEnabled", true),
                slotIndex = obj.optInt("slotIndex", 0)
            )
        }

        fun toJson(players: List<ExternalPlayerItem>): String {
            val array = JSONArray()
            for (p in players) {
                array.put(p.toJsonObject())
            }
            return array.toString()
        }

        fun fromJson(jsonStr: String): List<ExternalPlayerItem> {
            val list = mutableListOf<ExternalPlayerItem>()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(fromJsonObject(obj))
            }
            return list
        }
    }
}
