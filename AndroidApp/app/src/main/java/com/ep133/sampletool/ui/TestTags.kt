package com.ep133.sampletool.ui

/**
 * Semantics test tags for UI tests. Naming: {screen}_{element} snake_case; indexed or named
 * elements use the function helpers. Tags are only added where text selectors are ambiguous
 * (nav label vs header title), structural (containers, dialogs), or dynamic (pads, slots,
 * import rows) — user-visible labels stay text assertions in tests.
 */
object TestTags {
    // App shell
    const val NAV_PADS = "nav_pads"
    const val NAV_KIT = "nav_kit"
    const val NAV_PROJECTS = "nav_projects"
    const val NAV_DEVICE = "nav_device"
    const val NAV_CHORDS = "nav_chords"
    const val HEADER_TITLE = "header_title"
    const val HEADER_SKU_BADGE = "header_sku_badge"
    const val HEADER_THEME_TOGGLE = "header_theme_toggle"
    const val HEADER_CONNECTION = "header_connection"

    // Pads
    const val PADS_GRID = "pads_grid"
    fun pad(index: Int) = "pad_$index"
    fun groupChip(label: String) = "group_chip_$label"

    // Pad stateDescription values (see PadsScreen cell semantics)
    const val PAD_STATE_PRESSED = "pressed"
    const val PAD_STATE_IN_SCALE = "in_scale"
    const val PAD_STATE_IDLE = "idle"

    // Device
    const val DEVICE_PERMISSION_ACTION = "device_permission_action"
    const val DEVICE_FIRMWARE_BANNER = "device_firmware_banner"
    const val DEVICE_STATS_GRID = "device_stats_grid"
    const val DEVICE_RESTORE_CONFIRM_DIALOG = "device_restore_confirm_dialog"
    const val DEVICE_SCALE_DROPDOWN = "device_scale_dropdown"
    const val DEVICE_ROOT_DROPDOWN = "device_root_dropdown"
    const val DEVICE_SIM_TOGGLE = "device_sim_toggle"

    // Projects
    const val PROJECTS_SLOT_LIST = "projects_slot_list"
    fun projectSlot(nodeId: Int) = "project_slot_$nodeId"
    fun backupCard(name: String) = "backup_card_$name"
    const val PROJECTS_RESTORE_CONFIRM_DIALOG = "projects_restore_confirm_dialog"
    const val PROJECTS_EXPORT_DIALOG = "projects_export_dialog"
    fun exportFormatOption(id: String) = "export_format_$id"

    // Offline browser (manifest-backed backups, issue #55)
    const val OFFLINE_BROWSER = "offline_browser"
    const val OFFLINE_CLOSE = "offline_close"
    const val OFFLINE_PARAM_READOUT = "offline_param_readout"
    fun offlineGroupTab(group: String) = "offline_group_tab_$group"
    fun offlinePad(group: String, pad: String) = "offline_pad_${group}_$pad"
    fun backupBrowseButton(name: String) = "backup_browse_button_$name"

    // Import
    const val IMPORT_PICK_BUTTON = "import_pick_button"
    fun importRow(name: String) = "import_row_$name"

    // Kit (SAMPLES screen: loop chopper + kit builder)
    const val KIT_MODE_CHOP = "kit_mode_chop"
    const val KIT_MODE_KIT = "kit_mode_kit"
    const val KIT_SLICE_COUNT_DEC = "kit_slice_count_dec"
    const val KIT_SLICE_COUNT_INC = "kit_slice_count_inc"
    const val KIT_SLICE_COUNT_READOUT = "kit_slice_count_readout"
    fun kitSlicePad(rank: Int) = "kit_slice_pad_$rank"
    const val KIT_PICK_PANEL = "kit_pick_panel"
    const val KIT_PUSH_BUTTON = "kit_push_button"
    const val KIT_PROGRESS_STATUS = "kit_progress_status"
    fun kitProgressPad(rank: Int) = "kit_progress_pad_$rank"
    const val GROUP_CHOKE_TOGGLE = "group_choke_toggle"

