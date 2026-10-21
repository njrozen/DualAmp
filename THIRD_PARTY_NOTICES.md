# Third-party software and content

DualAmp's own code is under the MIT license (see `LICENSE`). It includes or uses:

## projectM 4.1.8 (visualizer engine)

- Source: https://github.com/projectM-visualizer/projectm
- License: GNU Lesser General Public License 2.1 (`DualAmp App/app/src/main/cpp/third_party/projectm/LICENSE.txt`
  and `COPYING`).
- projectM is built into the app from its source in this repository. Changes to it:
  - a build fix in `src/api/version.h.in` (version macros);
  - since DualAmp 1.4, 16 fine loudness bands for preset shaders, used by the album art visualizer
    (`src/libprojectM/Audio/` Loudness, PCM, FrameAudioData and AudioConstants; `MilkdropPreset/MilkdropShader.cpp`
    and `Shaders/PresetShaderHeaderGlsl330.inc`; each change is marked "Mixxx/DualAmp patch").

  Build options are set in `DualAmp App/app/src/main/cpp/CMakeLists.txt`. Because the complete source of DualAmp and projectM is
  published here, anyone can change projectM and rebuild the app with it, as the LGPL requires.

## MilkDrop presets ("Cream of the Crop")

- Source: https://github.com/projectM-visualizer/presets-cream-of-the-crop
- Curated by ISOSCELES (preset selection and sorting) and Jason Fletcher. Presets by their many original authors.
- MilkDrop presets were freely released by their authors and are generally treated as public domain. If you are a
  preset author and want a preset removed from DualAmp, open an issue and it will be taken out.

## MilkDrop texture pack

- Source: https://github.com/projectM-visualizer/presets-milkdrop-texture-pack
- Textures originally released with MilkDrop and collected by the projectM project for use with presets.

## Android libraries

- AndroidX, Jetpack Compose, Material 3 and Media3 / ExoPlayer: Apache License 2.0
  (https://www.apache.org/licenses/LICENSE-2.0).
- Kotlin and kotlinx.coroutines: Apache License 2.0.

## DualAmp Server

- Uses only Windows itself: .NET Framework 4.8 and Windows' built-in SQLite (`winsqlite3.dll`) to read
  foobar2000's media library. No third-party code is included.

foobar2000 is a trademark of its respective owner; DualAmp is not affiliated with foobar2000, Ayn or projectM.
