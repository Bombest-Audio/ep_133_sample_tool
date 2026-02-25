#include "PluginEditor.h"

// =============================================================================
// JavaScript MIDI-bridge polyfill
// Injected as an inline <script> block at the top of <head> so it runs before
// any other scripts on the page.
//
// The polyfill overrides navigator.requestMIDIAccess() and routes all MIDI
// I/O through JUCE 8's native function bridge (window.__JUCE__):
//
//  JS → JUCE  : window.__JUCE__.invoke('getMidiDevices')  → {inputs, outputs}
//  JS → JUCE  : window.__JUCE__.invoke('sendMidi', portId, [bytes])
//  JUCE → JS  : window.__JUCE__ emits event 'midiIn' {portId, data:[bytes]}
// =============================================================================
static const char* kMidiBridgeScript = R"JS(
(function () {
  'use strict';

  // midiListeners[portId] = [handler, ...]
  var midiListeners = {};

  function installBridge () {
    // Listen for incoming MIDI messages pushed from JUCE
    window.__JUCE__.addEventListener('midiIn', function (event) {
      var portId    = event.portId;
      var listeners = midiListeners[portId] || [];
      var msg       = { data: new Uint8Array(event.data),
                        target: { id: portId } };
      for (var i = 0; i < listeners.length; ++i) {
        try { listeners[i](msg); } catch (e) { console.error(e); }
      }
    });

    // Override the Web MIDI API
    navigator.requestMIDIAccess = function (options) {
      return window.__JUCE__.invoke('getMidiDevices').then(function (devices) {
        var inputs  = new Map();
        var outputs = new Map();

        (devices.inputs || []).forEach(function (d) {
          midiListeners[d.id] = midiListeners[d.id] || [];
          var port = {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'input',
            addEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = midiListeners[d.id] || [];
                midiListeners[d.id].push(fn);
              }
            },
            removeEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = (midiListeners[d.id] || []).filter(
                  function (f) { return f !== fn; });
              }
            }
          };
          // onmidimessage shorthand property
          Object.defineProperty(port, 'onmidimessage', {
            get: function () {
              return (midiListeners[d.id] || [])[0] || null;
            },
            set: function (fn) {
              midiListeners[d.id] = fn ? [fn] : [];
            }
          });
          inputs.set(d.id, port);
        });

        (devices.outputs || []).forEach(function (d) {
          outputs.set(d.id, {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'output',
            send: function (data /*, timestamp */) {
              window.__JUCE__.invoke('sendMidi', d.id, Array.from(data));
            },
            clear: function () {},
            addEventListener:    function () {},
            removeEventListener: function () {}
          });
        });

        return {
          inputs:       inputs,
          outputs:      outputs,
          sysexEnabled: !!(options && options.sysex),
          onstatechange: null,
          addEventListener:    function () {},
          removeEventListener: function () {}
        };
      });
    };

    console.log('[JUCE] MIDI bridge installed');
  }

  // window.__JUCE__ is injected by JUCE before page scripts run, but guard
  // with a short retry loop in case timing varies across JUCE versions.
  if (typeof window.__JUCE__ !== 'undefined') {
    installBridge();
  } else {
    var attempts = 0;
    var timer = setInterval(function () {
      if (typeof window.__JUCE__ !== 'undefined') {
        clearInterval(timer);
        installBridge();
      } else if (++attempts >= 50) {
        clearInterval(timer);
        console.warn('[JUCE] MIDI bridge: window.__JUCE__ not available');
      }
    }, 100);
  }
})();
)JS";

// =============================================================================
// Constructor – builds the WebBrowserComponent options (resource provider +
// native MIDI functions) and loads the web app.
// =============================================================================
EP133AudioProcessorEditor::EP133AudioProcessorEditor (EP133AudioProcessor& p)
    : AudioProcessorEditor (&p),
      audioProcessor (p),
      dataDir (EP133AudioProcessorEditor::findDataDirectory()),
      webBrowser (juce::WebBrowserComponent::Options{}
          // Serve all web assets from memory / disk via this callback
          .withResourceProvider (
              [this] (const juce::String& url)
              {
                  return getResource (url);
              })
          // Enable window.__JUCE__ in JavaScript
          .withNativeIntegrationEnabled()
          // JS: await window.__JUCE__.invoke('getMidiDevices')
          //     → { inputs:[{id,name},...], outputs:[{id,name},...] }
          .withNativeFunction (
              "getMidiDevices",
              [this] (const juce::Array<juce::var>& /*args*/,
                      std::function<void(const juce::var&)> complete)
              {
                  handleGetMidiDevices (std::move (complete));
              })
          // JS: await window.__JUCE__.invoke('sendMidi', portId, [byte, ...])
          .withNativeFunction (
              "sendMidi",
              [this] (const juce::Array<juce::var>& args,
                      std::function<void(const juce::var&)> complete)
              {
                  handleSendMidi (args, std::move (complete));
              }))
{
    addAndMakeVisible (webBrowser);

    // Navigate to the resource-provider root (JUCE resolves "/" → index.html)
    webBrowser.goToURL (webBrowser.getResourceProviderRoot());

    setSize (1200, 800);
}

