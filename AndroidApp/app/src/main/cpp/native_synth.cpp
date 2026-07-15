#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <vector>

#include "synth_core.h"

#define LOG_TAG "EP133NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── NativeSynth ───────────────────────────────────────────────────────────────
// Thin Oboe wrapper around ep133::SynthCore. All DSP lives in synth_core.h so
// the live callback and the offline renderer share one voice loop.

class NativeSynth : public oboe::AudioStreamDataCallback {
public:
    ep133::SynthCore core;
    oboe::AudioStream* stream{nullptr};

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

        core.setSampleRate(stream->getSampleRate());

        LOGI("Oboe open: sr=%d burst=%d sharing=%s",
             core.sampleRate,
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

    void close() {
        if (stream) {
            stream->requestStop();
            stream->close();
            stream = nullptr;
        }
    }

    // Real-time native thread. No allocations, no locks, no JNI.
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* /*stream*/,
            void* audioData,
            int32_t numFrames) override {
        core.renderBlock(static_cast<float*>(audioData), numFrames);
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
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->core.noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeNoteOff(
        JNIEnv*, jobject, jlong ptr, jint note) {
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->core.noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_ep133_sampletool_domain_midi_NativeSynth_nativeAllNotesOff(
        JNIEnv*, jobject, jlong ptr) {
    if (ptr) reinterpret_cast<NativeSynth*>(ptr)->core.allNotesOff();
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

// Offline render entry. Creates a fresh SynthCore (no Oboe stream) and drives
// the same renderBlock() voice loop the live callback uses. Called from
// NativeOfflineRenderer (a Kotlin object, so the JNI symbol carries no
// Companion mangling).
JNIEXPORT jfloatArray JNICALL
Java_com_ep133_sampletool_domain_audio_voice_NativeOfflineRenderer_nativeRenderOffline(
        JNIEnv* env, jobject,
        jintArray jChordNotes, jintArray jChordStarts, jintArray jChordFrames,
        jint velocity, jint sampleRate, jint tailFrames) {

    auto toVec = [env](jintArray arr) {
        jsize len = env->GetArrayLength(arr);
        std::vector<int> v(static_cast<size_t>(len));
        env->GetIntArrayRegion(arr, 0, len, reinterpret_cast<jint*>(v.data()));
        return v;
    };
    std::vector<int> chordNotes  = toVec(jChordNotes);
    std::vector<int> chordStarts = toVec(jChordStarts);
    std::vector<int> chordFrames = toVec(jChordFrames);

    ep133::SynthCore core;
    core.setSampleRate(static_cast<int>(sampleRate));
    std::vector<float> pcm = core.renderOffline(
            chordNotes, chordStarts, chordFrames,
            static_cast<int>(velocity), static_cast<int>(tailFrames));

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(pcm.size()));
    if (out) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(pcm.size()), pcm.data());
    return out;
}

} // extern "C"
