# App UI redesign brief — Claude Design

Paste-ready prompt for redesigning the EP-133 Sample Tool's Android app screens as one
cohesive Teenage-Engineering-grade design system. This covers all eight *implemented*
screens (the earlier `mobile-concept.html` only had three), built on the brand tokens
already in the codebase (`AndroidApp/.../ui/theme/Color.kt`).

Run it in Claude Design, iterate, export the HTML/CSS. Once it's back, the design ports
into the Compose screens (`ui/{feature}/{Feature}Screen.kt`).

---

> **Build the app UI design system for "EP-133 Sample Tool" — a free, open-source phone app
> (Android, mobile-first) that manages samples and performance on the Teenage Engineering
> EP-133 K.O. II and EP-1320 K.O. II hardware samplers, over USB. A gift to the community
> from Bombest Audio. I need eight connected screens as one cohesive visual system, plus a
> component sheet. Deliver responsive HTML/CSS (phone width ~390px first), tokens as CSS
> custom properties so it ports cleanly to Android Jetpack Compose.**
>
> **Aesthetic — Teenage Engineering industrial design on a phone.** Utilitarian, precise,
> confident, a little playful. Flat warm-gray faceplate surfaces, rubberized black pads, one
> loud accent, monospaced labels and numbers, hairline 1px rules, dense functional grids,
> hard corners / very small radii. No gradients-as-decoration, no glassmorphism, no
> drop-shadow soup, no generic SaaS look. Every element looks like it has a function.
> Numbers, codes, and IDs are first-class typographic citizens.
>
> **Tokens (use exactly):** accent/action orange `#EF4E27`; connectivity/"live" teal
> `#00A69C`; faceplate grays `#D1D2D4` base, `#E2E3E5` light, `#C6C7C9` dark, `#D8D9DB`
> buttons; pad black `#222323` (+`#2D2E2E` highlight, pad glow `rgba(239,78,39,.55)`); ink
> `#191A1B`, secondary `#555657`, tertiary `#8A8B8D`, on-dark `#E2E3E4`; border `#B0B1B3`.
> Monospace for codes/numbers/IDs, a clean grotesque for headings (ALL-CAPS labels, ~2px
> tracking). Provide a **light** theme (faceplate-gray surfaces, dark ink) and a **dark**
> theme (pad-black surfaces `#121314`/`#1E1F20`, light ink). Provide an **EP-1320 SKU** theme
> variant — swap the orange accent for rust `#B22E20` with warm-tan `#999269`. Toggle theme +
> SKU via `data-theme` / `data-sku` + a small header control.
>
> **Chrome:** a compact top header (brand pill "EP·133 / SAMPLE TOOL", a teal/grey
> connection dot + device name) and a bottom tab bar. The app has more than three sections,
> so use a tidy bottom nav that fits: Pads, Beats, Sounds, Chords, Scale, Device (Sample
> Import and Projects open from Device/Sounds, see below). Status dots: teal = connected/ok,
> orange = action/active. A subtle dotted PCB/graph-paper background is welcome.
>
> **Screens (these map to the real app — design each):**
> 1. **Pads** — a **3×4 grid of 12 rubber pads** (not 16; the KO-II has 12 pads per group)
>    with an A/B/C/D group selector. Pads show their ID (mono). States: empty, loaded,
>    pressed (orange glow), and "in scale" (teal hairline border) for scale-lock. Velocity is
>    real. A small transport/BPM readout. A connection-required empty state.
> 2. **Beats** — a **16-step sequencer**, 4 tracks, with EDIT and LIVE modes. Step cells
>    toggle; a playhead highlights the current step; beat boundaries get a subtle rule.
>    Transport (play/pause/stop), BPM +/- (mono readout), clear-track. LIVE mode shows a
>    read-only capture grid.
> 3. **Sounds** — browse 128 sounds across categories (Kick, Snare, Cymb, Perc, Bass, Melod,
>    Loop, User, SFX). Search field + category filter chips. Each row: small waveform, name,
>    number (mono), category tag, a preview (▶) and an "assign to pad" action that opens a
>    12-pad picker sheet.
> 4. **Chords** — browse chord progressions by key (12 pitch-class selector) and vibe
>    (multi-select chips). Progression cards with play/stop. A sound selector row. A "build
>    custom" entry to a **Chord Builder** sub-screen: chord blocks showing degree + tones
>    (mono MIDI notes), loop playback with step highlight, "send to Beats", and "push to
>    KO-II" (group picker → chord-map mode, with an active banner + cancel).
> 5. **Scale** — a **12-pad scale player**: scale selector, root-note (12 pitch classes),
>    octave +/-, the 3×4 pad grid showing degree label + note name, and "push to KO-II"
>    (group picker → scale-map mode, active banner + cancel).
> 6. **Device** — connection card (online/offline, device name); storage progress bar; stats
>    (sample count, storage used, firmware) as mono readouts; MIDI channel selector; scale +
>    root selectors; and action rows: **Backup**, **Restore** (with confirm), **Restore
>    factory sounds**, **Format**. Design a clean offline "NO DEVICE — plug in over USB-OTG"
>    state.
> 7. **Sample Import** — a file-pick → convert → upload flow. A list of picked WAVs each with
>    a **per-file upload progress** row (paged SysEx upload — visualize the pages/percent),
>    success/error states. This is the headline feature; make the upload feel tactile and
>    legible.
> 8. **Projects** — saved-project cards (name, date, size as mono), with a restore action
>    (render as currently-disabled/"coming soon" — it's gated pending hardware testing).
>
> **Component sheet:** pads (loaded/empty/pressed/in-scale), step cells, chips (filter +
> vibe), buttons (primary orange, ghost, disabled), status dots, mono stat readouts,
> sliders/steppers, progress bars, list rows, bottom-sheet pickers, the group A/B/C/D
> selector, and the connection card. Label everything the way hardware does.
>
> **Copy voice:** warm, direct, human — a maker talking to makers. Plug-in-and-go, no
> corporate filler, no "revolutionize/seamless/robust", no rule-of-three slogans. Short.
> Lowercase where it feels right. "a gift to the community — from Bombest Audio."
>
> **Don'ts:** no stock photos, no purple-gradient hero, no rounded-everything, no Material-
> default look. Mobile-first and thumb-reachable. Ship semantic HTML/CSS with the tokens as
> CSS custom properties.
