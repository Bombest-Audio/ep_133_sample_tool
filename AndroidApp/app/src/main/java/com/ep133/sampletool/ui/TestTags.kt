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

    // Projects
    const val PROJECTS_SLOT_LIST = "projects_slot_list"
    fun projectSlot(nodeId: Int) = "project_slot_$nodeId"
    fun backupCard(name: String) = "backup_card_$name"
    const val PROJECTS_RESTORE_CONFIRM_DIALOG = "projects_restore_confirm_dialog"

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
}