EP133AudioProcessorEditor::~EP133AudioProcessorEditor()
{
    // Stop all open MIDI inputs before destroying the editor
    for (auto* input : openInputs)
        input->stop();

    openInputs.clear();
    openOutputs.clear();
}

// =============================================================================
// Component overrides
// =============================================================================
void EP133AudioProcessorEditor::paint (juce::Graphics& g)
{
    g.fillAll (juce::Colours::black);
}

void EP133AudioProcessorEditor::resized()
{
    webBrowser.setBounds (getLocalBounds());
}

// =============================================================================
// ResourceProvider
// =============================================================================
std::optional<juce::WebBrowserComponent::Resource>
EP133AudioProcessorEditor::getResource (const juce::String& url)
{
    // Extract the path portion from the full internal URL.
    // e.g. "https://juce.resource.provider/index.js" → "index.js"
    auto path = juce::URL (url).getSubPath().trimCharactersAtStart ("/");

    if (path.isEmpty() || path == "index.html")
        return getIndexHtmlResource();

    auto file = dataDir.getChildFile (path);
    if (!file.existsAsFile())
        return std::nullopt;

    juce::MemoryBlock data;
    if (!file.loadFileAsData (data))
        return std::nullopt;

    auto* ptr = static_cast<const std::byte*> (data.getData());
    return juce::WebBrowserComponent::Resource {
        std::vector<std::byte> (ptr, ptr + data.getSize()),
        getMimeType (file.getFileExtension())
    };
}

std::optional<juce::WebBrowserComponent::Resource>
EP133AudioProcessorEditor::getIndexHtmlResource()
{
    auto indexFile = dataDir.getChildFile ("index.html");
    if (!indexFile.existsAsFile())
        return std::nullopt;

    // Inject the MIDI bridge polyfill into the page before any other scripts
    auto html = indexFile.loadFileAsString();
    juce::String injection = juce::String ("\n<script>\n")
                           + juce::String (kMidiBridgeScript)
                           + juce::String ("</script>\n");

    // Insert right after <head> (case-insensitive search)
    html = html.replace ("<head>", "<head>" + injection, true);

    auto utf8 = html.toStdString();
    auto* ptr = reinterpret_cast<const std::byte*> (utf8.data());
    return juce::WebBrowserComponent::Resource {
        std::vector<std::byte> (ptr, ptr + utf8.size()),
        "text/html"
    };
}

// =============================================================================
// Native MIDI functions (called from JavaScript via window.__JUCE__.invoke)
// =============================================================================
void EP133AudioProcessorEditor::handleGetMidiDevices (
    std::function<void(const juce::var&)> complete)
{
    juce::Array<juce::var> inputs, outputs;

    for (const auto& d : juce::MidiInput::getAvailableDevices())
    {
        juce::DynamicObject::Ptr obj = new juce::DynamicObject();
        obj->setProperty ("id",   d.identifier);
        obj->setProperty ("name", d.name);
        inputs.add (juce::var (obj.get()));

        // Open the input and start receiving if not already open
        bool alreadyOpen = false;
        for (auto* in : openInputs)
            if (in->getIdentifier() == d.identifier)
                alreadyOpen = true;

        if (!alreadyOpen)
        {
            if (auto input = juce::MidiInput::openDevice (d.identifier, this))
            {
                input->start();
                openInputs.add (std::move (input));
            }
        }
    }

    for (const auto& d : juce::MidiOutput::getAvailableDevices())
    {
        juce::DynamicObject::Ptr obj = new juce::DynamicObject();
        obj->setProperty ("id",   d.identifier);
        obj->setProperty ("name", d.name);
        outputs.add (juce::var (obj.get()));
    }

    juce::DynamicObject::Ptr result = new juce::DynamicObject();
    result->setProperty ("inputs",  inputs);
    result->setProperty ("outputs", outputs);

    complete (juce::var (result.get()));
}

