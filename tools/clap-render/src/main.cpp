// clap-render: standalone offline CLAP render CLI.
//
// Loads a .clap plugin, instantiates the first audio-effect plugin from its
// factory, optionally applies parameter values, streams a WAV file through
// process() in blocks, and writes the result as PCM16.
//
// Single-threaded, no GUI. Every plugin fault path exits non-zero with a
// message on stderr; stdout is reserved for --list-params output.

#include <clap/clap.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "wav.h"

#if defined(__APPLE__) || defined(__linux__)
#include <dirent.h>
#include <dlfcn.h>
#include <sys/stat.h>
#endif

namespace {

// ---------------------------------------------------------------------------
// CLI options
// ---------------------------------------------------------------------------

struct Options {
    std::string pluginPath;
    std::string inPath;
    std::string outPath;
    bool listParams = false;
    double sampleRate = 0.0; // 0 = use the input WAV's rate
    std::vector<std::pair<std::string, double>> params; // key (id or name) -> value
};

void usage() {
    std::fprintf(stderr,
        "usage: clap-render --plugin X.clap [--param id=value ...] [--list-params]\n"
        "                   --in in.wav --out out.wav [--sample-rate 46875]\n"
        "\n"
        "  --plugin PATH       path to a .clap plugin (bundle on macOS)\n"
        "  --list-params       print id\\tname\\tmin\\tmax\\tdefault per parameter and exit\n"
        "  --param KEY=VALUE   set a parameter before rendering; KEY is a numeric\n"
        "                      parameter id or an exact parameter name (repeatable)\n"
        "  --in PATH           input WAV (PCM16 or float32, mono or stereo)\n"
        "  --out PATH          output WAV (PCM16, same channel count as input)\n"
        "  --sample-rate HZ    activate the plugin at this rate instead of the WAV's\n");
}

Options parseArgs(int argc, char** argv) {
    Options opt;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto next = [&](const char* flag) -> std::string {
            if (i + 1 >= argc) throw std::runtime_error(std::string(flag) + " needs a value");
            return argv[++i];
        };
        if (a == "--plugin") opt.pluginPath = next("--plugin");
        else if (a == "--in") opt.inPath = next("--in");
        else if (a == "--out") opt.outPath = next("--out");
        else if (a == "--list-params") opt.listParams = true;
        else if (a == "--sample-rate") opt.sampleRate = std::stod(next("--sample-rate"));
        else if (a == "--param") {
            std::string kv = next("--param");
            size_t eq = kv.find('=');
            if (eq == std::string::npos || eq == 0)
                throw std::runtime_error("--param expects KEY=VALUE, got: " + kv);
            opt.params.emplace_back(kv.substr(0, eq), std::stod(kv.substr(eq + 1)));
        } else if (a == "--help" || a == "-h") {
            usage();
            std::exit(0);
        } else {
            throw std::runtime_error("unknown argument: " + a);
        }
    }
    if (opt.pluginPath.empty()) throw std::runtime_error("--plugin is required");
    if (!opt.listParams && (opt.inPath.empty() || opt.outPath.empty()))
        throw std::runtime_error("--in and --out are required unless --list-params is given");
    return opt;
}

// ---------------------------------------------------------------------------
// Minimal host
// ---------------------------------------------------------------------------

const void* hostGetExtension(const clap_host_t* host, const char* extensionId);
void hostRequestRestart(const clap_host_t*) {}
void hostRequestProcess(const clap_host_t*) {}
void hostRequestCallback(const clap_host_t*) {}

void hostLog(const clap_host_t*, clap_log_severity severity, const char* msg) {
    const char* level = severity >= CLAP_LOG_ERROR ? "error" : "info";
    std::fprintf(stderr, "plugin log [%s]: %s\n", level, msg);
}
const clap_host_log_t g_hostLog = { hostLog };

// Single-threaded offline host: the one thread is both the main thread and
// the audio thread, so both checks report true.
bool hostIsMainThread(const clap_host_t*) { return true; }
bool hostIsAudioThread(const clap_host_t*) { return true; }
const clap_host_thread_check_t g_hostThreadCheck = { hostIsMainThread, hostIsAudioThread };

const void* hostGetExtension(const clap_host_t*, const char* extensionId) {
    if (std::strcmp(extensionId, CLAP_EXT_LOG) == 0) return &g_hostLog;
    if (std::strcmp(extensionId, CLAP_EXT_THREAD_CHECK) == 0) return &g_hostThreadCheck;
    return nullptr;
}

const clap_host_t g_host = {
    CLAP_VERSION,
    nullptr,
    "clap-render",
    "Bombest Audio",
    "https://github.com/Bombest-Audio/ep_133_sample_tool",
    "0.1.0",
    hostGetExtension,
    hostRequestRestart,
    hostRequestProcess,
    hostRequestCallback,
};

