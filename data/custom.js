var VersionNumber = "v 1.1.0 - offline";
var CustomColors = false;
var HideSerial = true;
var CustomNames = false;
// if BackupSamples is set to true, it will save the sounds. disabling this creates quick project backups.
// this is now set in the GUI. no need to manually set.
var BackupSamples = true;

// set EnableHMLS to true if you want a new menu option to restore custom sounds
var EnableHMLS = true;
var OptionsDistance = 72
if (EnableHMLS == true) {OptionsDistance = 55};

var TempSku;

// COLORS
var ColorOriginalOrange = "#EF4E34";
var ColorSound = "#FF00E0";
var ColorSpeaker = "#b3a1b7";
var ColorBackupRestoreBtn = ColorSound;
var ColorBatteryCover = "#d6dae4";
var ColorEPFace = "#9496a5";
var ColorStartKnob = "#6b6f90";
var ColorEndKnob = "#3f2e41";
var ColorButtons = "#ef4e27";
var ColorButtons2 = "#000000";
var ColorGrid = "#c7be8b";

var ColorMain = "#b18fb1";
var ColorSecondary = "#a282a8";
var ColorTertiary = "#96abde";
var ColorSlate = "#dbdddb";
var ColorCharcoal = "#232424";
var ColorLightCharcoal = "#b0babe";
var ColorLightGray = "#dcdcdc";
var ColorBlack = "#000000"
var Color0 = "#8c959f";
var ColorMarker0 = Color0;
var Color1 = "#82c9ec";
var ColorMarker1 = Color1;
var Color2 = "#82ec88";
var ColorMarker2 = Color2;
var Color3 = "#faff4a";
var ColorMarker3 = Color3;
var Color4 = "#47f3e3";
var ColorMarker4 = Color4;
var Color5 = "#f45050";
var ColorMarker5 = Color5;
var Color6 = "#a475f9";
var ColorMarker6 = Color6;
var Color7 = "#ee86e6";
var ColorMarker7 = Color7;
var Color8 = "#ffaa00";
var ColorMarker8 = Color8;
var Color9 = "#b88552";
var ColorMarker9 = Color9;

var FontColorLight = "#e5e6e6";
var FontColorDark = "#000000";
var FontColorLogo1 = "#FF0000";
var FontColorLogo2 = ColorSound;

// NAMES
var Bank0 = "Bank 0";
var Bank1 = "Bank 1";
var Bank2 = "Bank 2";
var Bank3 = "Bank 3";
var Bank4 = "Bank 4";
var Bank5 = "Bank 5";
var Bank6 = "Bank 6";
var Bank7 = "Bank 7";
var Bank8 = "Bank 8";
var Bank9 = "Bank 9";
var StartIn = "ST";
var EndOut = "END";
var LibraryTitle = "EP-133 Library";
var FakeSerial = "EP-133"

// SET ORIGINAL NAMES BACK IF USER DOESN'T WANT CUSTOM NAMES
if (CustomNames == false) {
  Bank0 = "KICK";
  Bank1 = "SNARE";
  Bank2 = "CYMB";
  Bank3 = "PERC";
  Bank4 = "BASS";
  Bank5 = "MELOD";
  Bank6 = "LOOP";
  Bank7 = "USER 1";
  Bank8 = "USER 2";
  Bank9 = "SFX";
  StartIn = "IN";
  EndOut = "OUT";
  LibraryTitle = "SAMPLE LIBRARY";
  FakeSerial = null
  if (HideSerial == true) {FakeSerial = "EP-133"};
};
const changeEndianness = (string) => {
  const result = [];
  let len = string.length - 2;
  while (len >= 0) {
    result.push(string.substr(len, 2));
    len -= 2;
  }
  return result.join('');
}
if (CustomColors == false) {
  ColorMain = ColorOriginalOrange;
  ColorSound = "#00a69c";
  ColorSpeaker = ColorLightCharcoal;
  ColorBatteryCover = ColorSlate;
  ColorEPFace = ColorLightCharcoal;
  ColorStartKnob = ColorOriginalOrange;
  ColorEndKnob = ColorCharcoal;
  ColorBackupRestoreBtn = ColorSound;
  ColorButtons = "#ef4e27";
  ColorSecondary = ColorSpeaker;
  ColorTertiary = "#dbdddb";
  Color0 = ColorLightGray;
  Color1 = ColorLightGray;
  Color2 = ColorLightGray;
  Color3 = ColorLightGray;
  Color4 = ColorLightGray;
  Color5 = ColorLightGray;
  Color6 = ColorLightGray;
  Color7 = ColorLightCharcoal;
  Color8 = ColorLightCharcoal;
  Color9 = "#000000";
  ColorMarker0 = ColorOriginalOrange;
  ColorMarker1 = ColorOriginalOrange;
  ColorMarker2 = ColorOriginalOrange;
  ColorMarker3 = ColorOriginalOrange;
  ColorMarker4 = ColorOriginalOrange;
  ColorMarker5 = ColorOriginalOrange;
  ColorMarker6 = ColorOriginalOrange;
  ColorMarker7 = ColorOriginalOrange;
  ColorMarker8 = ColorOriginalOrange;
  ColorMarker9 = ColorOriginalOrange;
  ColorGrid = "#4f4059";
  FontColorLight = "#e5e6e6";
  FontColorDark = "#000000";
  FontColorLogo1 = "#000000";
  FontColorLogo2 = ColorButtons;
};

