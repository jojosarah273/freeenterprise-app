// ff4dump - a headless Snes9x driver for Free Enterprise work.
//
// Why this exists: the tracker, and the completion metadata planned for it, are
// statements about console memory (WRAM at $7E1000-$17FF, and SRAM). Testing them
// by playing to Toroia and looking at a screen is slow and unrepeatable. This tool
// loads a ROM - and optionally a save state or battery save - runs frames, and
// prints what those addresses actually contain.
//
// It is also the front half of the desktop client: the same core, the same
// save-state and battery-save handling, without a window.
//
// Build (from the repo root):
//     cmake -S tools/ff4dump -B tools/ff4dump/build -G Ninja
//     cmake --build tools/ff4dump/build
//
// Usage:
//     ff4dump --rom game.smc [--state game.state] [--sram game.srm]
//             [--frames N] [--report] [--dump-wram out.bin] [--dump-sram out.bin]
//             [--save-state out.state]
//
// Everything it reads and prints comes from the repository's own notes on the FF4
// layout: the item bag at $1440, FE's tracker flags at $1500, the treasure switch
// array at $12A0, the run statistics at $1578, and FE's own SRAM records at
// $70:7080 (key item locations) and $70:70F2 (ever completed).

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "libretro.h"

// ---------------------------------------------------------------- frontend glue

static unsigned g_frames = 0;
static const void *g_frame_data = nullptr;
static unsigned g_frame_w = 0, g_frame_h = 0;
static size_t g_frame_pitch = 0;

static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    // The core is chatty at info level; only surface warnings and errors.
    if (level == RETRO_LOG_DEBUG || level == RETRO_LOG_INFO) return;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[core] ");
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Whatever the core asks for, accept: the frame is never displayed.
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            // Answering false makes the core fall back to its own defaults,
            // which is what the app runs with (no core options are set there).
            return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) ((struct retro_log_callback *)data)->log = log_cb;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *(int *)data = 3;  // video and audio both "enabled"
            return true;
        default:
            // Directories, input descriptors, geometry, core options: all optional.
            return false;
    }
}

static void video_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    g_frames++;
    if (data) {
        g_frame_data = data;
        g_frame_w = width;
        g_frame_h = height;
        g_frame_pitch = pitch;
    }
}

static void audio_cb(int16_t, int16_t) {}
static size_t audio_batch_cb(const int16_t *, size_t frames) { return frames; }
static void input_poll_cb(void) {}
static int16_t input_state_cb(unsigned, unsigned, unsigned, unsigned) {
    return 0;  // no input: states are loaded rather than played to
}

// -------------------------------------------------------------- address decoding

// FE's key item table, from FreeEnt/scripts/tracker.f4c (Tracker__KeyItemCodes).
// Index i owns bit i of Tracker__KeyItemFoundFlags at $7E1500.
struct TrackerItem {
    const char *name;
    uint8_t code;
};
static const TrackerItem kTrackerItems[] = {
    {"package",        0xEE}, {"sandruby",      0xF0}, {"legend_sword",  0x19},
    {"baron_key",      0xEF}, {"twinharp",      0xF4}, {"earth_crystal", 0xF1},
    {"magma_key",      0xF2}, {"tower_key",     0xFA}, {"hook",          0xFC},
    {"luca_key",       0xF3}, {"dark_crystal",  0xF5}, {"rat_tail",      0xF6},
    {"adamant",        0xF7}, {"pan",           0xF8}, {"spoon",         0x3E},
    {"pink_tail",      0xF9}, {"crystal",       0xC8},
};
static const int kTrackerItemCount = (int)(sizeof(kTrackerItems) / sizeof(kTrackerItems[0]));

