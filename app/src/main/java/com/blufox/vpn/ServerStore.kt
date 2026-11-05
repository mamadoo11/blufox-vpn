package com.blufox.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedServer(
    val id: String,
    val name: String,
    val protocol: String,
    val country: String = "",
    val address: String = "",
    val shareLink: String = "",
    val configJson: String = "",
    val source: String = "manual"
)

object ServerStore {
    private const val PREFS = "blufox_saved_servers"
    private const val KEY = "servers"
    private const val SELECTED_KEY = "selected_server_id"

    fun loadSelectedId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_KEY, "").orEmpty()

    fun saveSelectedId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SELECTED_KEY, id).apply()
    }

    fun load(context: Context): List<SavedServer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                SavedServer(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name"),
                    protocol = item.optString("protocol"),
                    country = item.optString("country"),
                    address = item.optString("address"),
                    shareLink = item.optString("shareLink"),
                    configJson = item.optString("configJson"),
                    source = item.optString("source", "manual")
                ).takeIf { it.name.isNotBlank() && (it.shareLink.isNotBlank() || it.configJson.isNotBlank()) }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, servers: List<SavedServer>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(JSONObject()
                .put("id", server.id)
                .put("name", server.name)
                .put("protocol", server.protocol)
                .put("country", server.country)
                .put("address", server.address)
                .put("shareLink", server.shareLink)
                .put("configJson", server.configJson)
                .put("source", server.source))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }
}