void EP133AudioProcessorEditor::handleSendMidi (
    const juce::Array<juce::var>& args,
    std::function<void(const juce::var&)> complete)
{
    if (args.size() < 2)
    {
        complete (juce::var (false));
        return;
    }

    auto portId   = args[0].toString();
    auto* dataArr = args[1].getArray();

    if (dataArr == nullptr || dataArr->isEmpty())
    {
        complete (juce::var (false));
        return;
    }

    // Open the output port on first use (cached for subsequent calls)
    if (openOutputs.find (portId) == openOutputs.end())
    {
        if (auto output = juce::MidiOutput::openDevice (portId))
            openOutputs[portId] = std::move (output);
    }

    auto it = openOutputs.find (portId);
    if (it == openOutputs.end() || it->second == nullptr)
    {
        complete (juce::var (false));
        return;
    }

    // Build raw byte buffer
    std::vector<uint8_t> bytes;
    bytes.reserve ((size_t) dataArr->size());
    for (const auto& b : *dataArr)
        bytes.push_back (static_cast<uint8_t> (static_cast<int> (b)));

    auto msg = juce::MidiMessage (bytes.data(), (int) bytes.size());
    it->second->sendMessageNow (msg);

    complete (juce::var (true));
}

// =============================================================================
// MidiInputCallback – called on a MIDI background thread
// =============================================================================
void EP133AudioProcessorEditor::handleIncomingMidiMessage (
    juce::MidiInput*         source,
    const juce::MidiMessage& message)
{
    const auto* raw      = message.getRawData();
    const int   rawSize  = message.getRawDataSize();
    const auto  portId   = source->getIdentifier();

    juce::Array<juce::var> dataArray;
    dataArray.ensureStorageAllocated (rawSize);
    for (int i = 0; i < rawSize; ++i)
        dataArray.add (juce::var (static_cast<int> (raw[i])));

    juce::DynamicObject::Ptr event = new juce::DynamicObject();
    event->setProperty ("portId", portId);
    event->setProperty ("data",   dataArray);

    juce::var eventVar (event.get());

    // emitEventIfBrowserIsVisible must be called on the message thread
    juce::MessageManager::callAsync ([this, eventVar] {
        webBrowser.emitEventIfBrowserIsVisible ("midiIn", eventVar);
    });
}

// =============================================================================
// Helpers
// =============================================================================
juce::File EP133AudioProcessorEditor::findDataDirectory()
{
    // When installed as a plugin bundle on macOS the layout is:
    //   EP-133 Sample Tool.component/Contents/Resources/data/
    auto pluginBundle = juce::File::getSpecialLocation (
        juce::File::currentApplicationFile);
    auto bundleData = pluginBundle.getChildFile ("Contents/Resources/data");

    if (bundleData.isDirectory())
        return bundleData;

    // Fallback: walk up from the executable to find the data/ directory
    // (useful during development / running from the build tree)
    auto dir = juce::File::getSpecialLocation (
        juce::File::currentExecutableFile).getParentDirectory();

    for (int i = 0; i < 6; ++i)
    {
        auto candidate = dir.getChildFile ("data");
        if (candidate.isDirectory())
            return candidate;
        dir = dir.getParentDirectory();
    }

    // Last resort: look next to the repository root
    return juce::File::getSpecialLocation (
        juce::File::currentExecutableFile)
           .getParentDirectory()
           .getSiblingFile ("data");
}

juce::String EP133AudioProcessorEditor::getMimeType (const juce::String& ext)
{
    if (ext == ".html")  return "text/html";
    if (ext == ".css")   return "text/css";
    if (ext == ".js")    return "application/javascript";
    if (ext == ".wasm")  return "application/wasm";
    if (ext == ".json")  return "application/json";
    if (ext == ".png")   return "image/png";
    if (ext == ".ico")   return "image/x-icon";
    if (ext == ".otf")   return "font/otf";
    if (ext == ".woff")  return "font/woff";
    if (ext == ".woff2") return "font/woff2";
    if (ext == ".pak")   return "application/octet-stream";
    if (ext == ".hmls")  return "application/octet-stream";
    return "application/octet-stream";
}
