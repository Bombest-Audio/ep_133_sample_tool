# Phase 2: Discussion Log

**Session:** 2026-03-29
**Mode:** Claude's discretion (user selected [No preference] on all gray areas)

## Gray Areas Presented

1. Backup file format — .syx vs JSON+metadata vs both
2. Backup/restore UX flow — DeviceScreen vs dedicated screen, confirmation, naming
3. PERF scope confirmation — all 4 PERF requirements in Phase 2
4. Device config write-back — whether channel/scale write to EP-133 or local state only

## Decisions Made (Claude's discretion)

All decisions were made by Claude based on codebase analysis and standard patterns.
See `02-CONTEXT.md` for full decision set (D-01..D-29).

## Key Calls

- `.syx` over JSON wrapper: portability to other EP-133 tools outweighs metadata convenience
- Auto-named files: reduces friction; user can rename in OS file picker if needed
- PERF-01..04 all in Phase 2: screens exist, gaps are well-defined additions
- Scale/root note is local state only: EP-133 has no addressable scale parameter via MIDI
- SysEx accumulation buffer is a prerequisite blocker for all device management features
