#include "albumartpreset.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <sstream>

// Shared by Mixxx's visualizer and DualAmp; keep both copies identical.

namespace albumart {

namespace {

const ParamInfo kParams[kParamCount] = {
        {"Size", "Cover size / zoom", 0.2f, 1.0f, 0.55f},
        {"BassPulse", "Bass pulse", 0.f, 2.f, 1.f},
        {"Warp", "Warp / melt", 0.f, 2.f, 1.f},
        {"Spin", "Spin", 0.f, 2.f, 0.5f},
        {"Trails", "Trails", 0.f, 1.f, 0.6f},
        {"Glow", "Colour glow", 0.f, 2.f, 1.f},
        {"Sparkle", "Treble sparkle", 0.f, 2.f, 1.f},
        {"Waveform", "Waveform", 0.f, 1.f, 0.6f},
        {"Background", "Background art", 0.f, 1.f, 0.5f},
        {"ColorDrift", "Colour drift", 0.f, 1.f, 0.f},
        {"Slices", "Kaleidoscope slices", 2.f, 16.f, 6.f},
        {"Depth", "Depth (living photo)", 0.f, 2.f, 1.f},
        {"Outlines", "Outline ripples (living photo)", 0.f, 2.f, 1.f},
        {"Bands", "Bass / mid / treble bands (living photo)", 0.f, 2.f, 1.f},
        {"ColorSplit", "Colour split (living photo)", 0.f, 2.f, 1.f},
        {"Glitch", "Beat glitches (living photo)", 0.f, 2.f, 0.6f},
        {"Breathe", "Breathing (living photo)", 0.f, 2.f, 1.f},
};

const char* const kStyleNames[kStyleCount] = {
        "Cover",
        "Melt",
        "Kaleidoscope",
        "Tunnel",
        "Living photo",
};

constexpr std::size_t kMaxLayers = 4;

// Numbers in the preset always use a '.' whatever the C locale says.
std::string num(double value) {
    long long scaled = std::llround(value * 10000.0);
    const bool negative = scaled < 0;
    if (negative) {
        scaled = -scaled;
    }
    char buffer[40];
    std::snprintf(buffer, sizeof(buffer), "%s%lld.%04lld", negative ? "-" : "", scaled / 10000, scaled % 10000);
    return buffer;
}

std::string vec3(const Rgb& c) {
    return "float3(" + num(c.r) + ", " + num(c.g) + ", " + num(c.b) + ")";
}

float maxOf(const Rgb& c) {
    return std::max(c.r, std::max(c.g, c.b));
}

float minOf(const Rgb& c) {
    return std::min(c.r, std::min(c.g, c.b));
}

float saturation(const Rgb& c) {
    const float mx = maxOf(c);
    return mx > 0.f ? (mx - minOf(c)) / mx : 0.f;
}

float distance(const Rgb& a, const Rgb& b) {
    const float dr = a.r - b.r;
    const float dg = a.g - b.g;
    const float db = a.b - b.b;
    return std::sqrt(dr * dr + dg * dg + db * db);
}

/// Full brightness for glows and waves; nearly grey colours become a light grey.
Rgb vivid(Rgb c) {
    if (saturation(c) < 0.15f) {
        const float grey = std::max(0.75f, (c.r + c.g + c.b) / 3.f);
        return {grey, grey, grey};
    }
    const float scale = 1.f / std::max(maxOf(c), 0.05f);
    return {c.r * scale, c.g * scale, c.b * scale};
}

Rgb mixed(const Rgb& a, const Rgb& b, float t) {
    return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t};
}

/// Writes `code` as numbered preset lines: prefix1=`line, prefix2=`line ...
void writeCode(std::ostringstream& out, const char* prefix, const std::string& code, bool shader) {
    std::istringstream lines(code);
    std::string line;
    int index = 1;
    while (std::getline(lines, line)) {
        out << prefix << index++ << '=' << (shader ? "`" : "") << line << '\n';
    }
}

/// Expression for the blended covers at texture coordinate `uv` (a float2 expression).
std::string sampleArt(const std::vector<Layer>& layers, const std::string& uv) {
    if (layers.empty()) {
        return "float3(0.12, 0.12, 0.16)";
    }
    std::string expression = "(";
    for (std::size_t i = 0; i < layers.size(); ++i) {
        if (i > 0) {
            expression += " + ";
        }
        expression += "tex2D(sampler_fc_" + layers[i].texture + ", " + uv + ").xyz";
        if (layers.size() > 1) {
            expression += " * " + num(layers[i].weight);
        }
    }
    return expression + ")";
}

/// Shader lines declaring `name`: how loud the music is (around 1.0) at
/// `position` (0 = lowest .. 1 = highest frequency). With fine bands it reads
/// 16 bands, otherwise it blends bass, mid and treble; both mix the current
/// and the attenuated loudness.
std::string bandAt(const Params& params, const std::string& name, const std::string& position) {
    std::ostringstream s;
    if (params.fineBands) {
        s << "    float " << name << "i = saturate(" << position << ") * 15.0;\n"
          << "    float " << name << " = 0.0;\n";
        for (int group = 0; group < 4; ++group) {
            s << "    " << name << " += dot(saturate(1.0 - abs(" << name << "i - float4("
              << group * 4 << ".0, " << group * 4 + 1 << ".0, " << group * 4 + 2 << ".0, " << group * 4 + 3
              << ".0))), _fa" << group << " * 0.6 + _fb" << group << " * 0.4);\n";
        }
    } else {
        s << "    float " << name << "i = saturate(" << position << ") * 2.0;\n"
          << "    float " << name << " = dot(saturate(1.0 - abs(" << name << "i - float3(0.0, 1.0, 2.0))), "
          << "float3(bass_att, mid_att, treb_att) * 0.6 + float3(bass, mid, treb) * 0.4);\n";
    }
    return s.str();
}

/// Shader lines that place the cover: they set `auv` (cover texture coordinate),
/// `mask` (where the crisp cover shows) and `shade` (brightness), from the
/// screen coordinate `screenUv`.
std::string artMapping(const Params& params, const std::string& screenUv) {
    const std::string size = num(params.get(Param::Size));
    std::ostringstream s;
    // p: centred, the shorter screen side spans -0.5..0.5 (square pixels).
    // pf: the same with the longer side spanning -0.5..0.5 (fills the screen).
    s << "    float2 p = (" << screenUv << " - 0.5) * texsize.xy / min(texsize.x, texsize.y);\n";
    s << "    float2 pf = (" << screenUv << " - 0.5) * texsize.xy / max(texsize.x, texsize.y);\n";
    s << "    float2 a = p;\n";
    s << "    float mask = 1.0;\n";
    s << "    float shade = 1.0;\n";
    switch (params.style) {
    case Style::Cover:
        s << "    float cs = cos(q5);\n";
        s << "    float sn = sin(q5);\n";
        s << "    a = float2(cs * p.x - sn * p.y, sn * p.x + cs * p.y);\n";
        s << "    a = a / (" << size << " * (1.0 + 0.15 * q1));\n";
        s << "    float2 edge = saturate((0.5 - abs(a)) * 80.0);\n";
        s << "    mask = edge.x * edge.y;\n";
        s << "    float2 auv = a + 0.5;\n";
        break;
    case Style::LivingPhoto: {
        // Like Cover but bigger, breathing and drifting; the composite then
        // moves the picture by its own content (livingPhoto()).
        const std::string breathe = num(params.get(Param::Breathe));
        s << "    float cs = cos(q5);\n";
        s << "    float sn = sin(q5);\n";
        s << "    a = float2(cs * p.x - sn * p.y, sn * p.x + cs * p.y);\n";
        s << "    float br = 1.0 + 0.035 * " << breathe << " * sin(time * 0.6) + 0.06 * q1;\n";
        s << "    a = a / ((0.4 + 0.6 * " << size << ") * br);\n";
        s << "    a += 0.012 * " << breathe << " * float2(sin(time * 0.31), cos(time * 0.23));\n";
        s << "    float2 edge = saturate((0.5 - abs(a)) * 60.0);\n";
        s << "    mask = edge.x * edge.y;\n";
        s << "    float2 auv = a + 0.5;\n";
        break;
    }
    case Style::Melt:
        s << "    float cs = cos(q4 * 0.3);\n";
        s << "    float sn = sin(q4 * 0.3);\n";
        s << "    a = float2(cs * pf.x - sn * pf.y, sn * pf.x + cs * pf.y);\n";
        s << "    a = a * (1.0 - 0.08 * q1) / (0.45 + 0.6 * " << size << ");\n";
        s << "    float2 auv = 1.0 - abs(1.0 - frac((a + 0.5) * 0.5) * 2.0);\n";
        break;
    case Style::Kaleidoscope:
        // Each ring pulses with its own band: bass in the middle, highs outside.
        s << bandAt(params, "kb", "length(p) * 1.4");
        s << "    float r = length(p) * (1.0 - 0.12 * q1 - 0.08 * min(max(kb - 1.0, 0.0), 1.5)) / (0.3 + " << size << ");\n";
        s << "    float slice = 6.2831853 / " << num(std::floor(params.get(Param::Slices) + 0.5f)) << ";\n";
        s << "    float an = atan2(p.y, p.x) + q4;\n";
        s << "    float f = abs(frac(an / slice * 0.5) * 2.0 - 1.0) * slice;\n";
        s << "    a = r * float2(cos(f), sin(f));\n";
        s << "    float2 auv = 1.0 - abs(1.0 - frac((a + 0.5) * 0.5) * 2.0);\n";
        break;
    case Style::Tunnel:
    default:
        s << "    float r = length(p);\n";
        s << "    float an = atan2(p.y, p.x) * 0.15915494 + q4 * 0.05;\n";
        s << "    a = float2(an * 2.0, 0.25 * (0.5 + " << size << ") / (r + 0.03) + q7);\n";
        s << "    float2 auv = 1.0 - abs(1.0 - frac(a * 0.5) * 2.0);\n";
        // Each depth lights up with its own band: bass close by, highs far away.
        s << bandAt(params, "tb", "1.0 - saturate(r * 1.6)");
        s << "    shade = saturate(r * 4.0) * (0.55 + 0.45 * min(tb, 2.0));\n";
        break;
    }
    return s.str();
}

struct StyleTuning {
    float feed;       // how much of the cover is fed into the trails per frame
    float artOpacity; // crisp cover over the trails
    float zoom;       // trails zooming out on the bass
    float meshWarp;   // MilkDrop mesh warp per unit of Warp
    int waveMode;
};

StyleTuning tuning(Style style) {
    switch (style) {
    case Style::Cover:
        return {0.25f, 1.f, 0.035f, 0.1f, 0};
    case Style::Melt:
        return {0.05f, 0.12f, 0.012f, 0.45f, 6};
    case Style::Kaleidoscope:
        return {0.12f, 0.55f, 0.02f, 0.15f, 0};
    case Style::LivingPhoto:
        return {0.12f, 1.f, 0.02f, 0.05f, 0};
    case Style::Tunnel:
    default:
        return {0.15f, 0.6f, 0.f, 0.1f, 0};
    }
}

/// Living photo: shader lines that move `auv` by the picture's own content and
/// set `art` (with the colour split). `sample` turns a float2 expression into
/// the blended covers there.
template<typename Sample>
std::string livingPhoto(const Params& params, Sample sample) {
    std::ostringstream s;
    s << "    float3 base = " << sample("auv") << ";\n"
      << "    float l = lum(base);\n"
      << "    float2 tx = float2(0.004, 0.0);\n"
      << "    float2 ty = float2(0.0, 0.004);\n"
      << "    float2 grad = float2(lum(" << sample("auv + tx") << ") - lum(" << sample("auv - tx") << "),\n"
      << "                         lum(" << sample("auv + ty") << ") - lum(" << sample("auv - ty") << "));\n"
      << "    float2 duv = auv;\n"
      // Depth: bright parts bulge out from the middle on the bass, dark ones sink.
      << "    duv += (auv - 0.5) * (l - 0.5) * " << num(params.get(Param::Depth)) << " * (0.25 + q1) * 0.2;\n"
      // Ripples running along the outlines, with the mids.
      << "    grad = grad / (1.0 + 2.0 * length(grad));\n"
      << "    duv += grad * sin(time * 5.0 + l * 25.0) * " << num(params.get(Param::Outlines))
      << " * (0.4 + min(mid_att, 1.5)) * 0.03;\n"
      // Bottom third sways with the bass, the middle with the mids, the top with the treble.
      << bandAt(params, "lb", "auv.y")
      << "    float e = min(max(lb - 0.7, 0.0), 1.2);\n"
      << "    duv.x += sin(auv.y * 9.0 + time * 3.0) * e * "
      << num(params.get(Param::Bands)) << " * 0.018;\n"
      << "    duv.y += cos(auv.x * 7.0 + time * 2.0) * e * "
      << num(params.get(Param::Bands)) << " * 0.008;\n"
      // Big beats: some horizontal strips jump sideways (q9 = strength, q10 = which strips).
      << "    float strip = floor(auv.y * 14.0) + q10;\n"
      << "    float jump = frac(sin(strip * 12.9898) * 43758.5453);\n"
      << "    duv.x += step(0.55, jump) * (jump - 0.775) * q9 * 0.35;\n"
      // Hi-hats: red, green and blue pull apart (q8 = treble hit).
      << "    float2 split = float2(" << num(params.get(Param::ColorSplit)) << " * (0.1 + min(q8, 1.0)) * 0.01, 0.0);\n"
      << "    float3 art = float3(" << sample("duv + split") << ".x, " << sample("duv") << ".y, "
      << sample("duv - split") << ".z);\n"
      << "    float3 art2 = " << sample("duv + 0.004") << ";\n";
    return s.str();
}

} // namespace

