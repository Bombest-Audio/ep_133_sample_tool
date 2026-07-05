package com.ep133.sampletool.ui.kit

import android.content.SharedPreferences
import com.ep133.sampletool.domain.model.PadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the SAMPLES page's group state, shared by the Loop Chopper
 * ([KitViewModel]) and the Kit Builder (`KitBuilderViewModel`):
 *
 * - which group (A/B/C/D) is selected — one selection for the whole page;
 * - each group's **designation** — is it a CHOP group or a KIT group;
 * - each group's choke setting (`sound.mutegroup` at push time).
 *
 * The EP-133 itself has no notion of a group "type" — designation is an app concept that decides
 * which workflow (chop a loop vs. build a kit) targets the group. Selecting a group flips the page
 * into that group's designated mode; toggling CHOP|KIT re-designates the selected group.
 *
 * Designation and choke persist across launches via [prefs] when provided (production);
 * a null [prefs] keeps everything in memory (tests).
 */
class GroupSession(private val prefs: SharedPreferences? = null) {

    private val _selected = MutableStateFlow(PadChannel.A)
    /** The group the whole SAMPLES page is looking at. */
    val selected: StateFlow<PadChannel> = _selected.asStateFlow()

    private val _designations = MutableStateFlow(
        PadChannel.entries.associateWith { g ->
            prefs?.getString(designationKey(g), null)
                ?.let { runCatching { KitMode.valueOf(it) }.getOrNull() }
                ?: KitMode.CHOP
        },
    )
    /** Per-group designation: CHOP group or KIT group. */
    val designations: StateFlow<Map<PadChannel, KitMode>> = _designations.asStateFlow()

    private val _choke = MutableStateFlow(
        PadChannel.entries.associateWith { g -> prefs?.getBoolean(chokeKey(g), true) ?: true },
    )
    /** Per-group choke setting, applied as `sound.mutegroup` on every pad written to the group. */
    val choke: StateFlow<Map<PadChannel, Boolean>> = _choke.asStateFlow()

    fun select(group: PadChannel) { _selected.value = group }

    fun designate(group: PadChannel, mode: KitMode) {
        _designations.update { it + (group to mode) }
        prefs?.edit()?.putString(designationKey(group), mode.name)?.apply()
    }

    fun setChoke(group: PadChannel, on: Boolean) {
        _choke.update { it + (group to on) }
        prefs?.edit()?.putBoolean(chokeKey(group), on)?.apply()
    }

    fun designationFor(group: PadChannel): KitMode = _designations.value.getValue(group)
    fun chokeFor(group: PadChannel): Boolean = _choke.value.getValue(group)

    private fun designationKey(g: PadChannel) = "designation.${g.name}"
    private fun chokeKey(g: PadChannel) = "choke.${g.name}"
}
