package com.app.canconnection.data.repository

import android.content.Context
import com.app.canconnection.data.model.SavedCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists and retrieves the list of user-saved CAN commands.
 *
 * Uses [SharedPreferences] with a JSON array so that commands survive app restarts.
 * Storage key is "commands" inside the "saved_commands" preference file.
 * Any JSON parse error during [load] returns an empty list rather than crashing,
 * so corrupted storage is silently recovered from.
 */
class CommandRepository(context: Context) {

    private val prefs = context.getSharedPreferences("saved_commands", Context.MODE_PRIVATE)

    /** Deserialises all saved commands from SharedPreferences. Returns empty list on error. */
    fun load(): MutableList<SavedCommand> {
        val json = prefs.getString("commands", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { index ->
                val o = arr.getJSONObject(index)
                SavedCommand(
                    id       = o.getString("id"),
                    name     = o.getString("name"),
                    canIdHex = o.getString("canIdHex"),
                    dataHex  = o.getString("dataHex")
                )
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /** Serialises [commands] to JSON and writes it to SharedPreferences asynchronously. */
    fun save(commands: List<SavedCommand>) {
        val arr = JSONArray()
        commands.forEach { cmd ->
            arr.put(JSONObject().apply {
                put("id",       cmd.id)
                put("name",     cmd.name)
                put("canIdHex", cmd.canIdHex)
                put("dataHex",  cmd.dataHex)
            })
        }
        prefs.edit().putString("commands", arr.toString()).apply()
    }
}