const char* styleName(Style style) {
    const int index = static_cast<int>(style);
    return index >= 0 && index < kStyleCount ? kStyleNames[index] : kStyleNames[0];
}

const ParamInfo& paramInfo(Param param) {
    return kParams[static_cast<int>(param)];
}

Params::Params() {
    for (int i = 0; i < kParamCount; ++i) {
        values[i] = kParams[i].defaultValue;
    }
}

void Params::set(Param param, float value) {
    const ParamInfo& info = paramInfo(param);
    values[static_cast<int>(param)] = std::min(info.max, std::max(info.min, value));
}

Palette extractPalette(const unsigned char* rgba, int width, int height, int stride) {
    Palette palette;
    if (!rgba || width <= 0 || height <= 0) {
        return palette;
    }
    // Colours in 8x8x8 buckets, sampled on a grid of about 64x64 pixels.
    struct Bucket {
        double r = 0, g = 0, b = 0, weight = 0;
        Rgb average() const {
            return {static_cast<float>(r / weight), static_cast<float>(g / weight), static_cast<float>(b / weight)};
        }
    };
    std::vector<Bucket> buckets(512);
    const int step = std::max(1, std::max(width, height) / 64);
    for (int y = 0; y < height; y += step) {
        const unsigned char* pRow = rgba + static_cast<std::size_t>(y) * stride;
        for (int x = 0; x < width; x += step) {
            const unsigned char* pPixel = pRow + x * 4;
            if (pPixel[3] < 128) {
                continue;
            }
            const Rgb c = {pPixel[0] / 255.f, pPixel[1] / 255.f, pPixel[2] / 255.f};
            // Vivid, mid-bright colours count most; near black and near white little.
            const float sat = saturation(c);
            float weight = 0.1f + sat * sat * 2.f;
            if (maxOf(c) < 0.15f) {
                weight *= 0.15f;
            } else if (minOf(c) > 0.9f) {
                weight *= 0.3f;
            }
            Bucket& bucket = buckets[(pPixel[0] >> 5) << 6 | (pPixel[1] >> 5) << 3 | (pPixel[2] >> 5)];
            bucket.r += c.r * weight;
            bucket.g += c.g * weight;
            bucket.b += c.b * weight;
            bucket.weight += weight;
        }
    }
    const Bucket* pMain = nullptr;
    for (const Bucket& bucket : buckets) {
        if (bucket.weight > 0 && (!pMain || bucket.weight > pMain->weight)) {
            pMain = &bucket;
        }
    }
    if (!pMain) {
        return palette;
    }
    const Rgb main = pMain->average();
    // The accent: common, but clearly different from the main colour.
    const Bucket* pAccent = nullptr;
    double best = 0;
    for (const Bucket& bucket : buckets) {
        if (bucket.weight <= 0 || &bucket == pMain) {
            continue;
        }
        const float apart = distance(bucket.average(), main);
        if (apart < 0.3f) {
            continue;
        }
        const double score = bucket.weight * apart;
        if (score > best) {
            best = score;
            pAccent = &bucket;
        }
    }
    palette.main = vivid(main);
    palette.accent = pAccent ? vivid(pAccent->average()) : mixed(palette.main, {1.f, 1.f, 1.f}, 0.6f);
    return palette;
}

