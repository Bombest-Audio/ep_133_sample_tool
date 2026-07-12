#pragma once

#include <JuceHeader.h>
#include "PluginProcessor.h"

/**
 * EP133AudioProcessorEditor
 *
 * Hosts the existing web-based EP-133 Sample Tool inside a JUCE
 * WebBrowserComponent using JUCE 8's ResourceProvider API so that
 * all web assets (HTML / JS / CSS / WASM / fonts / .pak) are served
 * via an internal HTTPS-like URL scheme – no file:// restrictions apply.
 *
 * A small JavaScript polyfill is injected into index.html that overrides
 * navigator.requestMIDIAccess() and routes MIDI through JUCE's own
 * MidiInput / MidiOutput APIs so the plugin communicates directly with
 * the EP-133 hardware regardless of the DAW's MIDI routing.
 *
 * MIDI bridge (C++ ↔ JS) uses JUCE 8's native function interface:
 *   JS calls  : window.__JUCE__.invoke('getMidiDevices')  → Promise<{inputs,outputs}>
 *   JS calls  : window.__JUCE__.invoke('sendMidi', portId, [bytes])
 *   JUCE emits: webBrowser.emitEventIfBrowserIsVisible("midiIn", {portId, data})
 */
class EP133AudioProcessorEditor : public juce::AudioProcessorEditor,
                                  public juce::MidiInputCallback
{
public:
    explicit EP133AudioProcessorEditor (EP133AudioProcessor&);
    ~EP133AudioProcessorEditor() override;

    void paint  (juce::Graphics&) override;
    void resized() override;

private:
    // -----------------------------------------------------------------------
    // ResourceProvider – called by WebBrowserComponent for every URL request
    // -----------------------------------------------------------------------
    std::optional<juce::WebBrowserComponent::Resource>
        getResource (const juce::String& url);

    // Returns the modified index.html with the MIDI bridge polyfill injected.
    std::optional<juce::WebBrowserComponent::Resource> getIndexHtmlResource();

    // -----------------------------------------------------------------------
    // Native functions registered with the browser (called from JS)
    // -----------------------------------------------------------------------
    void handleGetMidiDevices (std::function<void(const juce::var&)> complete);
    void handleSendMidi       (const juce::Array<juce::var>& args,
                               std::function<void(const juce::var&)> complete);

    // -----------------------------------------------------------------------
    // juce::MidiInputCallback – called on a MIDI background thread
    // -----------------------------------------------------------------------
    void handleIncomingMidiMessage (juce::MidiInput*         source,
                                    const juce::MidiMessage& message) override;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    static juce::File   findDataDirectory();
    static juce::String getMimeType (const juce::String& fileExtension);
    static juce::String getMidiBridgeScript();

    // -----------------------------------------------------------------------
    // Members
    // (declaration order == initialisation order – dataDir before webBrowser)
    // -----------------------------------------------------------------------
    EP133AudioProcessor& audioProcessor;
    juce::File           dataDir;

    juce::WebBrowserComponent webBrowser;

    // Open MIDI ports owned by this editor (closed in destructor)
    juce::OwnedArray<juce::MidiInput>                        openInputs;
    std::map<juce::String, std::unique_ptr<juce::MidiOutput>> openOutputs;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (EP133AudioProcessorEditor)
};