// Item ids as FE names them (default.consts, consts(item)), for the bag dump.
static const char *item_name(uint8_t id) {
    switch (id) {
        case 0x19: return "Legend (sword)";
        case 0x3E: return "Spoon (weapon)";
        case 0xC8: return "Crystal";
        case 0xE7: return "Imp";
        case 0xE8: return "BombSummon";
        case 0xE9: return "Cockatrice";
        case 0xEA: return "Mage";
        case 0xEB: return "Carrot";
        case 0xEC: return "Pass";
        case 0xED: return "Whistle";
        case 0xEE: return "Package";
        case 0xEF: return "Baron Key";
        case 0xF0: return "SandRuby";
        case 0xF1: return "Earth Crystal";
        case 0xF2: return "Magma Key";
        case 0xF3: return "Luca Key";
        case 0xF4: return "TwinHarp";
        case 0xF5: return "Dark Crystal";
        case 0xF6: return "Rat Tail";
        case 0xF7: return "Adamant";
        case 0xF8: return "Pan";
        case 0xF9: return "Pink Tail";
        case 0xFA: return "Tower Key";
        case 0xFC: return "Hook";
        default: return nullptr;
    }
}

static std::string hex2(unsigned v) {
    char buf[8];
    snprintf(buf, sizeof(buf), "%02X", v);
    return buf;
}

static int popcount_bit(const uint8_t *base, int bit) {
    return (base[bit >> 3] >> (bit & 7)) & 1;
}

// The live game state lives at $7E1000-$17FF and mirrors a save slot.
static const int kLiveBase = 0x1000;
static const int kLiveSize = 0x800;

static void report(const uint8_t *wram, size_t wram_size, const uint8_t *sram, size_t sram_size) {
    if (wram_size < (size_t)(kLiveBase + kLiveSize)) {
        printf("WRAM is only %zu bytes; cannot read the live game state\n", wram_size);
        return;
    }
    const uint8_t *s = wram + kLiveBase;

    printf("\n=== WRAM $7E1000-$17FF (live game state) ===\n");

    // Item bag: 48 entries of (item id, quantity) at +0x440.
    printf("\n  item bag ($1440, 48 x 2 bytes)\n");
    int occupied = 0, key_items = 0;
    for (int i = 0; i < 48; i++) {
        uint8_t id = s[0x440 + i * 2];
        uint8_t qty = s[0x440 + i * 2 + 1];
        if (qty == 0) continue;
        occupied++;
        const char *name = item_name(id);
        printf("    [%2d] id $%s x%-3u %s\n", i, hex2(id).c_str(), qty,
               name ? name : "(unlisted)");
        if (name) key_items++;
    }
    printf("    -> %d occupied slot(s), %d of them key items\n", occupied, key_items);

    // FE's own key item tracker: found and used bit arrays at +0x500 and +0x503.
    printf("\n  engine tracker (Tracker__KeyItemFoundFlags $1500, UsedFlags $1503)\n");
    const uint8_t *found = s + 0x500;
    const uint8_t *used = s + 0x503;
    int found_count = 0;
    for (int i = 0; i < kTrackerItemCount; i++) {
        int f = popcount_bit(found, i), u = popcount_bit(used, i);
        found_count += f;
        printf("    %2d %-14s found=%d used=%d (item $%s)\n",
               i, kTrackerItems[i].name, f, u, hex2(kTrackerItems[i].code).c_str());
    }
    printf("    -> %d of %d tracked key items found\n", found_count, kTrackerItemCount);

    // Treasure switches: the same array the app's CheckDb counts, $12A0 for 64 bytes.
    const uint8_t *switches = s + 0x2A0;
    int set_bits = 0, highest = -1;
    for (int bit = 0; bit < 512; bit++) {
        if (popcount_bit(switches, bit)) { set_bits++; highest = bit; }
    }
    printf("\n  treasure switches ($12A0, 64 bytes / 512 bits)\n");
    printf("    %d bit(s) set, highest bit %d\n", set_bits, highest);

    // Run statistics, written by the engine at the ending and during play.
    printf("\n  run statistics ($1578)\n");
    printf("    key items  %u\n", s[0x578]);
    printf("    characters %u\n", s[0x579]);
    printf("    treasures  %u (high byte %u)\n", s[0x57A], s[0x57B]);
    printf("    bosses     %u\n", s[0x57C]);
    printf("    end time   %02u:%02u:%02u:%02u (frames:sec:min:hour)\n",
           s[0x57D], s[0x57E], s[0x57F], s[0x580]);

    // Save slots, as the app decodes them (4 slots of 0x800 at the start of SRAM).
    printf("\n  save slots (SRAM, 0x800 each)\n");
    for (int slot = 0; slot < 4 && (size_t)((slot + 1) * 0x800) <= sram_size; slot++) {
        const uint8_t *sl = sram + slot * 0x800;
        uint8_t valid = sl[0x7FB];
        unsigned checksum = sl[0x7FC] | (sl[0x7FD] << 8);
        printf("    slot %d: valid=$%s checksum=$%04X time=%02u:%02u:%02u\n",
               slot, hex2(valid).c_str(), checksum,
               sl[0x6A6], sl[0x6A5], sl[0x6A4]);
    }

    // FE's persistent records, addressed as $70:xxxx in its own source. Whether
    // those are direct SRAM offsets or mirrored depends on the chip size, which is
    // why the size is printed here.
    printf("\n  engine SRAM records\n");
    printf("    SRAM size reported by the core: %zu bytes (0x%zX)\n", sram_size, sram_size);
    const size_t kKeyItemLocations = 0x7080;  // Tracker__KeyItemLocations, 2 bytes/item
    const size_t kEverCompleted = 0x70F2;     // Stats_EverCompleted
    if (sram_size > kEverCompleted) {
        printf("    ever completed byte ($70F2): $%s\n", hex2(sram[kEverCompleted]).c_str());
    } else {
        printf("    $70F2 is beyond the chip; the address must be mirrored\n");
    }
    if (sram_size >= kKeyItemLocations + kTrackerItemCount * 2) {
        printf("    key item source slots ($7080, 2 bytes per tracked item)\n");
        for (int i = 0; i < kTrackerItemCount; i++) {
            unsigned lo = sram[kKeyItemLocations + i * 2];
            unsigned hi = sram[kKeyItemLocations + i * 2 + 1];
            printf("      %2d %-14s slot $%04X\n", i, kTrackerItems[i].name, (hi << 8) | lo);
        }
    }
}

