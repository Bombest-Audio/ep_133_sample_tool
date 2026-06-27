# website/

The public project site — landing page, the SysEx protocol reference, and the app
UI redesign concept. Static HTML/CSS/JS, no build step, no dependencies (just Google
Fonts over the network). Designed in the Teenage Engineering industrial idiom; MIT,
same as the rest of our code.

```
website/
├── index.html      # Overview / landing
├── protocol.html   # SysEx protocol reference (the pretty version of docs/ep133-sysex-protocol.md)
├── app.html        # App UI redesign concept — 7 screens + component sheet
├── style.css       # One stylesheet, three surfaces (design tokens at the top)
└── app.js          # Theme + EP-133/EP-1320 toggles, TOC scrollspy. No deps.
```

## Preview locally

```bash
cd website
python3 -m http.server   # → http://localhost:8000
```

## Deploy

`.github/workflows/pages.yml` publishes this folder to GitHub Pages on every push to
`main` that touches `website/`. To turn it on: repo **Settings → Pages → Build and
deployment → Source: GitHub Actions**. Live URL once enabled:
`https://bombest-audio.github.io/ep_133_sample_tool/`.
