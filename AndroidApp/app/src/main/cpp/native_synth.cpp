#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cmath>

#define LOG_TAG "EP133NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int   MAX_VOICES  = 8;
static constexpr float A4_FREQ     = 440.0f;
static constexpr float A4_MIDI     = 69.0f;
static constexpr float TWO_PI      = 6.28318530718f;

// Lo-fi Rhodes character constants
static constexpr float kLFOFreq    = 5.0f;    // tremolo rate (Hz)
static constexpr float kTremDepth  = 0.08f;   // ±8% amplitude tremolo
static constexpr float kDrive      = 0.8f;    // saturation drive
static constexpr float kBitScale   = 2048.0f; // 12-bit quantization (MPC 60 / SP-1200)

// ── Voice ─────────────────────────────────────────────────────────────────────
//
// 2-operator FM (DX7 electric piano algorithm):
//   modulator = sin(phaseM) × modIndex
//   carrier   = sin(phaseC + modulator)
//
// modIndex decays from a velocity-scaled peak to 0 over 200 ms, giving a bright
// clangorous attack that fades to a warm pure sine — the defining Rhodes character.
//
// Per-note LFO phase offset staggers tremolo between notes in a chord, producing
// a natural chorusing effect as the phases drift.

struct Voice {
    std::atomic<bool> active{false};
    std::atomic<bool> releasing{false};

    int   midiNote{-1};
    float frequency{440.0f};
    float amplitude{0.0f};

    float phaseC{0.0f};       // carrier phase   [0, 1)
    float phaseM{0.0f};       // modulator phase [0, 1)
    float phaseLFO{0.0f};     // tremolo LFO phase [0, 1)

    float modIndex{0.0f};      // current FM modulation depth
    float modPeak{0.0f};       // peak modIndex (ramp target)
    float modAttackRate{0.0f}; // per-sample increase during mod ramp-up
    float modDecayRate{0.0f};  // per-sample decrease after peak
    bool  modInAttack{true};   // true while modIndex is still ramping up

    float envGain{0.0f};
    float attackRate{0.0f};
    float decayRate{0.0f};
    float sustainLevel{0.0f};
    float releaseRate{0.0f};
    bool  inAttack{true};
};

// ── NativeSynth ───────────────────────────────────────────────────────────────

class NativeSynth : public oboe::AudioStreamDataCallback {
public:
    std::array<Voice, MAX_VOICES> voices{};
    oboe::AudioStream* stream{nullptr};
    int sampleRate{48000};

    // Precomputed constants (set once in start())
    float kDriveNorm{1.0f};
    float kBitScaleInv{1.0f / kBitScale};

    bool start(int requestedSr) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(oboe::ChannelCount::Mono);
        builder.setSampleRate(requestedSr);
        builder.setDataCallback(this);

        oboe::Result result = builder.openStream(&stream);
        if (result != oboe::Result::OK) {
            LOGE("Exclusive open failed (%s), retrying shared", oboe::convertToText(result));
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(&stream);
        }
        if (result != oboe::Result::OK) {
            LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
            return false;
        }

        sampleRate   = stream->getSampleRate();
        kDriveNorm   = tanhf(kDrive);
        kBitScaleInv = 1.0f / kBitScale;