// ---------------------------------------------------------------------------
// Event lists: an input list backed by a vector of param events, and an
// output list that discards everything.
// ---------------------------------------------------------------------------

struct InEventList {
    std::vector<clap_event_param_value_t> events;
    clap_input_events_t api;

    InEventList() {
        api.ctx = this;
        api.size = [](const clap_input_events_t* list) -> uint32_t {
            return (uint32_t)((const InEventList*)list->ctx)->events.size();
        };
        api.get = [](const clap_input_events_t* list, uint32_t index) -> const clap_event_header_t* {
            return &((const InEventList*)list->ctx)->events[index].header;
        };
    }
};

const clap_output_events_t g_outEvents = {
    nullptr,
    [](const clap_output_events_t*, const clap_event_header_t*) -> bool { return true; },
};

// ---------------------------------------------------------------------------
// Plugin loading
// ---------------------------------------------------------------------------

struct LoadedPlugin {
    void* dso = nullptr;
    const clap_plugin_entry_t* entry = nullptr;
    const clap_plugin_t* plugin = nullptr;

    ~LoadedPlugin() {
        if (plugin != nullptr) plugin->destroy(plugin);
        if (entry != nullptr) entry->deinit();
        if (dso != nullptr) dlclose(dso);
    }
};

bool isDirectory(const std::string& path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

// On macOS a .clap is a bundle directory; the loadable Mach-O lives in
// Contents/MacOS/. On Linux (and for pre-extracted binaries) the .clap path
// itself is the shared object.
std::string resolveBinaryPath(const std::string& pluginPath) {
    if (!isDirectory(pluginPath)) return pluginPath;
    std::string macosDir = pluginPath + "/Contents/MacOS";
    DIR* dir = opendir(macosDir.c_str());
    if (dir == nullptr)
        throw std::runtime_error("no Contents/MacOS inside bundle: " + pluginPath);
    std::string found;
    while (dirent* e = readdir(dir)) {
        if (e->d_name[0] == '.') continue;
        std::string candidate = macosDir + "/" + e->d_name;
        struct stat st{};
        if (stat(candidate.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
            found = candidate;
            break;
        }
    }
    closedir(dir);
    if (found.empty())
        throw std::runtime_error("no binary found in " + macosDir);
    return found;
}

void loadPlugin(LoadedPlugin& lp, const std::string& pluginPath) {
    std::string binary = resolveBinaryPath(pluginPath);

    lp.dso = dlopen(binary.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (lp.dso == nullptr)
        throw std::runtime_error("dlopen failed: " + std::string(dlerror()));

    auto* entry = (const clap_plugin_entry_t*)dlsym(lp.dso, "clap_entry");
    if (entry == nullptr)
        throw std::runtime_error("no clap_entry symbol in " + binary);
    if (!entry->init(pluginPath.c_str()))
        throw std::runtime_error("clap_entry.init failed for " + pluginPath);
    lp.entry = entry;

    auto* factory = (const clap_plugin_factory_t*)entry->get_factory(CLAP_PLUGIN_FACTORY_ID);
    if (factory == nullptr)
        throw std::runtime_error("plugin exposes no clap.plugin-factory");

    uint32_t count = factory->get_plugin_count(factory);
    if (count == 0) throw std::runtime_error("plugin factory reports zero plugins");

    // Prefer the first plugin declaring the audio-effect feature; fall back
    // to index 0 so single-plugin binaries with sparse features still load.
    const clap_plugin_descriptor_t* chosen = nullptr;
    for (uint32_t i = 0; i < count && chosen == nullptr; ++i) {
        const clap_plugin_descriptor_t* desc = factory->get_plugin_descriptor(factory, i);
        if (desc == nullptr || desc->features == nullptr) continue;
        for (const char* const* f = desc->features; *f != nullptr; ++f)
            if (std::strcmp(*f, CLAP_PLUGIN_FEATURE_AUDIO_EFFECT) == 0) {
                chosen = desc;
                break;
            }
    }
    if (chosen == nullptr) chosen = factory->get_plugin_descriptor(factory, 0);
    if (chosen == nullptr) throw std::runtime_error("cannot read plugin descriptor");

    std::fprintf(stderr, "plugin: %s (%s)\n", chosen->name, chosen->id);

    lp.plugin = factory->create_plugin(factory, &g_host, chosen->id);
    if (lp.plugin == nullptr)
        throw std::runtime_error("create_plugin failed for " + std::string(chosen->id));
    if (!lp.plugin->init(lp.plugin)) {
        lp.plugin->destroy(lp.plugin);
        lp.plugin = nullptr;
        throw std::runtime_error("plugin init() failed");
    }
}

// ---------------------------------------------------------------------------
// Parameters
// ---------------------------------------------------------------------------

std::vector<clap_param_info_t> collectParams(const clap_plugin_t* plugin) {
    std::vector<clap_param_info_t> out;
    auto* params = (const clap_plugin_params_t*)plugin->get_extension(plugin, CLAP_EXT_PARAMS);
    if (params == nullptr) return out;
    uint32_t count = params->count(plugin);
    for (uint32_t i = 0; i < count; ++i) {
        clap_param_info_t info{};
        if (params->get_info(plugin, i, &info)) out.push_back(info);
    }
    return out;
}

bool isNumericKey(const std::string& s) {
    return !s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isdigit(c); });
}

std::vector<clap_event_param_value_t> resolveParamEvents(
    const std::vector<clap_param_info_t>& infos,
    const std::vector<std::pair<std::string, double>>& requested) {
    std::vector<clap_event_param_value_t> events;
    for (const auto& [key, value] : requested) {
        const clap_param_info_t* match = nullptr;
        if (isNumericKey(key)) {
            clap_id id = (clap_id)std::stoul(key);
            for (const auto& info : infos)
                if (info.id == id) { match = &info; break; }
        } else {
            for (const auto& info : infos)
                if (key == info.name) { match = &info; break; }
        }
        if (match == nullptr)
            throw std::runtime_error("unknown parameter: " + key + " (use --list-params)");
        if (value < match->min_value || value > match->max_value)
            throw std::runtime_error("value " + std::to_string(value) + " out of range [" +
                                     std::to_string(match->min_value) + ", " +
                                     std::to_string(match->max_value) + "] for parameter " + key);

        clap_event_param_value_t ev{};
        ev.header.size = sizeof(ev);
        ev.header.time = 0;
        ev.header.space_id = CLAP_CORE_EVENT_SPACE_ID;
        ev.header.type = CLAP_EVENT_PARAM_VALUE;
        ev.header.flags = 0;
        ev.param_id = match->id;
        ev.cookie = match->cookie;
        ev.note_id = -1;
        ev.port_index = -1;
        ev.channel = -1;
        ev.key = -1;
        ev.value = value;
        events.push_back(ev);
    }
    return events;
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

struct PortConfig {
    std::vector<uint32_t> inputChannels;  // channel count per input port
    std::vector<uint32_t> outputChannels; // channel count per output port
};

PortConfig queryPorts(const clap_plugin_t* plugin, uint16_t wavChannels) {
    PortConfig cfg;
    auto* ap = (const clap_plugin_audio_ports_t*)plugin->get_extension(plugin, CLAP_EXT_AUDIO_PORTS);
    if (ap != nullptr) {
        uint32_t nin = ap->count(plugin, true);
        uint32_t nout = ap->count(plugin, false);
        for (uint32_t i = 0; i < nin; ++i) {
            clap_audio_port_info_t info{};
            cfg.inputChannels.push_back(ap->get(plugin, i, true, &info) ? info.channel_count : 2);
        }
        for (uint32_t i = 0; i < nout; ++i) {
            clap_audio_port_info_t info{};
            cfg.outputChannels.push_back(ap->get(plugin, i, false, &info) ? info.channel_count : 2);
        }
    }
    // No extension or no declared ports: assume a simple effect matching the WAV.
    if (cfg.inputChannels.empty()) cfg.inputChannels.push_back(wavChannels);
    if (cfg.outputChannels.empty()) cfg.outputChannels.push_back(wavChannels);
    return cfg;
}

wav::AudioFile renderFile(const clap_plugin_t* plugin, const wav::AudioFile& in,
                          double sampleRate,
                          std::vector<clap_event_param_value_t> paramEvents) {
    constexpr uint32_t kBlock = 512;

    PortConfig cfg = queryPorts(plugin, in.channels);

    if (!plugin->activate(plugin, sampleRate, 1, kBlock))
        throw std::runtime_error("plugin activate() failed at " + std::to_string(sampleRate) + " Hz");
    if (!plugin->start_processing(plugin)) {
        plugin->deactivate(plugin);
        throw std::runtime_error("plugin start_processing() failed");
    }

    // Allocate one buffer set per declared port. Port 0 carries the WAV;
    // any extra ports (sidechains etc) are fed silence.
    auto makeBuffers = [](const std::vector<uint32_t>& portChannels,
                          std::vector<std::vector<std::vector<float>>>& storage,
                          std::vector<std::vector<float*>>& ptrs,
                          std::vector<clap_audio_buffer_t>& bufs) {
        storage.resize(portChannels.size());
        ptrs.resize(portChannels.size());
        bufs.resize(portChannels.size());
        for (size_t p = 0; p < portChannels.size(); ++p) {
            storage[p].assign(portChannels[p], std::vector<float>(kBlock, 0.0f));
            ptrs[p].resize(portChannels[p]);
            for (uint32_t c = 0; c < portChannels[p]; ++c) ptrs[p][c] = storage[p][c].data();
            bufs[p] = clap_audio_buffer_t{};
            bufs[p].data32 = ptrs[p].data();
            bufs[p].channel_count = portChannels[p];
        }
    };

    std::vector<std::vector<std::vector<float>>> inStorage, outStorage;
    std::vector<std::vector<float*>> inPtrs, outPtrs;
    std::vector<clap_audio_buffer_t> inBufs, outBufs;
    makeBuffers(cfg.inputChannels, inStorage, inPtrs, inBufs);
    makeBuffers(cfg.outputChannels, outStorage, outPtrs, outBufs);

    wav::AudioFile out;
    out.sampleRate = (uint32_t)sampleRate;
    out.channels = in.channels;
    out.samples.assign(in.channels, {});

    InEventList inEvents;
    size_t totalFrames = in.frameCount();
    int64_t steadyTime = 0;

    for (size_t offset = 0; offset < totalFrames; offset += kBlock) {
        uint32_t frames = (uint32_t)std::min<size_t>(kBlock, totalFrames - offset);

        // Fill main input port; duplicate the last WAV channel when the port
        // declares more channels than the file has (mono into stereo).
        uint32_t mainCh = cfg.inputChannels[0];
        for (uint32_t c = 0; c < mainCh; ++c) {
            uint16_t src = (uint16_t)std::min<uint32_t>(c, in.channels - 1);
            const std::vector<float>& srcData = in.samples[src];
            float* dst = inStorage[0][c].data();
            for (uint32_t i = 0; i < frames; ++i) dst[i] = srcData[offset + i];
            for (uint32_t i = frames; i < kBlock; ++i) dst[i] = 0.0f;
        }

        // Param events land on the first block only.
        inEvents.events = paramEvents;
        paramEvents.clear();

        clap_process_t process{};
        process.steady_time = steadyTime;
        process.frames_count = frames;
        process.transport = nullptr;
        process.audio_inputs = inBufs.data();
        process.audio_inputs_count = (uint32_t)inBufs.size();
        process.audio_outputs = outBufs.data();
        process.audio_outputs_count = (uint32_t)outBufs.size();
        process.in_events = &inEvents.api;
        process.out_events = &g_outEvents;

        clap_process_status status = plugin->process(plugin, &process);
        if (status == CLAP_PROCESS_ERROR) {
            plugin->stop_processing(plugin);
            plugin->deactivate(plugin);
            throw std::runtime_error("plugin process() returned an error at frame " +
                                     std::to_string(offset));
        }

        // Collect output from port 0; fold extra plugin channels away by
        // taking the first `in.channels` channels.
        uint32_t outCh = cfg.outputChannels[0];
        for (uint16_t c = 0; c < in.channels; ++c) {
            uint32_t src = std::min<uint32_t>(c, outCh - 1);
            const float* data = outStorage[0][src].data();
            out.samples[c].insert(out.samples[c].end(), data, data + frames);
        }

        steadyTime += frames;
    }

    plugin->stop_processing(plugin);
    plugin->deactivate(plugin);
    return out;
}

} // namespace

int main(int argc, char** argv) {
    try {
        Options opt = parseArgs(argc, argv);

        LoadedPlugin lp;
        loadPlugin(lp, opt.pluginPath);

        std::vector<clap_param_info_t> infos = collectParams(lp.plugin);

        if (opt.listParams) {
            for (const auto& info : infos)
                std::printf("%u\t%s\t%g\t%g\t%g\n", info.id, info.name,
                            info.min_value, info.max_value, info.default_value);
            return 0;
        }

        std::vector<clap_event_param_value_t> events = resolveParamEvents(infos, opt.params);

        wav::AudioFile in = wav::read(opt.inPath);
        double rate = opt.sampleRate > 0.0 ? opt.sampleRate : (double)in.sampleRate;
        if (opt.sampleRate > 0.0 && (uint32_t)opt.sampleRate != in.sampleRate)
            std::fprintf(stderr,
                         "note: activating at %g Hz but the input WAV is %u Hz; "
                         "no resampling is performed\n",
                         opt.sampleRate, in.sampleRate);

        wav::AudioFile out = renderFile(lp.plugin, in, rate, std::move(events));
        wav::write(opt.outPath, out);
        std::fprintf(stderr, "wrote %s (%zu frames, %u ch, %u Hz)\n", opt.outPath.c_str(),
                     out.frameCount(), out.channels, out.sampleRate);
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "clap-render: %s\n", e.what());
        return 1;
    }
}
