#ifndef EP133_SYNTH_CORE_H
#define EP133_SYNTH_CORE_H

// EP-133 lo-fi Rhodes synth core - the single voice loop shared by the live
// Oboe callback and the offline renderer. Pure C++ (no Oboe, no JNI, no
// Android headers) so it can also be compiled for host-side tests.
//
// IMPORTANT: this is the ONE synth. There is no second offline synth -
// renderOffline() drives renderBlock(), the exact function the Oboe callback
// calls. The Kotlin replica in domain/audio/voice/SynthCore.kt mirrors this
// math for JVM unit tests and MUST be kept in sync with any change here.

#include <array>
#include <atomic>
#include <cmath>
#include <vector>

namespace ep133 {

static constexpr int   MAX_VOICES  = 8;
static constexpr float A4_FREQ     = 440.0f;
static constexpr float A4_MIDI     = 69.0f;
static constexpr float TWO_PI      = 6.28318530718f;

// Lo-fi Rhodes character constants
static constexpr float kLFOFreq    = 5.0f;    // tremolo rate (Hz)
static constexpr float kTremDepth  = 0.08f;   // +/-8% amplitude tremolo
static constexpr float kDrive      = 0.8f;    // saturation drive
static constexpr float kBitScale   = 2048.0f; // 12-bit quantization (MPC 60 / SP-1200)

// ── Voice ─────────────────────────────────────────────────────────────────────
//
// 2-operator FM (DX7 electric piano algorithm):
//   modulator = sin(phaseM) x modIndex
//   carrier   = sin(phaseC + modulator)
//
// modIndex ramps 0 -> peak over 15 ms then decays to 0 over 200 ms, giving a
// bright clangorous attack that fades to a warm pure sine - the defining
// Rhodes character.
//
// Per-note LFO phase offset staggers tremolo between notes in a chord,
// producing a natural chorusing effect as the phases drift.

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

// ── SynthCore ─────────────────────────────────────────────────────────────────

class SynthCore {
public:
    std::array<Voice, MAX_VOICES> voices{};
    int sampleRate{48000};

    // Precomputed constant (set once in setSampleRate())
    float kBitScaleInv{1.0f / kBitScale};

    void setSampleRate(int sr) {
        sampleRate   = sr;
        kBitScaleInv = 1.0f / kBitScale;
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
        if (!target) return; // 8 active sustained notes - drop

        float freq = A4_FREQ * powf(2.0f, (midiNote - A4_MIDI) / 12.0f);
        float amp  = (velocity / 127.0f) * 0.55f;
        float sr   = static_cast<float>(sampleRate);

        // Amplitude envelope
        float atk = sr * 0.035f;   // 35 ms attack (softer onset)
        float dec = sr * 0.600f;   // 600 ms decay
        float sus = amp * 0.25f;   // 25% sustain level
        float rel = sr * 0.150f;   // 150 ms release

        // FM: modIndex ramps 0 -> peak over 15 ms, then decays to 0 over 200 ms.
        // Starting at 0 avoids the instant sideband click at note-on.
        float peakMod       = (velocity / 127.0f) * 0.8f;
        float modAttackSamp = sr * 0.015f; // 15 ms ramp-up
        float modDecaySamp  = sr * 0.200f; // 200 ms decay to pure sine

        // Per-note LFO phase offset staggers tremolo in chords (pseudo-random per pitch)
        float lfoOffset = fmodf(static_cast<float>(midiNote) * 0.137f, 1.0f);

        target->midiNote      = midiNote;
        target->frequency     = freq;
        target->amplitude     = amp;
        target->phaseC        = 0.0f;
        target->phaseM        = 0.0f;
        target->phaseLFO      = lfoOffset;
        target->modIndex      = 0.0f;
        target->modPeak       = peakMod;
        target->modAttackRate = peakMod / modAttackSamp;
        target->modDecayRate  = peakMod / modDecaySamp;
        target->modInAttack   = true;
        target->envGain       = 0.0f;
        target->attackRate    = amp / atk;
        target->decayRate     = (amp - sus) / dec;
        target->sustainLevel  = sus;
        target->releaseRate   = amp / rel;
        target->inAttack      = true;
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

    // ── The per-sample voice loop ─────────────────────────────────────────────
    // Called from the Oboe real-time callback (no allocations, no locks) AND
    // from renderOffline(). Output is chunk-size invariant: all per-sample
    // state is carried across calls, so rendering N frames in one call equals
    // rendering them in any sequence of smaller calls.
    void renderBlock(float* out, int numFrames) {
        for (int i = 0; i < numFrames; ++i) out[i] = 0.0f;

        float sr      = static_cast<float>(sampleRate);
        float lfoIncr = kLFOFreq / sr;

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

                // Phase updates - normalized [0, 1) to avoid float precision drift
                phaseC   += freqIncr; if (phaseC   >= 1.0f) phaseC   -= 1.0f;
                phaseM   += freqIncr; if (phaseM   >= 1.0f) phaseM   -= 1.0f;
                phaseLFO += lfoIncr;  if (phaseLFO >= 1.0f) phaseLFO -= 1.0f;
            }

            v.phaseC   = phaseC;
            v.phaseM   = phaseM;
            v.phaseLFO = phaseLFO;
            v.modIndex = modIndex;
            v.envGain  = envGain;
            v.inAttack = inAttack;
            if (!stillActive) v.active.store(false, std::memory_order_relaxed);
        }

