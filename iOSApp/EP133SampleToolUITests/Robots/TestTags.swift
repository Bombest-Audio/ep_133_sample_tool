import Foundation

/// UI-test-target mirror of the app's `UI/TestTags.swift`.
///
/// XCUITest runs out-of-process, so the runner can't see the app target's internal `TestTags`
/// enum. This is a byte-for-byte copy of the identifier strings — the accessibilityIdentifier
/// values the app sets — so the robots address elements by the same tags the Android robots use.
/// Keep the two files in sync: any tag added to `EP133SampleTool/UI/TestTags.swift` that a UI test
/// needs must be added here too.
enum TestTags {
    // App shell
    static let NAV_PADS = "nav_pads"
    static let NAV_KIT = "nav_kit"
    static let NAV_PROJECTS = "nav_projects"
    static let NAV_DEVICE = "nav_device"
    static let HEADER_TITLE = "header_title"
    static let HEADER_SKU_BADGE = "header_sku_badge"
    static let HEADER_THEME_TOGGLE = "header_theme_toggle"
    static let HEADER_CONNECTION = "header_connection"

    // Pads
    static let PADS_GRID = "pads_grid"
    static func pad(_ index: Int) -> String { "pad_\(index)" }
    static func groupChip(_ label: String) -> String { "group_chip_\(label)" }

    // Pad stateDescription values (see PadsScreen cell semantics)
    static let PAD_STATE_PRESSED = "pressed"
    static let PAD_STATE_IN_SCALE = "in_scale"
    static let PAD_STATE_IDLE = "idle"

    // Device
    static let DEVICE_PERMISSION_ACTION = "device_permission_action"
    static let DEVICE_FIRMWARE_BANNER = "device_firmware_banner"
    static let DEVICE_STATS_GRID = "device_stats_grid"
    static let DEVICE_RESTORE_CONFIRM_DIALOG = "device_restore_confirm_dialog"
    static let DEVICE_SCALE_DROPDOWN = "device_scale_dropdown"
    static let DEVICE_ROOT_DROPDOWN = "device_root_dropdown"
    static let DEVICE_SIM_TOGGLE = "device_sim_toggle"

    // Projects
    static let PROJECTS_SLOT_LIST = "projects_slot_list"
    static func projectSlot(_ nodeId: Int) -> String { "project_slot_\(nodeId)" }
    static func backupCard(_ name: String) -> String { "backup_card_\(name)" }
    static let PROJECTS_RESTORE_CONFIRM_DIALOG = "projects_restore_confirm_dialog"

    // Import
    static let IMPORT_PICK_BUTTON = "import_pick_button"
    static func importRow(_ name: String) -> String { "import_row_\(name)" }

    // Kit (SAMPLES screen)
    static let KIT_MODE_CHOP = "kit_mode_chop"
    static let KIT_MODE_KIT = "kit_mode_kit"
    static let KIT_SLICE_COUNT_DEC = "kit_slice_count_dec"
    static let KIT_SLICE_COUNT_INC = "kit_slice_count_inc"
    static let KIT_SLICE_COUNT_READOUT = "kit_slice_count_readout"
    static func kitSlicePad(_ rank: Int) -> String { "kit_slice_pad_\(rank)" }
    static let KIT_PICK_PANEL = "kit_pick_panel"
    static let KIT_PUSH_BUTTON = "kit_push_button"
    static let KIT_PROGRESS_STATUS = "kit_progress_status"
    static func kitProgressPad(_ rank: Int) -> String { "kit_progress_pad_\(rank)" }
    static let GROUP_CHOKE_TOGGLE = "group_choke_toggle"

    // Kit Builder
    static func kbPadCell(_ index: Int) -> String { "kb_pad_cell_\(index)" }
    static let KB_CLEAR_PAD_BUTTON = "kb_clear_pad_button"
    static let KB_CLEAR_CONFIRM_DIALOG = "kb_clear_confirm_dialog"
    static let KB_SAMPLE_LIST = "kb_sample_list"
    static func kbCategoryTab(_ id: String) -> String { "kb_category_tab_\(id)" }
    static func kbSampleRow(_ name: String) -> String { "kb_sample_row_\(name)" }
    static func kbAuditionButton(_ name: String) -> String { "kb_audition_button_\(name)" }
    static let KB_ASSIGNED_COUNT = "kb_assigned_count"
    static let KB_SWITCH_PACK_BUTTON = "kb_switch_pack_button"
    static let KB_LOAD_BANNER = "kb_load_banner"
}