    // Chords
    const val CHORDS_SOUND_SELECTOR = "chords_sound_selector"
    const val CHORDS_OFFLINE_NOTICE = "chords_offline_notice"
    const val CHORDS_PROGRESSION_LIST = "chords_progression_list"
    fun chordsProgressionCard(id: String) = "chords_progression_card_$id"
    const val CHORDS_SOUND_PICKER_SHEET = "chords_sound_picker_sheet"
    const val CHORDS_GROUP_PICKER_SHEET = "chords_group_picker_sheet"
    const val CHORDS_BAKE_BUTTON = "chords_bake_button"
    const val CHORDS_BAKE_BANNER = "chords_bake_banner"
    const val CHORDS_GENERATOR_BARS = "chords_generator_bars"
    const val CHORDS_GENERATOR_BARS_MINUS = "chords_generator_bars_minus"
    const val CHORDS_GENERATOR_BARS_PLUS = "chords_generator_bars_plus"
    const val CHORDS_GENERATE_BUTTON = "chords_generate_button"

    // Kit Builder (embedded in the SAMPLES screen's KIT mode)
    fun kbPadCell(index: Int) = "kb_pad_cell_$index"
    const val KB_CLEAR_PAD_BUTTON = "kb_clear_pad_button"
    const val KB_CLEAR_CONFIRM_DIALOG = "kb_clear_confirm_dialog"
    const val KB_SAMPLE_LIST = "kb_sample_list"
    fun kbCategoryTab(id: String) = "kb_category_tab_$id"
    fun kbSampleRow(name: String) = "kb_sample_row_$name"
    fun kbAuditionButton(name: String) = "kb_audition_button_$name"
    const val KB_ASSIGNED_COUNT = "kb_assigned_count"
    const val KB_SWITCH_PACK_BUTTON = "kb_switch_pack_button"
    const val KB_LOAD_BANNER = "kb_load_banner"
    const val KB_INSTRUMENT_SECTION = "kb_instrument_section"
    fun kbInstrumentRow(name: String) = "kb_instrument_row_$name"

    // Kit Builder pack import (batch upload to /sounds)
    fun kbImportSelect(name: String) = "kb_import_select_$name"
    const val KB_IMPORT_ALL_BUTTON = "kb_import_all_button"
    const val KB_IMPORT_SELECTED_BUTTON = "kb_import_selected_button"
    const val KB_IMPORT_PANEL = "kb_import_panel"
    const val KB_IMPORT_CANCEL_BUTTON = "kb_import_cancel_button"
    const val KB_IMPORT_DISMISS_BUTTON = "kb_import_dismiss_button"
    const val KB_IMPORT_PROGRESS_TEXT = "kb_import_progress_text"
    fun kbImportResultRow(name: String) = "kb_import_result_row_$name"

    // Kit Builder prep (batch processing before anything hits the device)
    const val KB_PREP_NORMALIZE_TOGGLE = "kb_prep_normalize_toggle"
    const val KB_PREP_TRIM_TOGGLE = "kb_prep_trim_toggle"
    const val KB_PREP_MONO_TOGGLE = "kb_prep_mono_toggle"
    const val KB_STAGED_BUTTON = "kb_staged_button"
    const val KB_STAGED_PANEL = "kb_staged_panel"
    fun kbStagedRow(name: String) = "kb_staged_row_$name"
    fun kbStagedAudition(name: String) = "kb_staged_audition_$name"
    fun kbStagedDuplicate(name: String) = "kb_staged_duplicate_$name"
    fun kbStagedRename(name: String) = "kb_staged_rename_$name"
    fun kbStagedDelete(name: String) = "kb_staged_delete_$name"
    const val KB_STAGED_RENAME_DIALOG = "kb_staged_rename_dialog"
    const val KB_STAGED_RENAME_FIELD = "kb_staged_rename_field"
    const val KB_STAGED_RENAME_CONFIRM = "kb_staged_rename_confirm"
}
