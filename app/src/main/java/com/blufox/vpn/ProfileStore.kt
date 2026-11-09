package com.blufox.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SavedVpnProfile(
    val id: String,
    val name: String,
    val configJson: String,
    val savedAt: Long
) {
    val engineLabel: String get() = "موتور: sing-box"
}

/**
 * Stores imported profiles in the app-private preferences area.
 * Profiles never leave the device; Android backup is disabled in the manifest.
 */
object ProfileStore {
    private const val PREFS = "blufox_saved_profiles"
    private const val KEY_PROFILES = "profiles"

    fun load(context: Context): List<SavedVpnProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val config = item.optString("config")
                    if (id.isNotBlank() && name.isNotBlank() && config.isNotBlank()) {
                        add(SavedVpnProfile(id, name, config, item.optLong("savedAt")))
                    }
                }
            }.sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, name: String, configJson: String) {
        require(configJson.isNotBlank()) { "تنظیمات سرور خالی است." }
        val cleanName = name.trim().ifBlank { "سرور بدون نام" }.take(80)
        val id = sha256("$cleanName\n$configJson")
        val current = load(context).filterNot { it.id == id }.toMutableList()
        current.add(0, SavedVpnProfile(id, cleanName, configJson, System.currentTimeMillis()))
        persist(context, current.take(100))
    }

    fun delete(context: Context, id: String) {
        persist(context, load(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, profiles: List<SavedVpnProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("config", profile.configJson)
                    .put("savedAt", profile.savedAt)
            )
        }
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROFILES, array.toString())
                .commit()
        ) { "ذخیره سرور روی گوشی انجام نشد." }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