// ---------------------------------------------------------------------------
// SPLICE FOLDER SYNC PANEL (Electron desktop only)
//
// window.spliceSync is exposed by the Electron preload script. On other
// platforms (Android/iOS/JUCE/browser) it does not exist and this whole
// block is inert.
//
// Import handoff: each detected sample is listed as a draggable chip. On
// dragstart we attach the prefetched File to the drag's dataTransfer, so
// dropping a chip on a pad or sample slot goes through the app's own
// onDrop handlers (which read event.dataTransfer.files) - the exact same
// code path as dragging a file in from Finder/Explorer.
// ---------------------------------------------------------------------------
if (typeof window !== "undefined" && window.spliceSync) {
  (function () {
    var MAX_ITEMS = 25;
    var MAX_PREFETCH_BYTES = 32 * 1024 * 1024;
    var items = []; // { path, name, size, file (File|null), failed (bool) }
    var panel, listEl, toggleEl, folderEl, bodyEl, collapsed = true;

    function prefetch(item) {
      if (item.file || item.failed || item.size > MAX_PREFETCH_BYTES) return;
      window.spliceSync.readFile(item.path).then(function (result) {
        var mime = "audio/wav";
        var lower = item.name.toLowerCase();
        if (/\.mp3$/.test(lower)) { mime = "audio/mpeg"; }
        else if (/\.flac$/.test(lower)) { mime = "audio/flac"; }
        else if (/\.ogg$/.test(lower)) { mime = "audio/ogg"; }
        else if (/\.aiff?$/.test(lower)) { mime = "audio/aiff"; }
        item.file = new File([result.bytes], result.name, { type: mime });
        renderList();
      }, function () {
        item.failed = true;
        renderList();
      });
    }

    function styleEl(el, styles) {
      var key;
      for (key in styles) { if (styles.hasOwnProperty(key)) { el.style[key] = styles[key]; } }
      return el;
    }

    function renderList() {
      if (!listEl) return;
      listEl.innerHTML = "";
      if (items.length === 0) {
        var empty = document.createElement("div");
        empty.textContent = "No new samples yet. Download something on Splice.";
        styleEl(empty, { padding: "6px 8px", opacity: "0.6", fontSize: "11px" });
        listEl.appendChild(empty);
        return;
      }
      var i;
      for (i = 0; i < items.length; i++) {
        (function (item) {
          var row = document.createElement("div");
          row.textContent = (item.file ? "≡ " : item.failed ? "✕ " : "… ") + item.name;
          row.title = item.failed
            ? "Could not read this file"
            : item.file
              ? "Drag onto a pad or sample slot to import"
              : "Loading file...";
          styleEl(row, {
            padding: "5px 8px", margin: "2px 0", fontSize: "11px",
            background: item.file ? "#2c2c2c" : "#1d1d1d",
            color: item.failed ? "#888888" : "#e5e6e6",
            borderRadius: "3px", cursor: item.file ? "grab" : "default",
            whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis"
          });
          row.draggable = !!item.file;
          row.addEventListener("mouseenter", function () { prefetch(item); });
          row.addEventListener("dragstart", function (ev) {
            if (!item.file || !ev.dataTransfer) { ev.preventDefault(); return; }
            ev.dataTransfer.items.add(item.file);
            ev.dataTransfer.effectAllowed = "copy";
          });
          listEl.appendChild(row);
        })(items[i]);
      }
    }

    function refreshSettings() {
      window.spliceSync.getSettings().then(function (settings) {
        toggleEl.checked = settings.enabled;
        folderEl.textContent = settings.folder;
        folderEl.title = settings.folder + (settings.enabled && !settings.watching
          ? " (folder not found - click to choose another)" : "");
        folderEl.style.color = settings.enabled && !settings.watching ? "#f45050" : "#9a9a9a";
      });
    }

    function buildPanel() {
      panel = document.createElement("div");
      styleEl(panel, {
        position: "fixed", right: "10px", bottom: "10px", width: "240px",
        background: "#151515", color: "#e5e6e6", zIndex: "99999",
        border: "1px solid #333333", borderRadius: "6px",
        fontFamily: "monospace", boxShadow: "0 2px 10px rgba(0,0,0,0.5)"
      });

      var header = document.createElement("div");
      header.textContent = "SPLICE SYNC";
      styleEl(header, {
        padding: "6px 8px", fontSize: "11px", letterSpacing: "1px",
        cursor: "pointer", userSelect: "none", background: "#202020",
        borderRadius: "6px 6px 0 0"
      });
      header.addEventListener("click", function () {
        collapsed = !collapsed;
        bodyEl.style.display = collapsed ? "none" : "block";
      });
      panel.appendChild(header);

      bodyEl = document.createElement("div");
      styleEl(bodyEl, { display: "none", padding: "6px 8px 8px 8px" });
      panel.appendChild(bodyEl);

      var controls = document.createElement("div");
      styleEl(controls, { display: "flex", alignItems: "center", marginBottom: "4px" });

      toggleEl = document.createElement("input");
      toggleEl.type = "checkbox";
      toggleEl.title = "Watch the Splice folder for new samples";
      toggleEl.addEventListener("change", function () {
        window.spliceSync.setSettings({ enabled: toggleEl.checked, folder: "" })
          .then(refreshSettings);
      });
      controls.appendChild(toggleEl);

      var label = document.createElement("span");
      label.textContent = "watch folder";
      styleEl(label, { fontSize: "11px", marginLeft: "4px" });
      controls.appendChild(label);
      bodyEl.appendChild(controls);

      folderEl = document.createElement("div");
      folderEl.textContent = "";
      styleEl(folderEl, {
        fontSize: "10px", color: "#9a9a9a", cursor: "pointer",
        whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
        marginBottom: "6px"
      });
      folderEl.addEventListener("click", function () {
        window.spliceSync.chooseFolder().then(function (folder) {
          if (!folder) return;
          window.spliceSync.setSettings({ enabled: toggleEl.checked, folder: folder })
            .then(refreshSettings);
        });
      });
      bodyEl.appendChild(folderEl);

      listEl = document.createElement("div");
      styleEl(listEl, { maxHeight: "180px", overflowY: "auto" });
      bodyEl.appendChild(listEl);

      document.body.appendChild(panel);
      renderList();
      refreshSettings();
    }

    window.spliceSync.onNewSamples(function (samples) {
      var i;
      for (i = 0; i < samples.length; i++) {
        items.unshift({
          path: samples[i].path, name: samples[i].name,
          size: samples[i].size, file: null, failed: false
        });
      }
      if (items.length > MAX_ITEMS) { items.length = MAX_ITEMS; }
      // Prefetch only the most recent sample; the rest load lazily on
      // mouseenter so a big download batch cannot balloon memory.
      if (items.length > 0) { prefetch(items[0]); }
      if (collapsed && bodyEl) { collapsed = false; bodyEl.style.display = "block"; }
      renderList();
    });

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", buildPanel);
    } else {
      buildPanel();
    }
  })();
};