// ------------------------------------------------------------------------ main

static std::vector<uint8_t> read_file(const char *path, bool *ok) {
    std::vector<uint8_t> out;
    FILE *f = fopen(path, "rb");
    if (!f) { *ok = false; return out; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n > 0) {
        out.resize((size_t)n);
        *ok = fread(out.data(), 1, (size_t)n, f) == (size_t)n;
    } else {
        *ok = false;
    }
    fclose(f);
    return out;
}

static bool write_file(const char *path, const void *data, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, size, f) == size;
    fclose(f);
    return ok;
}

// A single "set this byte" request, applied after the frames and before the
// report. It exists so the decoder can be checked without a playthrough: set the
// engine's found-flags byte and see whether the report agrees.
struct Poke {
    bool sram;
    unsigned addr;
    unsigned value;
};

int main(int argc, char **argv) {
    const char *rom_path = nullptr, *state_path = nullptr, *sram_path = nullptr;
    const char *dump_wram = nullptr, *dump_sram = nullptr, *save_state = nullptr;
    int frames = 0;
    bool do_report = true;
    std::vector<Poke> pokes;

    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        const char *v = (i + 1 < argc) ? argv[i + 1] : nullptr;
        if (a == "--rom" && v) { rom_path = v; i++; }
        else if (a == "--state" && v) { state_path = v; i++; }
        else if (a == "--sram" && v) { sram_path = v; i++; }
        else if (a == "--frames" && v) { frames = atoi(v); i++; }
        else if (a == "--dump-wram" && v) { dump_wram = v; i++; }
        else if (a == "--dump-sram" && v) { dump_sram = v; i++; }
        else if (a == "--save-state" && v) { save_state = v; i++; }
        else if (a == "--no-report") { do_report = false; }
        else if (a == "--poke" && v) {
            // --poke wram:1500=04   or   --poke sram:70f2=01
            char seg[8] = {0};
            unsigned addr = 0, value = 0;
            if (sscanf(v, "%7[^:]:%x=%x", seg, &addr, &value) != 3) {
                fprintf(stderr, "bad poke (expected wram:ADDR=VAL or sram:ADDR=VAL): %s\n", v);
                return 2;
            }
            Poke p;
            p.sram = (strcmp(seg, "sram") == 0);
            p.addr = addr;
            p.value = value & 0xFF;
            pokes.push_back(p);
            i++;
        }
        else if (a == "--help" || a == "-h") {
            printf("usage: ff4dump --rom ROM [--state STATE] [--sram SRM] [--frames N]\n"
                   "               [--report|--no-report] [--dump-wram FILE]\n"
                   "               [--dump-sram FILE] [--save-state FILE]\n");
            return 0;
        } else {
            fprintf(stderr, "unknown or incomplete argument: %s\n", a.c_str());
            return 2;
        }
    }
    if (!rom_path) {
        fprintf(stderr, "a ROM is required: --rom game.smc\n");
        return 2;
    }

    bool ok = false;
    std::vector<uint8_t> rom = read_file(rom_path, &ok);
    if (!ok || rom.empty()) {
        fprintf(stderr, "could not read ROM: %s\n", rom_path);
        return 1;
    }

    retro_set_environment(env_cb);
    retro_init();
    retro_set_video_refresh(video_cb);
    retro_set_audio_sample(audio_cb);
    retro_set_audio_sample_batch(audio_batch_cb);
    retro_set_input_poll(input_poll_cb);
    retro_set_input_state(input_state_cb);

    retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = rom_path;
    info.data = rom.data();
    info.size = rom.size();

    if (!retro_load_game(&info)) {
        fprintf(stderr, "the core refused the ROM\n");
        retro_deinit();
        return 1;
    }

    uint8_t *sram = (uint8_t *)retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t sram_size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    uint8_t *wram = (uint8_t *)retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
    size_t wram_size = retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);

    printf("loaded %s (%zu bytes)\n", rom_path, rom.size());
    printf("core memory: WRAM %zu bytes, SRAM %zu bytes\n", wram_size, sram_size);

    if (sram_path) {
        std::vector<uint8_t> srm = read_file(sram_path, &ok);
        if (!ok) {
            fprintf(stderr, "could not read battery save: %s\n", sram_path);
        } else if (sram && !srm.empty()) {
            size_t n = srm.size() < sram_size ? srm.size() : sram_size;
            memcpy(sram, srm.data(), n);
            printf("battery save loaded into SRAM (%zu bytes)\n", n);
        }
    }

    if (state_path) {
        std::vector<uint8_t> st = read_file(state_path, &ok);
        if (!ok) {
            fprintf(stderr, "could not read save state: %s\n", state_path);
        } else if (!retro_unserialize(st.data(), st.size())) {
            fprintf(stderr, "the core refused the save state (wrong core version?)\n");
        } else {
            printf("save state loaded (%zu bytes)\n", st.size());
        }
    }

    for (int i = 0; i < frames; i++) retro_run();
    printf("ran %d frame(s); video frames seen: %u (%ux%u)\n",
           frames, g_frames, g_frame_w, g_frame_h);

    // Applied after the frames, so the game cannot overwrite them.
    for (size_t i = 0; i < pokes.size(); i++) {
        const Poke &p = pokes[i];
        uint8_t *base = p.sram ? sram : wram;
        size_t size = p.sram ? sram_size : wram_size;
        if (!base || p.addr >= size) {
            fprintf(stderr, "poke out of range: %s:0x%X\n", p.sram ? "sram" : "wram", p.addr);
            continue;
        }
        base[p.addr] = (uint8_t)p.value;
        printf("poked %s[$%04X] = $%02X\n", p.sram ? "sram" : "wram", p.addr, p.value);
    }

    if (do_report) report(wram, wram_size, sram, sram_size);

    if (dump_wram && wram) write_file(dump_wram, wram, wram_size);
    if (dump_sram && sram) write_file(dump_sram, sram, sram_size);
    if (save_state) {
        size_t n = retro_serialize_size();
        if (n == 0) {
            fprintf(stderr, "the core reports no save-state support\n");
        } else {
            std::vector<uint8_t> buf(n);
            if (retro_serialize(buf.data(), buf.size())) {
                write_file(save_state, buf.data(), buf.size());
                printf("save state written: %s (%zu bytes)\n", save_state, buf.size());
            } else {
                fprintf(stderr, "retro_serialize failed\n");
            }
        }
    }

    retro_unload_game();
    retro_deinit();
    return 0;
}
