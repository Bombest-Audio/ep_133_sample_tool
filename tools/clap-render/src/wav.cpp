#include "wav.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <stdexcept>

namespace wav {

namespace {

uint16_t readU16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
uint32_t readU32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

void writeU16(std::ofstream& out, uint16_t v) {
    uint8_t b[2] = { (uint8_t)(v & 0xff), (uint8_t)(v >> 8) };
    out.write((const char*)b, 2);
}
void writeU32(std::ofstream& out, uint32_t v) {
    uint8_t b[4] = { (uint8_t)(v & 0xff), (uint8_t)((v >> 8) & 0xff),
                     (uint8_t)((v >> 16) & 0xff), (uint8_t)((v >> 24) & 0xff) };
    out.write((const char*)b, 4);
}

} // namespace

AudioFile read(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open input file: " + path);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());

    if (data.size() < 44 || std::memcmp(data.data(), "RIFF", 4) != 0 ||
        std::memcmp(data.data() + 8, "WAVE", 4) != 0)
        throw std::runtime_error("not a RIFF/WAVE file: " + path);

    uint16_t format = 0, channels = 0, bitsPerSample = 0;
    uint32_t sampleRate = 0;
    const uint8_t* pcm = nullptr;
    uint32_t pcmBytes = 0;

    // Walk chunks starting after the 12-byte RIFF header.
    size_t pos = 12;
    while (pos + 8 <= data.size()) {
        const uint8_t* hdr = data.data() + pos;
        uint32_t chunkSize = readU32(hdr + 4);
        size_t body = pos + 8;
        if (body + chunkSize > data.size())
            chunkSize = (uint32_t)(data.size() - body); // tolerate truncated final chunk

        if (std::memcmp(hdr, "fmt ", 4) == 0 && chunkSize >= 16) {
            const uint8_t* f = data.data() + body;
            format = readU16(f);
            channels = readU16(f + 2);
            sampleRate = readU32(f + 4);
            bitsPerSample = readU16(f + 14);
            // WAVE_FORMAT_EXTENSIBLE: the real format is in the SubFormat GUID.
            if (format == 0xfffe && chunkSize >= 40)
                format = readU16(f + 24);
        } else if (std::memcmp(hdr, "data", 4) == 0) {
            pcm = data.data() + body;
            pcmBytes = chunkSize;
        }
        pos = body + chunkSize + (chunkSize & 1); // chunks are word-aligned
    }

    if (channels == 0 || sampleRate == 0) throw std::runtime_error("missing fmt chunk: " + path);
    if (pcm == nullptr) throw std::runtime_error("missing data chunk: " + path);
    if (channels > 2) throw std::runtime_error("only mono or stereo WAV supported, got " +
                                               std::to_string(channels) + " channels");

    AudioFile file;
    file.sampleRate = sampleRate;
    file.channels = channels;
    file.samples.resize(channels);

    if (format == 1 && bitsPerSample == 16) {
        size_t frames = pcmBytes / (2u * channels);
        for (auto& ch : file.samples) ch.resize(frames);
        for (size_t i = 0; i < frames; ++i)
            for (uint16_t c = 0; c < channels; ++c) {
                int16_t s = (int16_t)readU16(pcm + (i * channels + c) * 2);
                file.samples[c][i] = (float)s / 32768.0f;
            }
    } else if (format == 3 && bitsPerSample == 32) {
        size_t frames = pcmBytes / (4u * channels);
        for (auto& ch : file.samples) ch.resize(frames);
        for (size_t i = 0; i < frames; ++i)
            for (uint16_t c = 0; c < channels; ++c) {
                float s;
                std::memcpy(&s, pcm + (i * channels + c) * 4, 4);
                file.samples[c][i] = s;
            }
    } else {
        throw std::runtime_error("unsupported WAV encoding (format=" + std::to_string(format) +
                                 ", bits=" + std::to_string(bitsPerSample) +
                                 "); only PCM16 and float32 are supported");
    }
    return file;
}

void write(const std::string& path, const AudioFile& file) {
    if (file.channels == 0 || file.samples.size() != file.channels)
        throw std::runtime_error("invalid channel layout for output WAV");

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) throw std::runtime_error("cannot open output file: " + path);

    uint32_t frames = (uint32_t)file.frameCount();
    uint32_t dataBytes = frames * file.channels * 2;
    uint32_t byteRate = file.sampleRate * file.channels * 2;

    out.write("RIFF", 4);
    writeU32(out, 36 + dataBytes);
    out.write("WAVE", 4);
    out.write("fmt ", 4);
    writeU32(out, 16);
    writeU16(out, 1);  // PCM
    writeU16(out, file.channels);
    writeU32(out, file.sampleRate);
    writeU32(out, byteRate);
    writeU16(out, (uint16_t)(file.channels * 2)); // block align
    writeU16(out, 16); // bits per sample
    out.write("data", 4);
    writeU32(out, dataBytes);

    for (uint32_t i = 0; i < frames; ++i)
        for (uint16_t c = 0; c < file.channels; ++c) {
            float v = std::clamp(file.samples[c][i], -1.0f, 1.0f);
            int16_t s = (int16_t)std::lrintf(v * 32767.0f);
            writeU16(out, (uint16_t)s);
        }

    if (!out) throw std::runtime_error("failed writing output file: " + path);
}

} // namespace wav
