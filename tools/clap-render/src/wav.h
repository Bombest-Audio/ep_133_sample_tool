// Minimal self-contained WAV I/O for clap-render.
// Reads PCM16 and float32 WAV files (mono or stereo), writes PCM16.
// No external dependencies.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace wav {

struct AudioFile {
    uint32_t sampleRate = 0;
    uint16_t channels = 0; // 1 or 2
    // Deinterleaved float samples in [-1, 1], one vector per channel.
    std::vector<std::vector<float>> samples;

    size_t frameCount() const { return samples.empty() ? 0 : samples[0].size(); }
};

// Throws std::runtime_error with a descriptive message on any parse failure.
AudioFile read(const std::string& path);

// Writes PCM16. Throws std::runtime_error on I/O failure.
void write(const std::string& path, const AudioFile& file);

} // namespace wav
