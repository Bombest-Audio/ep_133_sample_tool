#include "PluginProcessor.h"
#include "PluginEditor.h"

EP133AudioProcessor::EP133AudioProcessor()
    : AudioProcessor (BusesProperties()
          .withInput  ("Input",  juce::AudioChannelSet::stereo(), true)
          .withOutput ("Output", juce::AudioChannelSet::stereo(), true))
{
}

EP133AudioProcessor::~EP133AudioProcessor() {}

//==============================================================================
const juce::String EP133AudioProcessor::getName() const { return JucePlugin_Name; }

bool  EP133AudioProcessor::acceptsMidi()  const { return true;  }
bool  EP133AudioProcessor::producesMidi() const { return false; }
bool  EP133AudioProcessor::isMidiEffect() const { return false; }
double EP133AudioProcessor::getTailLengthSeconds() const { return 0.0; }

//==============================================================================
int  EP133AudioProcessor::getNumPrograms()                            { return 1;  }
int  EP133AudioProcessor::getCurrentProgram()                         { return 0;  }
void EP133AudioProcessor::setCurrentProgram (int)                     {}
const juce::String EP133AudioProcessor::getProgramName (int)          { return {}; }
void EP133AudioProcessor::changeProgramName (int, const juce::String&) {}

//==============================================================================
void EP133AudioProcessor::prepareToPlay (double, int)  {}
void EP133AudioProcessor::releaseResources()            {}

bool EP133AudioProcessor::isBusesLayoutSupported (const BusesLayout& layouts) const
{
    if (layouts.getMainOutputChannelSet() != juce::AudioChannelSet::mono()
     && layouts.getMainOutputChannelSet() != juce::AudioChannelSet::stereo())
        return false;

    return true;
}

void EP133AudioProcessor::processBlock (juce::AudioBuffer<float>& buffer,
                                        juce::MidiBuffer& midiMessages)
{
    juce::ScopedNoDenormals noDenormals;

    // This plugin does not process audio – just clear the output buffer so the
    // host receives silence and does not play back un-initialised samples.
    buffer.clear();
    ignoreUnused (midiMessages);
}

//==============================================================================
bool EP133AudioProcessor::hasEditor() const { return true; }

juce::AudioProcessorEditor* EP133AudioProcessor::createEditor()
{
    return new EP133AudioProcessorEditor (*this);
}

//==============================================================================
void EP133AudioProcessor::getStateInformation (juce::MemoryBlock&)    {}
void EP133AudioProcessor::setStateInformation (const void*, int)       {}

//==============================================================================
juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter()
{
    return new EP133AudioProcessor();
}
