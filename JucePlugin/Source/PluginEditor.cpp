#include "PluginEditor.h"
#include "BinaryData.h"

// =============================================================================
// JavaScript MIDI-bridge polyfill
//
// The plugin injects the SAME shared/MIDIBridgePolyfill.js that the Android and
// iOS wrappers use (embedded at build time as BinaryData::MIDIBridgePolyfill_js
// via juce_add_binary_data). That polyfill auto-detects the host - it sees
// window.__JUCE__ and routes MIDI through JUCE 8's native function bridge:
//
//  JS → JUCE  : native fn 'getMidiDevices'  → {inputs, outputs}
//  JS → JUCE  : native fn 'sendMidi'(portId, [bytes])
//  JUCE → JS  : backend event 'midiIn' {portId, data:[bytes]}
// (JUCE 8 wire protocol: emitEvent '__juce__invoke' + backend.addEventListener;
//  the polyfill replicates it — there is no window.__JUCE__.invoke().)
//
// The native functions and 'midiIn' event this editor provides (below) are
// exactly what that polyfill's JUCE branch expects, so no bespoke copy is kept.
// =============================================================================
static juce::String midiBridgeScript()
{
    return juce::String::fromUTF8 (BinaryData::MIDIBridgePolyfill_js,
                                   BinaryData::MIDIBridgePolyfill_jsSize);
}

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
          // Invoked from JS via the JUCE 8 backend protocol (there is no
          // window.__JUCE__.invoke()): the shared polyfill emits "__juce__invoke"
          // {name:"getMidiDevices"} on window.__JUCE__.backend and awaits the
          // "__juce__complete" reply → { inputs:[{id,name},...], outputs:[{id,name},...] }
          .withNativeFunction (
              "getMidiDevices",
              [this] (const juce::Array<juce::var>& /*args*/,
                      std::function<void(const juce::var&)> complete)
              {
                  handleGetMidiDevices (std::move (complete));
              })
          // Invoked the same way: "__juce__invoke" {name:"sendMidi",
          // params:[portId, [byte, ...]]} on window.__JUCE__.backend
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
    // Extract the path portion. The resource provider hands us either a bare path
    // ("/index.js") or a full provider URL ("juce://juce.backend/index.js") depending
    // on the platform WebView backend (Logic's AU host uses the bare form). juce::URL
    // getSubPath() returns EMPTY for the bare form (no scheme/host), which would route
    // every request into the index.html branch and serve HTML in place of index.js/css
    // — a blank WebView. Parse the path by hand so both forms resolve correctly.
    juce::String path = url;
    if (auto schemeIdx = path.indexOf ("://"); schemeIdx >= 0)
    {
        auto afterHost = path.substring (schemeIdx + 3);
        auto slash = afterHost.indexOfChar ('/');
        path = (slash >= 0) ? afterHost.substring (slash) : juce::String ("/");
    }
    path = path.upToFirstOccurrenceOf ("?", false, false)
               .upToFirstOccurrenceOf ("#", false, false)
               .trimCharactersAtStart ("/");

    if (path.isEmpty() || path == "index.html")
        return getIndexHtmlResource();

    auto file = dataDir.getChildFile (path);

    // Reject any request that escapes the data directory. getChildFile resolves
    // "../" segments at construction, so a traversal attempt (e.g. "../../etc/
    // passwd") normalises to a path outside dataDir and is no longer a child.
    if (! file.isAChildOf (dataDir))
        return std::nullopt;

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

    // Inject the shared MIDI bridge polyfill into the page before any other scripts.
    // A synchronous marker tells the polyfill a JUCE bridge is coming, so it installs
    // the Web MIDI override immediately even though window.__JUCE__ initialises async.
    auto html = indexFile.loadFileAsString();
    juce::String injection =
          juce::String ("\n<script>window.__ep133_expectJuceBridge = true;</script>\n")
        + juce::String ("<script>\n")
        + midiBridgeScript()
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
// Native MIDI functions (invoked from JS via the JUCE 8 "__juce__invoke"
// backend event; the shared polyfill drives it — there is no __JUCE__.invoke())
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

    // Build raw byte buffer. The array comes straight from JS, so validate each
    // element: reject anything non-numeric or outside a single MIDI byte (0-255)
    // rather than let a blind cast truncate/wrap it into unintended MIDI data.
    std::vector<uint8_t> bytes;
    bytes.reserve ((size_t) dataArr->size());
    for (const auto& b : *dataArr)
    {
        if (! (b.isInt() || b.isInt64() || b.isDouble()))
        {
            complete (juce::var (false));
            return;
        }

        const int value = static_cast<int> (b);
        if (value < 0 || value > 255)
        {
            complete (juce::var (false));
            return;
        }

        bytes.push_back (static_cast<uint8_t> (value));
    }

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

    // emitEventIfBrowserIsVisible must be called on the message thread.
    // Capture a SafePointer so the lambda is a no-op if the editor is
    // destroyed before the async callback fires (e.g. host closes the UI
    // while a MIDI message is already queued on the background thread).
    juce::Component::SafePointer<EP133AudioProcessorEditor> safeThis (this);
    juce::MessageManager::callAsync ([safeThis, eventVar] {
        if (safeThis != nullptr)
            safeThis->webBrowser.emitEventIfBrowserIsVisible ("midiIn", eventVar);
    });
}

