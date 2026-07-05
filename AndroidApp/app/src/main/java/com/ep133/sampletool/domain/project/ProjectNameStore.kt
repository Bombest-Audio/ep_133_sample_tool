package com.ep133.sampletool.domain.project

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-side custom names for EP-133 project slots. The device has no rename op (slots are fixed,
 * numbered 01..09), so names live only in the app and are applied at export/backup time. Keyed by
 * slot number (1..9) — stable across sessions, unlike the device node id.
 */
interface ProjectNameStore {
    /** slot number (1..9) → custom name. Absent = use the device slot number. */
    val names: StateFlow<Map<Int, String>>
    fun setName(slot: Int, name: String)
    fun clearName(slot: Int)
    fun nameFor(slot: Int): String? = names.value[slot]?.takeIf { it.isNotBlank() }
}

/** In-memory store — the default for tests / previews (no Android Context). */
open class InMemoryProjectNameStore : ProjectNameStore {
    private val _names = MutableStateFlow<Map<Int, String>>(emptyMap())
    override val names: StateFlow<Map<Int, String>> = _names.asStateFlow()

    override fun setName(slot: Int, name: String) {
        val trimmed = name.trim()
        _names.update { if (trimmed.isEmpty()) it - slot else it + (slot to trimmed) }
    }

    override fun clearName(slot: Int) = _names.update { it - slot }
}

/** SharedPreferences-backed store used by the app. Keys are `slot.<n>`. */
class PrefsProjectNameStore(context: Context) : ProjectNameStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("ep133_project_names", Context.MODE_PRIVATE)

    private val _names = MutableStateFlow(loadAll())
    override val names: StateFlow<Map<Int, String>> = _names.asStateFlow()

    private fun loadAll(): Map<Int, String> =
        prefs.all.mapNotNull { (k, v) ->
            val slot = k.removePrefix("slot.").toIntOrNull() ?: return@mapNotNull null
            val name = (v as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            slot to name
        }.toMap()

    override fun setName(slot: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { clearName(slot); return }
        prefs.edit().putString("slot.$slot", trimmed).apply()
        _names.update { it + (slot to trimmed) }
    }

    override fun clearName(slot: Int) {
        prefs.edit().remove("slot.$slot").apply()
        _names.update { it - slot }
    }
}