        LOGI("Oboe open: sr=%d burst=%d sharing=%s",
             sampleRate,
             stream->getFramesPerBurst(),
             stream->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");

        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start stream: %s", oboe::convertToText(result));
            stream->close();
            stream = nullptr;
            return false;
        }
        return true;
    }

    void noteOn(int midiNote, int velocity) {
        // Find a free voice; on overflow steal a releasing one
        Voice* target = nullptr;
        for (auto& v : voices) {
            if (!v.active.load(std::memory_order_acquire)) { target = &v; break; }
        }
        if (!target) {
            for (auto& v : voices) {
                if (v.releasing.load(std::memory_order_relaxed)) { target = &v; break; }
            }
        }
        if (!target) return; // 8 active sustained notes — drop

        float freq = A4_FREQ * powf(2.0f, (midiNote - A4_MIDI) / 12.0f);
        float amp  = (velocity / 127.0f) * 0.55f;
        float sr   = static_cast<float>(sampleRate);

        // Amplitude envelope
        float atk = sr * 0.035f;   // 35 ms attack (softer onset)
        float dec = sr * 0.600f;   // 600 ms decay
        float sus = amp * 0.25f;   // 25% sustain level
        float rel = sr * 0.150f;   // 150 ms release

        // FM: modIndex ramps 0→peak over 15 ms, then decays to 0 over 200 ms.
        // Starting at 0 avoids the instant sideband click at note-on.
        float peakMod       = (velocity / 127.0f) * 0.8f;
        float modAttackSamp = sr * 0.015f; // 15 ms ramp-up
        float modDecaySamp  = sr * 0.200f; // 200 ms decay to pure sine

        // Per-note LFO phase offset staggers tremolo in chords (pseudo-random per pitch)
        float lfoOffset = fmodf(static_cast<float>(midiNote) * 0.137f, 1.0f);

        target->midiNote     = midiNote;
        target->frequency    = freq;
        target->amplitude    = amp;
        target->phaseC       = 0.0f;
        target->phaseM       = 0.0f;
        target->phaseLFO     = lfoOffset;
        target->modIndex     = 0.0f;
        target->modPeak      = peakMod;
        target->modAttackRate = peakMod / modAttackSamp;
        target->modDecayRate = peakMod / modDecaySamp;
        target->modInAttack  = true;
        target->envGain      = 0.0f;
        target->attackRate   = amp / atk;
        target->decayRate    = (amp - sus) / dec;
        target->sustainLevel = sus;
        target->releaseRate  = amp / rel;
        target->inAttack     = true;
        target->releasing.store(false, std::memory_order_relaxed);
        // Release fence: all writes above are visible before active becomes true
        target->active.store(true, std::memory_order_release);
    }

    void noteOff(int midiNote) {
        for (auto& v : voices) {
            if (v.active.load(std::memory_order_relaxed)
                    && v.midiNote == midiNote
                    && !v.releasing.load(std::memory_order_relaxed)) {
                v.releasing.store(true, std::memory_order_relaxed);
                break;
            }
        }
    }

    void allNotesOff() {
        for (auto& v : voices) {
            v.releasing.store(true, std::memory_order_relaxed);
        }
    }

    void close() {
        if (stream) {
            stream->requestStop();
            stream->close();
            stream = nullptr;
        }
    }

    // ── Audio callback ────────────────────────────────────────────────────────
    // Real-time native thread. No allocations, no locks, no JNI.
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* /*stream*/,
            void* audioData,
            int32_t numFrames) override {

        auto* out = static_cast<float*>(audioData);
        for (int i = 0; i < numFrames; ++i) out[i] = 0.0f;

        float sr       = static_cast<float>(sampleRate);
        float lfoIncr  = kLFOFreq / sr;

        for (auto& v : voices) {
            // Acquire fence pairs with the release store in noteOn()
            if (!v.active.load(std::memory_order_acquire)) continue;

            float freq         = v.frequency;
            float phaseC       = v.phaseC;
            float phaseM       = v.phaseM;
            float phaseLFO     = v.phaseLFO;
            float modIndex     = v.modIndex;
            float modDecayRate = v.modDecayRate;
            float envGain      = v.envGain;
            float amp          = v.amplitude;
            float attackRate   = v.attackRate;
            float decayRate    = v.decayRate;
            float sustainLevel = v.sustainLevel;
            float releaseRate  = v.releaseRate;
            bool  inAttack     = v.inAttack;
            bool  stillActive  = true;

            float freqIncr = freq / sr;

            for (int i = 0; i < numFrames; ++i) {
                // ── Amplitude envelope ────────────────────────────────────────
                if (v.releasing.load(std::memory_order_relaxed)) {
                    envGain -= releaseRate;
                    if (envGain <= 0.0f) { envGain = 0.0f; stillActive = false; break; }
                } else if (inAttack) {
                    envGain += attackRate;
                    if (envGain >= amp) { envGain = amp; inAttack = false; }
                } else if (envGain > sustainLevel) {
                    envGain -= decayRate;
                    if (envGain < sustainLevel) envGain = sustainLevel;
                }

                // ── FM synthesis ──────────────────────────────────────────────
                float mod     = sinf(phaseM * TWO_PI) * modIndex;
                float carrier = sinf((phaseC + mod) * TWO_PI);

                // ── Tremolo ───────────────────────────────────────────────────
                float tremolo = 1.0f + kTremDepth * sinf(phaseLFO * TWO_PI);

                out[i] += carrier * envGain * tremolo;

                // FM modulation envelope: ramp up to peak, then decay to pure sine
                if (v.modInAttack) {
                    modIndex += v.modAttackRate;
                    if (modIndex >= v.modPeak) { modIndex = v.modPeak; v.modInAttack = false; }
                } else {
                    modIndex -= modDecayRate;
                    if (modIndex < 0.0f) modIndex = 0.0f;
                }

                // Phase updates — normalized [0, 1) to avoid float precision drift
                phaseC   += freqIncr; if (phaseC   >= 1.0f) phaseC   -= 1.0f;
                phaseM   += freqIncr; if (phaseM   >= 1.0f) phaseM   -= 1.0f;
                phaseLFO += lfoIncr;  if (phaseLFO >= 1.0f) phaseLFO -= 1.0f;
            }

            v.phaseC     = phaseC;
            v.phaseM     = phaseM;
            v.phaseLFO   = phaseLFO;
            v.modIndex   = modIndex;
            v.envGain    = envGain;
            v.inAttack   = inAttack;
            if (!stillActive) v.active.store(false, std::memory_order_relaxed);
        }

        // ── Post-mix: soft saturation + 12-bit quantization ──────────────────
        // tanh drive gives natural compression on full chords; roundf quantization
        // replicates the MPC 60 / SP-1200 12-bit noise floor (≈ −72 dBFS).
        float driveNorm   = kDriveNorm;
        float bitScale    = kBitScale;
        float bitScaleInv = kBitScaleInv;

        for (int i = 0; i < numFrames; ++i) {
            float x = tanhf(out[i] * kDrive) / driveNorm;
            x = roundf(x * bitScale) * bitScaleInv;
            out[i] = x;
        }

        return oboe::DataCallbackResult::Continue;
    }
};

// ── JNI bridge ────────────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeCreate(
        JNIEnv*, jobject, jint sampleRate) {
    auto* synth = new NativeSynth();
    if (!synth->start(static_cast<int>(sampleRate))) {
        delete synth;
        return 0L;
    }
    return reinterpret_cast<jlong>(synth);
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeNoteOn(
        JNIEnv*, jobject, jlong ptr, jint note, jint velocity) {
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeNoteOff(
        JNIEnv*, jobject, jlong ptr, jint note) {
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeAllNotesOff(
        JNIEnv*, jobject, jlong ptr) {
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->allNotesOff();
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeClose(
        JNIEnv*, jobject, jlong ptr) {
    if (ptr) {
        auto* synth = reinterpret_cast<NativeSynth*>(ptr);
        synth->close();
        delete synth;
    }
}

} // extern "C"
