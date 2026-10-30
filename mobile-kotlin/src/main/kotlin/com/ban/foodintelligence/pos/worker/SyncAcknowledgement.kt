package com.ban.foodintelligence.pos.worker

import com.google.gson.JsonParser

data class SyncAcknowledgement(val accepted: Set<String>, val rejected: Map<String, Pair<String, String>>)

/** A successful HTTP status alone never authorises deleting an offline sale. */
fun parseSyncAcknowledgement(body: String, sentIds: Set<String>): SyncAcknowledgement? {
    try {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val ids = obj.get("acknowledged_ids") ?: return null
        if (!ids.isJsonArray) return null
        val accepted = mutableSetOf<String>()
        for (id in ids.asJsonArray) {
            if (!id.isJsonPrimitive || !id.asJsonPrimitive.isString || id.asString !in sentIds) return null
            accepted.add(id.asString)
        }
        val rejected = mutableMapOf<String, Pair<String, String>>()
        obj.get("rejected")?.let { items ->
            if (!items.isJsonArray) return null
            for (item in items.asJsonArray) {
                if (!item.isJsonObject) return null
                val row = item.asJsonObject
                val id = row.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
                val code = row.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
                val message = row.get("message")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: code
                if (id !in sentIds || id in accepted || id in rejected) return null
                rejected[id] = code to message
            }
        }
        return SyncAcknowledgement(accepted, rejected)
    } catch (_: RuntimeException) { return null }
}