// ---------------------------------------------------------------------------
// CLAP FX PANEL (Electron desktop only)
//
// window.clapTools is exposed by the Electron preload script and backed by
// the standalone tools/clap-render CLI (see docs/adr-001-clap-hosting.md).
// On other platforms it does not exist and this whole block is inert. The
// panel also stays hidden when the CLI binary has not been built.
//
// Import handoff: the rendered WAV becomes a draggable chip. On dragstart
// the File is attached to the drag's dataTransfer, exactly like the Splice
// panel above, so dropping on a pad or sample slot goes through the app's
// own onDrop handlers.
// ---------------------------------------------------------------------------
if (typeof window !== "undefined" && window.clapTools) {
  (function () {
    var MAX_PARAM_ROWS = 48;
    var plugin = null;   // { path, name }
    var wavFile = null;  // { path, name }
    var params = [];     // { id, name, min, max, defaultValue, value }
    var rendered = null; // { name, file (File) }
    var panel, bodyEl, statusEl, pluginBtn, wavBtn, paramsEl, renderBtn, resultEl;
    var collapsed = true;
    var rendering = false;

    function styleEl(el, styles) {
      var key;
      for (key in styles) { if (styles.hasOwnProperty(key)) { el.style[key] = styles[key]; } }
      return el;
    }

    function setStatus(text, isError) {
      statusEl.textContent = text || "";
      statusEl.style.color = isError ? "#f45050" : "#9a9a9a";
    }

    function button(label) {
      var b = document.createElement("button");
      b.textContent = label;
      styleEl(b, {
        display: "block", width: "100%", margin: "3px 0", padding: "4px 6px",
        fontSize: "11px", fontFamily: "monospace", background: "#2c2c2c",
        color: "#e5e6e6", border: "1px solid #444444", borderRadius: "3px",
        cursor: "pointer", textAlign: "left", whiteSpace: "nowrap",
        overflow: "hidden", textOverflow: "ellipsis"
      });
      return b;
    }

    function renderParams() {
      paramsEl.innerHTML = "";
      var count = Math.min(params.length, MAX_PARAM_ROWS);
      var i;
      for (i = 0; i < count; i++) {
        (function (p) {
          var row = document.createElement("div");
          styleEl(row, { display: "flex", alignItems: "center", margin: "2px 0" });
          var name = document.createElement("span");
          name.textContent = p.name;
          name.title = p.name + " [" + p.min + " .. " + p.max + "]";
          styleEl(name, {
            flex: "1", fontSize: "10px", whiteSpace: "nowrap",
            overflow: "hidden", textOverflow: "ellipsis", marginRight: "4px"
          });
          row.appendChild(name);
          var input = document.createElement("input");
          input.type = "number";
          input.min = String(p.min);
          input.max = String(p.max);
          input.step = "any";
          input.value = String(p.value);
          styleEl(input, {
            width: "64px", fontSize: "10px", fontFamily: "monospace",
            background: "#1d1d1d", color: "#e5e6e6",
            border: "1px solid #444444", borderRadius: "3px"
          });
          input.addEventListener("change", function () {
            var v = parseFloat(input.value);
            if (isNaN(v)) { input.value = String(p.value); return; }
            if (v < p.min) { v = p.min; }
            if (v > p.max) { v = p.max; }
            p.value = v;
            input.value = String(v);
          });
          row.appendChild(input);
          paramsEl.appendChild(row);
        })(params[i]);
      }
      if (params.length > count) {
        var more = document.createElement("div");
        more.textContent = "(" + (params.length - count) + " more parameters not shown)";
        styleEl(more, { fontSize: "10px", opacity: "0.6", margin: "2px 0" });
        paramsEl.appendChild(more);
      }
    }

    function renderResult() {
      resultEl.innerHTML = "";
      if (!rendered) { return; }
      var chip = document.createElement("div");
      chip.textContent = "≡ " + rendered.name;
      chip.title = "Drag onto a pad or sample slot to import";
      styleEl(chip, {
        padding: "5px 8px", margin: "4px 0 2px 0", fontSize: "11px",
        background: "#2c2c2c", color: "#e5e6e6", borderRadius: "3px",
        cursor: "grab", whiteSpace: "nowrap", overflow: "hidden",
        textOverflow: "ellipsis"
      });
      chip.draggable = true;
      chip.addEventListener("dragstart", function (ev) {
        if (!ev.dataTransfer) { ev.preventDefault(); return; }
        ev.dataTransfer.items.add(rendered.file);
        ev.dataTransfer.effectAllowed = "copy";
      });
      resultEl.appendChild(chip);

      var preview = document.createElement("button");
      preview.textContent = "▶ preview";
      styleEl(preview, {
        margin: "0 0 2px 0", padding: "2px 6px", fontSize: "10px",
        fontFamily: "monospace", background: "#1d1d1d", color: "#e5e6e6",
        border: "1px solid #444444", borderRadius: "3px", cursor: "pointer"
      });
      preview.addEventListener("click", function () {
        var url = URL.createObjectURL(rendered.file);
        var audio = new Audio(url);
        audio.addEventListener("ended", function () { URL.revokeObjectURL(url); });
        audio.play();
      });
      resultEl.appendChild(preview);
    }

    function choosePlugin() {
      window.clapTools.choosePlugin().then(function (result) {
        if (!result) { return; }
        plugin = result;
        params = [];
        rendered = null;
        pluginBtn.textContent = "plugin: " + plugin.name;
        setStatus("loading parameters...");
        renderParams();
        renderResult();
        window.clapTools.listParams(plugin.path).then(function (list) {
          var i;
          params = [];
          for (i = 0; i < list.length; i++) {
            params.push({
              id: list[i].id, name: list[i].name, min: list[i].min,
              max: list[i].max, defaultValue: list[i].defaultValue,
              value: list[i].defaultValue
            });
          }
          setStatus(params.length + " parameters");
          renderParams();
        }, function (err) {
          setStatus(String(err && err.message ? err.message : err), true);
        });
      });
    }

    function chooseWav() {
      window.clapTools.chooseWav().then(function (result) {
        if (!result) { return; }
        wavFile = result;
        rendered = null;
        wavBtn.textContent = "wav: " + wavFile.name;
        renderResult();
      });
    }

    function doRender() {
      if (rendering) { return; }
      if (!plugin) { setStatus("choose a plugin first", true); return; }
      if (!wavFile) { setStatus("choose a WAV first", true); return; }
      rendering = true;
      renderBtn.textContent = "rendering...";
      setStatus("");
      var changed = [];
      var i;
      for (i = 0; i < params.length; i++) {
        if (params[i].value !== params[i].defaultValue) {
          changed.push({ id: params[i].id, value: params[i].value });
        }
      }
      window.clapTools.render({
        pluginPath: plugin.path, wavPath: wavFile.path, params: changed
      }).then(function (result) {
        rendered = {
          name: result.name,
          file: new File([result.bytes], result.name, { type: "audio/wav" })
        };
        setStatus("rendered - drag the chip to import");
        renderResult();
      }, function (err) {
        setStatus(String(err && err.message ? err.message : err), true);
      }).then(function () {
        rendering = false;
        renderBtn.textContent = "render";
      });
    }

    function buildPanel() {
      panel = document.createElement("div");
      styleEl(panel, {
        position: "fixed", right: "260px", bottom: "10px", width: "240px",
        background: "#151515", color: "#e5e6e6", zIndex: "99999",
        border: "1px solid #333333", borderRadius: "6px",
        fontFamily: "monospace", boxShadow: "0 2px 10px rgba(0,0,0,0.5)"
      });

      var header = document.createElement("div");
      header.textContent = "CLAP FX";
      styleEl(header, {
        padding: "6px 8px", fontSize: "11px", letterSpacing: "1px",
        cursor: "pointer", userSelect: "none", background: "#202020",
        borderRadius: "6px 6px 0 0"
      });
      header.addEventListener("click", function () {
        collapsed = !collapsed;
        bodyEl.style.display = collapsed ? "none" : "block";
      });
      panel.appendChild(header);

      bodyEl = document.createElement("div");
      styleEl(bodyEl, { display: "none", padding: "6px 8px 8px 8px" });
      panel.appendChild(bodyEl);

      pluginBtn = button("choose plugin...");
      pluginBtn.addEventListener("click", choosePlugin);
      bodyEl.appendChild(pluginBtn);

      wavBtn = button("choose wav...");
      wavBtn.addEventListener("click", chooseWav);
      bodyEl.appendChild(wavBtn);

      paramsEl = document.createElement("div");
      styleEl(paramsEl, { maxHeight: "160px", overflowY: "auto", margin: "4px 0" });
      bodyEl.appendChild(paramsEl);

      renderBtn = button("render");
      renderBtn.style.textAlign = "center";
      renderBtn.addEventListener("click", doRender);
      bodyEl.appendChild(renderBtn);

      resultEl = document.createElement("div");
      bodyEl.appendChild(resultEl);

      statusEl = document.createElement("div");
      styleEl(statusEl, { fontSize: "10px", color: "#9a9a9a", marginTop: "2px", minHeight: "12px" });
      bodyEl.appendChild(statusEl);

      document.body.appendChild(panel);
    }

    window.clapTools.available().then(function (ok) {
      if (!ok) { return; } // CLI not built; stay out of the way
      if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", buildPanel);
      } else {
        buildPanel();
      }
    });
  })();
};