// =============================================================================
// Helpers
// =============================================================================
juce::File EP133AudioProcessorEditor::findDataDirectory()
{
    // Base every lookup on currentExecutableFile, NOT currentApplicationFile: when the
    // plugin is loaded by a host, JUCE resolves currentApplicationFile to the HOST app
    // (e.g. Logic.app), so "Contents/Resources/data" would resolve against Logic and the
    // WebView loads blank. currentExecutableFile is the plugin binary itself.
    auto exeFile = juce::File::getSpecialLocation (juce::File::currentExecutableFile);

    // Installed/built bundle layout on macOS:
    //   EP-133 Sample Tool.component/Contents/MacOS/EP-133 Sample Tool  (exeFile)
    //   EP-133 Sample Tool.component/Contents/Resources/data/           (assets)
    auto bundleData = exeFile.getParentDirectory()       // Contents/MacOS
                             .getParentDirectory()       // Contents
                             .getChildFile ("Resources/data");
    if (bundleData.isDirectory())
        return bundleData;

    // Fallback: walk up from the executable to find the data/ directory, checking both a
    // bare data/ (repo/build tree) and Contents/Resources/data (nested bundle layouts)
    // (useful during development / running from the build tree).
    auto dir = exeFile.getParentDirectory();

    for (int i = 0; i < 6; ++i)
    {
        auto bare = dir.getChildFile ("data");
        if (bare.isDirectory())
            return bare;

        auto nested = dir.getChildFile ("Resources/data");
        if (nested.isDirectory())
            return nested;

        dir = dir.getParentDirectory();
    }

    // Last resort: look next to the repository root
    return exeFile.getParentDirectory().getSiblingFile ("data");
}

juce::String EP133AudioProcessorEditor::getMimeType (const juce::String& ext)
{
    if (ext == ".html")  return "text/html";
    if (ext == ".css")   return "text/css";
    if (ext == ".js")    return "application/javascript";
    if (ext == ".wasm")  return "application/wasm";
    if (ext == ".json")  return "application/json";
    if (ext == ".png")   return "image/png";
    if (ext == ".svg")   return "image/svg+xml";
    if (ext == ".ico")   return "image/x-icon";
    if (ext == ".otf")   return "font/otf";
    if (ext == ".woff")  return "font/woff";
    if (ext == ".woff2") return "font/woff2";
    if (ext == ".pak")   return "application/octet-stream";
    if (ext == ".hmls")  return "application/octet-stream";
    return "application/octet-stream";
}