        // ── Post-mix: soft saturation + 12-bit quantization ──────────────────
        // tanh drive gives natural compression on full chords and bounds the
        // mix below 0 dBFS (|tanh| < 1); roundf quantization replicates the
        // MPC 60 / SP-1200 12-bit noise floor (~ -72 dBFS).
        // NOTE: an earlier version divided by tanh(kDrive) to normalize unity
        // input to unity output, but that pushes dense chords to +3.5 dBFS
        // (mix of 6 voices saturates toward 1.0, then /0.664 clips). The
        // Phase 8 quality gate caught it; the normalization is gone.
        float bitScale    = kBitScale;
        float bitScaleInv = kBitScaleInv;

        for (int i = 0; i < numFrames; ++i) {
            float x = tanhf(out[i] * kDrive);
            x = roundf(x * bitScale) * bitScaleInv;
            out[i] = x;
        }
    }

    // ── Offline render ────────────────────────────────────────────────────────
    // Renders a sequence of chord steps through the SAME renderBlock() the Oboe
    // callback uses. Chords are played back to back: noteOn all notes of a
    // chord, render its frame count, noteOff, next chord; then render tailFrames
    // so the final release rings out. Single-threaded; do not call while an
    // Oboe stream is driving the same core.
    //
    // chordNotes:  flattened MIDI notes of every chord
    // chordStarts: numChords+1 offsets into chordNotes (chord c spans
    //              [chordStarts[c], chordStarts[c+1]))
    // chordFrames: frames to render for each chord (numChords entries)
    std::vector<float> renderOffline(
            const std::vector<int>& chordNotes,
            const std::vector<int>& chordStarts,
            const std::vector<int>& chordFrames,
            int velocity,
            int tailFrames) {
        size_t numChords = chordFrames.size();
        size_t total = static_cast<size_t>(tailFrames);
        for (int f : chordFrames) total += static_cast<size_t>(f);

        std::vector<float> out(total, 0.0f);
        size_t pos = 0;

        for (size_t c = 0; c < numChords; ++c) {
            for (int n = chordStarts[c]; n < chordStarts[c + 1]; ++n) {
                noteOn(chordNotes[static_cast<size_t>(n)], velocity);
            }
            renderBlock(out.data() + pos, chordFrames[c]);
            pos += static_cast<size_t>(chordFrames[c]);
            for (int n = chordStarts[c]; n < chordStarts[c + 1]; ++n) {
                noteOff(chordNotes[static_cast<size_t>(n)]);
            }
        }
        if (tailFrames > 0) renderBlock(out.data() + pos, tailFrames);
        return out;
    }
};

} // namespace ep133

#endif // EP133_SYNTH_CORE_H
