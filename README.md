# DualAmp

A dual-screen music player for the Ayn Thor (and other dual-display Android handhelds).

- **Top screen:** now playing. A full-screen MilkDrop visualizer with album art, title, artist, album, year,
  genre, format, a seek bar and controls on top of it. The info fades out after a few seconds of no input,
  leaving just the visualizer. Tap the screen (or press any controller button) to bring it back.
- **Bottom screen:** your library, laid out like Spotify's: category chips (Playlists, Artists, Albums, Songs,
  Genres, Years, Folders, Queue), a sort menu, and a view switch that cycles **List → Grid → Cover Flow →
  Compact**. Drill-down works like an iPod: Artist → their albums and singles → tracks.
  Cover Flow works on every page: artists, an artist's albums, an album's tracks, playlists, genres, years and folders.

On a device with one screen (a phone, or a handheld with its second screen off) it opens in single-screen mode: both halves
on one screen (stacked in portrait, side by side in landscape).

**Version 1.0** · made by njrozen and Claude.

## Installing

Download from the [latest release](https://github.com/njrozen/DualAmp/releases/latest): **DualAmp-1.0.apk** (the app) and,
optionally, **DualAmp-Server.exe** (for playing your PC's music).

1. Copy `DualAmp-1.0.apk` to the Thor (or any Android 11+ device) and open it (allow installing from your file manager when asked), or from a PC:
   `adb install DualAmp-1.0.apk`.
2. Start DualAmp. It asks for your music folder, offers the shoulder-button permission, then shows the help guide
   (Settings > Help opens it again).
3. Optional, for the lid-closed buttons: Settings > Lid-closed buttons, and turn on DualAmp under
   Accessibility (Settings > Shoulder buttons while asleep).
4. Optional, to play the music on your Windows PC over Wi-Fi: run `DualAmp-Server.exe` on the PC and choose your music in its window (see
   [Playing music from your PC](#playing-music-from-your-pc)).

## Building

Requirements: Android Studio (its bundled JDK is fine), Android SDK 36, NDK 28.2.13676358, CMake 3.31.6.
All of these can be installed from Android Studio's SDK Manager.

1. *(Optional, recommended)* Bundle ~9,800 MilkDrop presets plus the texture pack:
   ```
   powershell -ExecutionPolicy Bypass -File tools\fetch-presets.ps1
   ```
   Without this, the app starts with no presets. You can still import your own from Settings.
2. Open the folder in Android Studio and run it, or build from a terminal:
   ```
   gradlew assembleDebug
   ```
   The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
3. Install it on the Thor: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Controls

| Input | Action |
|---|---|
| Tap top screen | Show or hide the song info |
| Swipe left/right on the visualizer, or its arrow buttons | Next / previous preset in the visualizer folder you are using (wraps round) |
| D-pad | Move the library cursor (left/right switch categories at the top level, left goes back elsewhere). Up from the top row reaches the categories (Playlists, Artists, Albums, Singles, Songs, Genres, Years, Folders, Queue; left / right switch) and then the header: PC, Library, Equalizer, search, visualizers, settings (A picks, down goes back) |
| A | Open / play |
| B | Back |
| Y (or long-press) | Item menu: play next, add to queue, add to playlist, like, go to album/artist |
| X / R2 | Play / pause (R2 also works asleep, see below) |
| Start | Settings (press again to close); hold for the visualizer folders |
| L1 / R1 | Previous / next track (also asleep) |
| L2 | Next visualizer, or a random one from the folder (Settings > L2 button) |
| Playlist button (next to Like, top screen) | Add the playing song to a playlist |
| Select | Next tab (PC, Library, Equalizer); hold to search with the keyboard open |
| R3 | Show or hide the song info |

The controller works whichever screen you touched last.

**With the lid closed or the screen off,** L1/R1/R2 still control music once you turn on
**Settings > Accessibility > DualAmp** (Android drops gamepad buttons while asleep otherwise). It only acts
while the device is asleep, so games are unaffected. Music keeps playing while asleep. Some launchers, such as Cocoon,
play their own music when the Thor wakes, which pauses DualAmp.

**Visualizer reactivity:** Settings > Visualizer reactivity (50-300 %, default 150 %). DualAmp evens out the volume the
visualizer sees (quiet songs move it as much as loud ones) and, above 100 %, exaggerates beats compared with the
last second or so, so presets jump harder; below 100 % calms them. What you hear is unchanged.

**Visualizers:** Settings > Change visualizer: every N seconds (slider, 20-120 s), when the song changes, or
locked (the lock button on the top screen). Settings > Visualizers lets you make your own folders of presets
(Y / long-press a preset > Add to visualizer folder) and "Use only this folder".

**Two screens, one app:** closing either screen closes both, Home on either screen sends both to the home menu, and
reopening either screen (from recents) brings both back.
Music keeps playing in the background either way (notification controls stay available).

**First launch:** DualAmp asks for your music folder, then offers to turn on the asleep shoulder buttons (Accessibility).
Both can be skipped and done later in Settings.

**Music folders:** on first launch you pick the folder(s) that hold your music (Settings > Music folders to add or
remove). Playlist files (.m3u) inside those folders are hidden unless you turn them on there.

**Albums vs Singles:** a single is a song alone on a release of the same name ("Cartier" on "Cartier"). A lone song from a bigger album still counts as an album.

**When the queue runs out:** after a song list, search result or single song, a random library song plays next.
Albums and playlists just end. Next (R1 or the button) always keeps going with a random song. The Queue tab shows only
what is up next (the playing song is the header). L3 or "Clear
queue" on the Queue tab removes everything else but keeps the current song playing (it asks first).

**Top-screen visualizer menus:** the folder button picks what the visualizer cycles through: all presets, a
visualizer type (Dancer, Drawing, Geometric, ...) or one of your folders. Tap the preset name to add it to a folder.

**Tabs:** PC music, Library and Equalizer sit in the header next to the title, as icons (the title names the tab you are
in; Select switches between them). Settings is the gear at the top right. The equalizer preset is a drop-down list.

**Search keyboard (gamepad):** D-pad + A type, X delete, Y space, L1 / R1 move the cursor, L2 letters / numbers and symbols, Start done, B close (with nothing typed, B leaves search).

**Equalizer & DSP:** DualAmp's own effects (Poweramp's DSP is closed source and can't be reused): 10-band EQ with
presets, preamp, bass/treble, balance, stereo width / mono and a limiter.

**Top screen:** the song info sits on an adjustable dark background (Settings > Song info background, 0-100 %,
default 50 %). The seek bar is the song's waveform; tap or drag it to jump. Both screens work in portrait;
Settings > Screen orientation locks Auto / Landscape / Portrait.

**Help guide:** what DualAmp does, how to use it, and every control. Shown at launch (tick "Don't show again" to hide it; up / down scroll it).
Settings > Help opens it, Settings > Show help at launch turns the pop-up back on, Settings > Controls lists just the controls.

**Header buttons (bottom screen):** search, visualizer folders (the sparkle) and Settings (gear). Visualizer folders and
Settings are tabs of their own: opening them never replaces what the Library, PC or Equalizer tab was showing, and
pressing the button again (or B) goes back. Tap the DualAmp logo for About (version, device, credits).
Settings are grouped: Library, Screens, Now playing, Visualizer, Help & controls, About.

**Bottom-screen player bar:** add to playlist, previous, play/pause, next. Tap the song to open the queue.

**Queue:** Y / long-press any song, album or playlist > Play next / Add to queue. On the Queue page, Y on a song
gives Play now, Play next (move up) and Remove from queue.

## How it works

| Piece | Files |
|---|---|
| Opening one activity per screen | `DualScreen.kt`, `LauncherActivity.kt` |
| Playback (Media3 ExoPlayer, notification, headset buttons) | `playback/PlaybackService.kt`, `playback/PlayerConnection.kt` |
| Feeding audio to the visualizer | `playback/TapAudioSink.kt`, `audio/AudioTap.kt` |
| MilkDrop rendering (projectM via JNI) | `viz/MilkSurfaceView.kt`, `cpp/milk_bridge.cpp` |
| Preset list, import, shuffle | `viz/PresetManager.kt`, `viz/VizControl.kt` |
| Library scan and grouping | `library/LibraryRepository.kt`, `library/Models.kt` |
| Top screen UI | `ui/NowPlayingScreen.kt` |
| Bottom screen UI | `ui/library/LibraryScreen.kt`, `ui/library/LibraryPages.kt`, `ui/library/CoverFlow.kt` |

### Visualizer

Poweramp's visualizer is its own closed-source MilkDrop engine. Its APK ships `libpowerampcore.so`, which
converts each preset's equations to LuaJIT and translates its HLSL shaders to GLSL, plus a "Milk DSP" that
copies decoded audio out of the player. DualAmp follows the same design with open-source parts:

- **projectM 4.1.8** (`app/src/main/cpp/third_party/projectm`, LGPL-2.1) is the MilkDrop engine. It reads
  `.milk` presets, including MilkDrop 2 shader presets, and renders them with OpenGL ES 3.
- **Audio tap:** ExoPlayer's audio sink is wrapped so every decoded buffer is copied, with its timestamp. The
  visualizer only reads the samples that are playing right now, so the visuals stay on the beat instead of
  running ahead by the size of the audio buffer. It needs no microphone permission.

### Presets

- Bundled presets are read directly from the APK. Textures are copied out once on first launch.
- **Settings → Import preset folder** copies any folder of `.milk` files and textures into app storage.
  Presets that projectM can't compile are skipped automatically.
- Settings also cover how long each preset stays on, shuffle, switching on big beats, render resolution and frame cap.

## Notes and limits

- If the screens open the wrong way round, use **Settings → Swap screens**.
- The app's own playlists and Liked Songs are stored in app storage. Playlists created by other apps through
  Android's media store appear too, but they are read-only.
- projectM covers almost all MilkDrop 1.x/2.x presets. A few that rely on rare HLSL features may fail to
  compile; those are skipped automatically.
- projectM is LGPL-2.1 (see `THIRD_PARTY_NOTICES.md`). The complete source of DualAmp and projectM is in this
  repository, so anyone can modify projectM and rebuild the app with it.

## Playing music from your PC

**DualAmp-Server.exe** (from the [releases page](https://github.com/njrozen/DualAmp/releases/latest)) shares the music on a Windows 10 or 11 PC with DualAmp over Wi-Fi.
It is a single small program (about 65 KB) that uses what Windows already has (.NET Framework 4.8): nothing to install.

**First launch.** Double-click **DualAmp-Server.exe**. Its window opens and asks for your music:

- **Use foobar2000's library** appears when foobar2000 is installed and DualAmp Server can open its music folders.
  It shares exactly what foobar2000 has in its media library.
- **Choose a music folder** opens Windows' folder picker. Subfolders are included.

Your choice is saved (in `%APPDATA%\DualAmp Server`), so the next launch starts sharing straight away.

**Changing your music.** In the window: **Add a music folder…**, **Change…** or **Remove** next to each folder, and
**Share it / Don't share** for foobar2000's library. **Rescan** picks up new files (it also rescans by itself every
15 minutes). While it reads your music, the window shows a progress bar, the file it is reading and a log.

**Connecting your device.** DualAmp connects only when you ask, once each time it starts (so it never opens with a
"can't reach your PC" message). On your device: DualAmp > Settings > PC music > **Connect to** (the PC you used last time),
**Find PC on Wi-Fi**, or **Enter PC address** with the address shown in the server's window. The PC tab is always
there; until you connect it shows a button (or press Y) that takes you to Settings > PC music. PC songs appear in their own **PC** tab (left of Library) with the same
Artists / Albums / Singles / Folders views; they are not mixed into the device's own library.

**Keep the window open while you listen.** Closing it (or **Stop sharing**) stops sharing.

**Connection status:** the PC tab shows a checkmark while the PC is connected and a spinning circle while DualAmp is
looking for it (orange once the connection is lost). DualAmp checks every 1.5 seconds and reconnects by itself when
the PC comes back.

**If it doesn't connect,** DualAmp shows a pop-up on your device within a few seconds (also when a PC song can't play). Check that:

- DualAmp Server is running on the PC, with its window open.
- Your device and the PC are on the same Wi-Fi.
- Windows Firewall allows DualAmp Server. Windows asks the first time the program runs; if you clicked Cancel, or
  your device still can't find the PC, set the PC's Wi-Fi network to **Private** (Settings > Network & internet >
  Wi-Fi > your network > Private).

Windows SmartScreen may warn about an unknown program the first time (the .exe isn't code-signed): More info > Run
anyway.

Artists whose names differ only in capitalization ("Glokk40Spaz" / "Glokk40spaz") are treated as one artist.

**Building the server.** The source is in the `server` folder (C#: `Window.cs` the window, `Server.cs` sharing and the HTTP API,
`Tags.cs` reading MP3 / FLAC / M4A / OGG / Opus tags, `Sqlite.cs` reading foobar2000's library with Windows' own SQLite).
Run `server/build.cmd`: it compiles `DualAmp Server.exe` with the C# compiler that comes with Windows, nothing to install.

## Other options

- Launching DualAmp fresh from the bottom screen's launcher (or as a Cocoon companion app) runs it single-screen there.
- Lid-closed shoulder buttons (Settings > Lid-closed buttons): when the lid closes during playback, DualAmp keeps the screen on
  (black, dimmest) so L1 / R1 / R2 keep working; opening the lid (detected with the Thor's lid sensor) brings the screen back,
  and so does a tap. After 2 minutes paused with the lid shut, the screen is allowed to sleep. Uses more battery while on.
