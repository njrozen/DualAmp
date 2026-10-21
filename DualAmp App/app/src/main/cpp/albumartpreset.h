#pragma once

// Album art presets: MilkDrop presets built around one or more album covers.
//
// Shared by Mixxx's visualizer and DualAmp; keep both copies identical.
// Plain C++17 without Qt or OpenGL. The caller decodes the cover, saves it as
// an image file named textureName(...) in a folder on projectM's texture
// search path, and loads the text from buildPreset() with
// projectm_load_preset_data(). projectM rescans the texture folders on every
// preset load, so a new cover file is picked up without resetting textures.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace albumart {

struct Rgb {
    float r = 0.f;
    float g = 0.f;
    float b = 0.f;
};

/// Colours picked from a cover, used for the glow, the waveform and the tint.
struct Palette {
    Rgb main = {0.6f, 0.6f, 0.7f};   // most common vivid colour
    Rgb accent = {0.9f, 0.9f, 1.0f}; // a second, different colour (or a light main)
};

/// `rgba`: 8-bit RGBA pixels, `stride` bytes per row.
Palette extractPalette(const unsigned char* rgba, int width, int height, int stride);

/// Texture base name for a cover ("albumart_<hash>"), from any bytes that identify
/// it (the encoded image or its pixels). Save the cover under this name plus ".png".
std::string textureName(const void* data, std::size_t size);

/// How the covers are shown.
enum class Style {
    Cover = 0,    // the cover in the middle, pulsing, with glow and trails
    Melt,         // the cover fills the screen and melts with the music
    Kaleidoscope, // mirrored slices of the cover
    Tunnel,       // the cover lines a tunnel flying towards you
    LivingPhoto,  // the picture itself moves: depth, outlines, bands, colour split, glitches
    Count
};
constexpr int kStyleCount = static_cast<int>(Style::Count);
const char* styleName(Style style);

/// The adjustable elements of an album art preset.
enum class Param {
    Size = 0,   // cover size (Cover), zoom (the others)
    BassPulse,  // how much the bass pumps the cover
    Warp,       // how much the mids bend and melt the picture
    Spin,       // turning speed
    Trails,     // how long old frames linger
    Glow,       // palette-coloured glow on the beat
    Sparkle,    // cover edges flashing with the treble
    Waveform,   // palette-coloured waveform
    Background, // the cover blurred behind everything
    ColorDrift, // colours slowly turning round the colour wheel
    Slices,     // kaleidoscope slices
    // Living photo only (new parameters go at the end: apps store them by position)
    Depth,      // bright parts bulge forward on the bass, dark parts sink
    Outlines,   // ripples running along the picture's own edges, with the mids
    Bands,      // bottom of the picture moves with the bass, middle mids, top treble
    ColorSplit, // red, green and blue pulling apart on hi-hats
    Glitch,     // strips of the picture jumping sideways on big beats
    Breathe,    // slow zoom and drift between beats
    Count
};
constexpr int kParamCount = static_cast<int>(Param::Count);

struct ParamInfo {
    const char* id;    // stable key for settings files
    const char* label; // English label for the UI
    float min;
    float max;
    float defaultValue;
};
const ParamInfo& paramInfo(Param param);

struct Params {
    Style style = Style::Cover;
    float values[kParamCount];
    /// Frequency detail: false = bass, mid and treble (any projectM), true = 16
    /// bands, which needs projectM with the fine band patch (the _fb/_fa uniforms).
    bool fineBands = false;

    Params();
    float get(Param param) const {
        return values[static_cast<int>(param)];
    }
    void set(Param param, float value);
};

/// One cover in the preset. Several layers are blended by their weights
/// (for example one per playing deck, weighted by volume).
struct Layer {
    std::string texture; // textureName() of the saved cover
    Palette palette;
    float weight = 1.f;
};

/// A complete .milk preset. Up to four layers are used, the heaviest first.
std::string buildPreset(std::vector<Layer> layers, const Params& params);

} // namespace albumart