std::string textureName(const void* data, std::size_t size) {
    // FNV-1a, 64 bit.
    std::uint64_t hash = 14695981039346656037ull;
    const auto* pBytes = static_cast<const unsigned char*>(data);
    for (std::size_t i = 0; i < size; ++i) {
        hash ^= pBytes[i];
        hash *= 1099511628211ull;
    }
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "albumart_%016llx", static_cast<unsigned long long>(hash));
    return buffer;
}

std::string buildPreset(std::vector<Layer> layers, const Params& params) {
    // The heaviest covers, weights adding up to 1.
    layers.erase(std::remove_if(layers.begin(),
                         layers.end(),
                         [](const Layer& layer) { return layer.texture.empty() || layer.weight <= 0.f; }),
            layers.end());
    std::stable_sort(layers.begin(), layers.end(), [](const Layer& a, const Layer& b) {
        return a.weight > b.weight;
    });
    if (layers.size() > kMaxLayers) {
        layers.resize(kMaxLayers);
    }
    float total = 0.f;
    for (const Layer& layer : layers) {
        total += layer.weight;
    }
    Rgb glow = {0.f, 0.f, 0.f};
    Rgb wave = {0.f, 0.f, 0.f};
    // Palette colours blended by weight.
    for (Layer& layer : layers) {
        layer.weight /= total;
        glow.r += layer.palette.main.r * layer.weight;
        glow.g += layer.palette.main.g * layer.weight;
        glow.b += layer.palette.main.b * layer.weight;
        wave.r += layer.palette.accent.r * layer.weight;
        wave.g += layer.palette.accent.g * layer.weight;
        wave.b += layer.palette.accent.b * layer.weight;
    }
    if (layers.empty()) {
        const Palette fallback;
        glow = fallback.main;
        wave = fallback.accent;
    }

    const StyleTuning tune = tuning(params.style);
    const float pulse = params.get(Param::BassPulse);
    const float warp = params.get(Param::Warp);
    const float spin = params.get(Param::Spin);
    const float trails = params.get(Param::Trails);
    const float waveform = params.get(Param::Waveform);
    const float background = params.get(Param::Background);
    const float drift = params.get(Param::ColorDrift);
    const float decay = 0.86f + 0.13f * trails;

    std::ostringstream out;
    out << "[preset00]\n"
        << "MILKDROP_PRESET_VERSION=201\n"
        << "PSVERSION=2\n"
        << "PSVERSION_WARP=2\n"
        << "PSVERSION_COMP=2\n"
        << "fRating=5.000\n"
        << "fGammaAdj=1.000\n"
        << "fDecay=1.000\n"
        << "fVideoEchoZoom=1.000\n"
        << "fVideoEchoAlpha=0.000\n"
        << "nVideoEchoOrientation=0\n"
        << "nWaveMode=" << tune.waveMode << '\n'
        << "bAdditiveWaves=1\n"
        << "bWaveDots=0\n"
        << "bWaveThick=1\n"
        << "bModWaveAlphaByVolume=0\n"
        << "bMaximizeWaveColor=0\n"
        << "bTexWrap=1\n"
        << "bDarkenCenter=0\n"
        << "bRedBlueStereo=0\n"
        << "bBrighten=0\n"
        << "bDarken=0\n"
        << "bSolarize=0\n"
        << "bInvert=0\n"
        << "fWaveAlpha=" << num(waveform) << '\n'
        << "fWaveScale=" << num(0.4 + 0.8 * waveform) << '\n'
        << "fWaveSmoothing=0.750\n"
        << "fWaveParam=0.000\n"
        << "fModWaveAlphaStart=0.750\n"
        << "fModWaveAlphaEnd=0.950\n"
        << "fWarpAnimSpeed=1.000\n"
        << "fWarpScale=1.500\n"
        << "fZoomExponent=1.000\n"
        << "fShader=0.000\n"
        << "zoom=1.000\n"
        << "rot=0.000\n"
        << "cx=0.500\n"
        << "cy=0.500\n"
        << "dx=0.000\n"
        << "dy=0.000\n"
        << "warp=" << num(tune.meshWarp * warp) << '\n'
        << "sx=1.000\n"
        << "sy=1.000\n"
        << "wave_r=" << num(wave.r) << '\n'
        << "wave_g=" << num(wave.g) << '\n'
        << "wave_b=" << num(wave.b) << '\n'
        << "wave_x=0.500\n"
        << "wave_y=0.500\n"
        << "ob_size=0.000\n"
        << "ob_a=0.000\n"
        << "ib_size=0.000\n"
        << "ib_a=0.000\n"
        << "nMotionVectorsX=0.000\n"
        << "nMotionVectorsY=0.000\n"
        << "mv_a=0.000\n"
        << "b1n=0.000\n"
        << "b2n=0.000\n"
        << "b3n=0.000\n"
        << "b1x=1.000\n"
        << "b2x=1.000\n"
        << "b3x=1.000\n"
        << "b1ed=0.250\n";

    // Per frame: the music turned into q variables for the shaders.
    std::ostringstream perFrame;
    perFrame << "q1 = min(max(bass_att - 0.85, 0), 1.5) * " << num(pulse) << ";\n"
             << "q2 = min(mid_att, 2.5) * " << num(warp) << ";\n"
             << "q3 = min(max(treb_att - 0.7, 0), 2) * " << num(params.get(Param::Sparkle)) << ";\n"
             << "q4 = time * " << num(spin * 0.25) << ";\n"
             << "q5 = " << num(0.08 * spin) << " * sin(time * 0.37) + 0.04 * q1 * sin(time * 1.9);\n"
             << "q6 = " << num(params.get(Param::Glow)) << " * (0.25 + min(bass_att, 2.5) * 0.5);\n"
             << "travel = travel + (0.004 + 0.006 * min(bass_att, 3)) * " << num(0.5 + spin) << ";\n"
             << "q7 = travel;\n"
             << "zoom = 1 + " << num(tune.zoom) << " * q1;\n"
             << "rot = " << num(0.004 * spin) << " * sin(time * 0.21);\n"
             << "wave_a = " << num(waveform) << " * (0.5 + 0.5 * min(vol_att, 1));\n"
             << "q8 = min(max(treb - 1, 0), 2);\n"
             // A big beat: bass well above its average. The glitch fades out over a few frames.
             << "beat = above(bass, bass_att * 1.35) * above(bass, 1.1);\n"
             << "glitch = max(glitch * 0.82, beat * " << num(params.get(Param::Glitch)) << ");\n"
             << "seed = if(beat, rand(1000), seed);\n"
             << "q9 = glitch;\n"
             << "q10 = seed;\n";
    writeCode(out, "per_frame_", perFrame.str(), false);

    std::string samplers;
    for (const Layer& layer : layers) {
        samplers += "sampler sampler_fc_" + layer.texture + ";\n";
    }

    // Warp: the trails. The previous frame, bent by the mids and fading, with
    // the cover fed in again on every frame (more on the bass).
    std::ostringstream warpShader;
    warpShader << samplers
               << "shader_body\n"
               << "{\n"
               << "    float2 d = float2(sin(uv.y * 9.0 + time * 1.7), cos(uv.x * 8.0 - time * 1.3));\n"
               // Melt: each height melts with its own band (bass at the bottom).
               << (params.style == Style::Melt
                                  ? bandAt(params, "mb", "uv_orig.y") + "    d *= 0.4 + 0.9 * min(mb, 2.5);\n"
                                  : std::string())
               << "    ret = tex2D(sampler_main, uv + d * q2 * 0.004).xyz * " << num(decay) << " - 0.003;\n"
               << artMapping(params, "uv_orig")
               << "    float3 art = " << sampleArt(layers, "auv") << " * shade;\n"
               << "    ret = lerp(ret, art, saturate(mask * " << num(tune.feed) << " * (0.25 + q1)));\n"
               << "}\n";
    writeCode(out, "warp_", warpShader.str(), true);

    // Composite: trails, blurred background, the crisp cover with treble
    // sparkle, the palette glow and the colour drift.
    std::ostringstream comp;
    comp << samplers
         << "shader_body\n"
         << "{\n"
         << "    float3 col = tex2D(sampler_main, uv).xyz * " << num(0.55 + 0.4 * trails) << ";\n"
         << "    col += GetBlur2(uv) * " << num(0.6 * background) << ";\n"
         << artMapping(params, "uv");
    const bool framed = params.style == Style::Cover || params.style == Style::LivingPhoto;
    if (framed) {
        comp << "    float2 buv = pf * 0.9 + 0.5;\n"
             << "    col += " << sampleArt(layers, "buv") << " * " << num(0.3 * background) << ";\n";
    }
    if (params.style == Style::LivingPhoto) {
        comp << livingPhoto(params, [&layers](const std::string& uv) { return sampleArt(layers, uv); });
    } else {
        comp << "    float3 art = " << sampleArt(layers, "auv") << ";\n"
             << "    float3 art2 = " << sampleArt(layers, "auv + 0.004") << ";\n";
    }
    // Living photo: its edges move already, so a gentler flash.
    comp << "    art += saturate(length(art - art2) * 4.0) * q3 * "
         << (params.style == Style::LivingPhoto ? "0.25" : "0.7") << ";\n"
         << "    art *= shade;\n";
    if (framed) {
        // The glow round the cover follows the bands by angle: bass below, highs above.
        comp << "    float dist = length(max(abs(a) - 0.5, 0.0)) * " << num(params.get(Param::Size)) << ";\n"
             << bandAt(params, "gb", "abs(atan2(a.x, -a.y)) * 0.31830989")
             << "    col += " << vec3(glow) << " * exp(-dist * 12.0) * q6 * (1.0 - mask)"
             << " * (0.3 + 0.9 * min(max(gb - 0.6, 0.0), 2.0));\n";
    } else {
        comp << "    col += " << vec3(glow) << " * q6 * 0.12;\n";
    }
    comp << "    col = lerp(col, art, mask * " << num(tune.artOpacity) << ");\n";
    if (drift > 0.001f) {
        comp << "    float h = time * " << num(drift * 0.3) << ";\n"
             << "    float3 k = float3(0.57735, 0.57735, 0.57735);\n"
             << "    col = col * cos(h) + cross(k, col) * sin(h) + k * dot(k, col) * (1.0 - cos(h));\n";
    }
    comp << "    ret = col;\n"
         << "}\n";
    writeCode(out, "comp_", comp.str(), true);
    return out.str();
}

} // namespace albumart
