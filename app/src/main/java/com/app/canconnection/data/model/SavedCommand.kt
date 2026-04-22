package com.app.canconnection.data.model

/**
 * A user-defined CAN frame saved to the Command Library.
 *
 * Persisted across app restarts by [CommandRepository] using SharedPreferences + JSON.
 * [id] is a random UUID used as a stable key for list operations and deletion.
 *
 * @property id       Unique identifier (UUID) for this command.
 * @property name     Human-readable label shown in the Command Library list.
 * @property canIdHex CAN arbitration ID as a hex string (e.g. "7DF").
 * @property dataHex  Data bytes as a hex string (e.g. "02 01 0C").
 */
data class SavedCommand(
    val id: String,
    val name: String,
    val canIdHex: String,
    val dataHex: String
)
