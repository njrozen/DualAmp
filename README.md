# DualAmp

A music player made with the **AYN Thor** and its two screens in mind. It works on single-screen Android devices
(phones, tablets, other handhelds) as well: there it puts everything on one screen.

- **Top screen:** now playing. A full-screen MilkDrop visualizer with album art, title, artist, album, year,
  genre, format, a waveform seek bar and controls on top of it. The info fades out after a few seconds (or never),
  leaving just the visualizer. Tap the screen (or press any controller button) to bring it back.
- **Bottom screen:** your library, laid out like Spotify's: category chips (Playlists, Artists, Albums, Singles, Songs,
  Genres, Years, Folders, Queue), a sort menu, and three views: **List, Grid and Cover Flow**. Drill-down works like an
  iPod: Artist → their albums and singles → tracks. Long names scroll so you can read all of them.

**Version 1.6** · made by njrozen and Claude.

| Both screens: now playing on top, the library below (here in Cover Flow) |
|:---:|
| ![Both screens, Cover Flow](screenshots/both-screens-cover-flow.png) |

| Equalizer | Visualizers |
|:---:|:---:|
| ![Both screens, Equalizer tab](screenshots/both-screens-equalizer.png) | ![Both screens, Visualizers tab](screenshots/both-screens-visualizers.png) |

| Single-screen on the top screen (landscape) | Single-screen on the bottom screen (portrait) |
|:---:|:---:|
| ![Single-screen mode on the top screen, landscape](screenshots/single-screen-top-landscape.png) | ![Single-screen mode on the bottom screen, portrait](screenshots/single-screen-bottom-portrait.png) |

| Lyrics over the visualizer (top screen) |
|:---:|
| ![Timed lyrics over the visualizer on the top screen](screenshots/lyrics-top-screen.png) |

**Mix mode (1.6):** DJ-style transitions between the songs of a playlist ([how it works](#mix-mode)).

| Editing a transition: the waveforms on top (with the beat ruler between them), the settings below | A playlist in mix mode: tempo, key and the transitions |
|:---:|:---:|
| ![Mix editor on both screens: song 1 and song 2 waveforms on the top screen with the beat ruler between them, transition settings on the bottom](screenshots/mix-editor-both-screens.png) | ![A playlist in mix mode with each song's BPM and key and a transition between each pair](screenshots/mix-playlist.png) |

**On one screen, held upright** (a phone, or the Thor's bottom screen alone): the waveforms above the settings, and,
while customizing (here the filter: dots on its curve), bigger waveforms with only that setting below.

![The mix editor on one screen in portrait: waveforms above the settings; customizing the filter makes the waveforms bigger with just the filter rows below](screenshots/mix-single-screen-portrait.png)

| Mixed playlists carry the mixer symbol |
|:---:|
| ![The Playlists grid: Mix demo shows the mixer symbol and Mixed by you](screenshots/mix-mixed-playlists.png) |

**Also new in 1.6:**

| Settings in tabs | Add to several playlists at once |
|:---:|:---:|
| ![Settings with its tabs as chips at the top, on the Now playing tab](screenshots/settings-tabs.png) | ![Add to playlist with a tick box for each playlist and Add to 2 playlists at the top](screenshots/add-to-playlists.png) |

| Search this playlist | Playlists: all, yours, or playlist files |
|:---:|:---:|
| ![Searching inside a playlist: only its artists, with how many of their songs it holds](screenshots/playlist-search.png) | ![The Playlists tab showing only playlist files](screenshots/playlists-showing.png) |

| Select multiple in Cover Flow | Years by decade |
|:---:|:---:|
| ![Cover Flow with a selected artist: an accent ring and a tick](screenshots/cover-flow-select.png) | ![The Years tab grouped by decade, on the 2020s](screenshots/years-by-decade.png) |

## Screens, landscape and portrait

- **Settings > Use both screens** chooses how DualAmp uses the Thor's screens:
  - **Both Screens** (default): now playing on the top screen, the library on the bottom one.
  - **Top Screen** or **Bottom Screen**: single-screen mode, with now playing and the library together on that screen.
- **Single-screen mode** is also what you get on a device with one screen, and when you open DualAmp from the bottom
  screen's launcher or as a Cocoon companion app (Settings then shows Bottom Screen). In landscape the player and the
  library sit side by side; in portrait the player is on top and the library below.
  **Settings > Swap sides (single-screen)** puts the library on the left (landscape) or on top (portrait). The grid has
  its own size in single-screen mode (3 per row by default).
- **Settings > Swap screens** is for devices whose screens open the wrong way round (both-screens mode).
- **Settings > Screen orientation:** Auto (follows how you hold the device), Landscape or Portrait (either way up).
  Both screens always turn together. In portrait the D-pad turns with the screen, so up on the D-pad is always up on
  screen.

## What's new in 1.6

### Mix mode

- **Mix mode:** turn a playlist into a DJ mix. Each song shows its **tempo (BPM) and key** (Camelot code, e.g. 8A), and
  between every two songs you set up a **transition**: where song 2 comes in, from where in song 2, for how many bars,
  and how they blend (volume, EQ / bass swap, high- and low-pass filters, reverb / echo / delay, looping, each song's
  lows / mids / highs). **Play the transition** hears it straight away; **Save** keeps it, so the playlist plays the
  same mix every time. **Auto-mix** makes a transition for every pair of songs in one go. See [Mix mode](#mix-mode).
- **Beatmatching** is on by default: song 2 plays at song 1's tempo, on its beat, then eases back to its own (or choose
  **Ease into song 2's tempo**).
- **Waveforms:** while you edit a transition, the two songs' waveforms (lows, mids, highs) show lined up as they will
  play, with the volume, EQ, filter and effect moves and where the music is: on the top screen with both screens, above
  the settings on one screen. Drag a waveform to slide that song: it snaps to beats, bars, or anywhere (**Snap to the
  grid**).
- **Beat ruler:** the space between the two waveforms shows both songs' beats, song 1's hanging from the top (blue) and
  song 2's rising from the bottom (orange). Bar lines are thick, beat lines thin, and lines that meet turn green on both
  (a bar on a bar brighter), so you can see at a glance whether the songs are on the beat.
- **Customize** like Spotify's mix mode: every Volume, EQ, Filter and Effect choice can be drawn by hand as a curve of
  dots on the waveform (tap the line to add a dot, drag it, tap it to remove it), with undo / redo. More choices too:
  Spotify-style volume shapes (Overlap stops right at the end instead of fading), all nine filter in / out pairs, and
  reverb, echo and delay effects that ring on after song 1 is gone.
- **On one screen, held upright** (a phone, or the Thor's bottom screen alone): the waveforms sit above the settings,
  with no tab bar or mini player, so the editor gets the whole screen. **Touch the waveforms** (or customize a curve)
  and they grow to most of the screen, with only the settings that go with them below; **Show all settings** or B
  brings back the rest. Side by side is used only on a clearly wide screen.
- **Play button** for the transition on song 2's label row, so it never covers the waveforms or the dots you are
  drawing. No mini player in the transition editor (one screen or two).
- **Mixed playlists** show the mixer symbol and "Mixed by you". Mix mode can be turned off at any time to play the
  playlist normally (shuffle too); its transitions are kept. **Move up / Move down** in a playlist song's menu sets the
  order.
- **Smoother mixes:** tempo changes during a transition use Android's own time-stretch, so beatmatching doesn't click
  or stutter; the two songs leave headroom while they play together (no clipping), and analysing songs runs in the
  background without taking time from playback.

### Library and playlists

- **Add to several playlists at once:** Add to playlist (and the top screen's song menu) shows a tick box for every
  playlist and playlist file; tick the ones you want, then **Add to N playlists** at the top.
- **Playlist files** (.m3u...; Settings > Music library > Music folders > Show playlist files) can be added to. In
  .m3u files the songs go at the end, written like the lines already there (so a playlist made on your PC keeps
  working there); Android asks once per file. Other formats (.pls, .wpl) are written by Android.
- **Playlists tab: Showing** All playlists / Your playlists / Playlist files (the button at the top; only when there
  are playlist files).
- **Search this playlist** on a playlist's page: only its songs and artists come up; an artist opens their songs in
  that playlist.
- **Playlists sort by Album artist** by default, so an album with various artists stays together (then year, album,
  disc and track). Track order (the playlist's own) and the other sorts are still in the sort menu.
- **Playlist pictures:** Y (or long-press) on a playlist > **Choose playlist picture** for your own photo; **Remove
  playlist picture** goes back to the first song's cover.
- **Artist pictures:** Settings > Artists > **Fetch artist pictures** (off until you turn it on; the first launch asks)
  finds a photo of each artist online on Wi-Fi. Or choose your own: Y on an artist > **Choose artist picture**, or
  **Artist picture** on the artist's page. Without either, artists show one of their covers. See
  [Artist pictures](#artist-pictures).
- **Years by decade:** the Years tab can group the years into decades (**Group by decade** / **Show every year** at
  the top); each decade opens to its years, with Shuffle and All songs.
- **Select multiple in Cover Flow:** picked covers get an accent ring and a tick (and a tick by the name).
- **Remove lyrics from the song:** in a song's menu, next to Delete .lrc file: takes out the lyrics stored in the
  song's own tags (asks first).

### Now playing on the bottom screen

- **Bigger now-playing view** (tap the mini player): no artwork in the way, so the buttons have room; they shrink to
  fit (portrait with the lyrics) and both rows line up. **Tap the title** for the song's menu (go to artist / album,
  edit song info...); the gamepad works in that menu too.
- Settings > Now playing > Bottom screen: **Mini player** (on or off, with both screens), **Waveform progress bar**
  (the song's waveform, like the top screen's), **Album art background** (the cover behind the view, with **Album art
  blur** and **Background behind the buttons**).

### Settings

- **Settings in tabs:** Music library, Artists, Library view, Theme, Screens, Now playing (top and bottom screen),
  Lyrics, Visualizer, Album art visualizer, Help & controls and About, as chips at the top of Settings. Tap one, or
  press up from the first setting and then left / right. Settings opens on the tab you used last.

### Fixes

- Big playlist files that list a song more than once no longer crash DualAmp when opened.
- In menus opened by touch (e.g. tapping the song in the bottom player), the D-pad now shows its highlight straight
  away.
- Pressing up from the first setting of a page reaches the tabs / header even when a section title is above it.

## What's new in 1.5.4

- **Now playing on the bottom screen:** tap the song in the player bar for a bigger player: a big progress bar,
  big previous / next, back and forward 10 seconds, play / pause, like, add to playlist, shuffle, repeat and queue
  buttons, and the **lyrics**, which then leave the top screen and show here. Back arrow, B or Back returns to the
  library.

## What's new in 1.5.3

- **Delete .lrc file (wrong lyrics):** in a song's menu (Y or long-press, Hold Y for the song playing, or the cover
  menu on the player) when the song has a `.lrc` file next to it, and in Edit song info (for several songs too). For
  `.lrc` files holding another song's lyrics. DualAmp asks first; Android may ask too, or once for the song's folder.
- **Hide artists** from the Artists tab: Y (or long-press) on an artist > Hide from Artists, e.g. someone featured on
  one song. Their songs stay where they are; Settings > Artists > Hidden artists brings them back.
- **Uninterrupted visualizer** now also stays clear when you skip songs with L1 / R1. **Show the new song** (on by
  default) puts the song's title and album (just the title for a single), with its artists below, top left for a
  moment instead.
- **Fixes:** in Cover Flow, going up to Shuffle (or the other buttons) and back down returns to the cover you were on;
  in the single-screen tabs layout, Go to album / Go to artist from the big player's cover menu closes the big player
  and opens the page.

## What's new in 1.5.2

- **Autoplay:** when an album, artist or list of songs ends, similar songs keep playing (on the Queue tab, or
  Settings > Queue).
- **Playlists start over** when they end; a shuffled playlist keeps going in a new random order.
- **Play next / Add to queue** now come before the rest of the album or playlist playing, shuffle on or off.
- **Artists tab: album artists only** (Settings > Artists): featured artists leave the Artists tab but still
  come up in search, with their page and songs.
- **Edit several songs at once:** a whole album (Edit album info), an artist or playlist, or songs picked with Select
  multiple. Fields the songs don't share show "Various" and stay as they are unless you change them.
- **Edit song info** also edits the **comment** and can **remove the lyrics stored in songs** (not `.lrc` files).

## What's new in 1.5.1

- **Uninterrupted visualizer** (Settings > Now playing, top screen): the song info no longer comes back over the
  visualizer when the next song starts. It still shows when you pause, tap or press a button.
- **Genres:** a song tagged with several genres ("Rock; Punk" or "Rock, Punk") is listed under Rock and under Punk,
  instead of under a "Rock; Punk" genre of its own.
- **Fix:** closing DualAmp now takes it out of Android's media controls, and closing the Thor's lid afterwards lets
  the screen turn off as usual.

## What's new in 1.5

- **Edit song info:** press Y on a song (or long-press it) and choose Edit song info to change its title, artist,
  album artist, album, release date, genre, track and disc number, or its cover art (from your files or photos).
  Settings > Edit song info (Music library) takes it out of the menu. See [Edit song info](#edit-song-info).
- **Leaks & unreleased:** songs whose comment tag says "leak", "leaked" or "unreleased" get their own section on
  the artist's page, split into albums and singles, apart from the official releases. See
  [Leaks & unreleased](#leaks--unreleased).

## What's new in 1.4

- **Album art visualizer:** the visualizer can be built from the cover of the song playing, instead of the MilkDrop
  presets or taking turns with them (Settings > Visualizer shows, or Album art on the Visualizers page). It still
  moves with the music, and takes its glow and waveform colours from the cover. Five styles: Cover, Melt,
  Kaleidoscope, Tunnel and **Living photo** (the picture itself bulges, ripples, sways, splits colours and glitches
  with the music), with a slider for every part. See [Album art visualizer](#album-art-visualizer).
- **Frequency detail:** the album art styles can follow lows, mids and highs, or 16 frequency bands so each part of
  the picture follows its own frequencies.
- **L2 with album art** always goes to the next style, in order.

## What's new in 1.3

- **Single-screen tabs layout** (Settings > Screens > Single-screen layout: Tabs), like Spotify: the library with its tabs at the bottom
  or top and a player bar; tap it (or double-click L3) for the big player, close it with its back arrow or B. Swipe the cover to skip.
  See [Single-screen tabs layout](#single-screen-tabs-layout).
- **Multiple artists:** songs with several artists ("A, B", "A feat. B", "Song (feat. B)") are listed under each, with **Appears on**
  on artist pages; the separators and the names never to split are editable. See [Multiple artists](#multiple-artists).
- **Mp3tag actions** for tidying multi-artist tags (`DualAmp-Mp3tag-actions.zip` on the release page).
- **Lyrics from the internet** show a small cloud next to the lyrics button.
- **Controls:** Select from Visualizers or Settings goes back to the tab you were in.
- **Fix:** the About box fits narrow screens (single-screen side by side in landscape).

## What's new in 1.2

- **Lyrics:** timed lyrics over the visualizer and in the song info, from `.lrc` files next to your songs, the songs' tags, or
  lrclib.net. The lyrics button (top left of the player) or a double-click of the right stick turns them on and off; Settings > Lyrics
  sets the background darkness and the timing (sooner / later). Songs without lyrics just show the visualizer.
- **Stream the visualizer** to a TV, PC or phone browser on the same Wi-Fi (Settings > Stream visualizer).
- **Hold Y** to like the song playing or add it to a playlist, from anywhere.
- **Lid closed:** the buttons work with the music paused too, the visualizer stops to save battery, the volume buttons keep
  controlling DualAmp, and it works alongside SleepManager's closed-lid protection.
- **Cover Flow:** the glow pulses as you move through the covers.
- **Fixes:** waveforms load for every song; the artist page's buttons fit narrow screens.

## What's new in 1.1

- **Screens:** Both Screens / Top Screen / Bottom Screen, single-screen mode in landscape and portrait, Swap sides,
  and both screens turning together (Auto, Landscape or Portrait). The single-screen player fits its half of the
  screen, and long titles, artists and visualizer names scroll.
- **Library views:** List, Grid and Cover Flow on every page. One view everywhere, or separate views for the
  categories and the pages inside them; the view to start with; grid size 3-6 per row.
- **Looks:** themes (Default, Light, OLED black, Midnight) with an accent color, and a Cover Flow glow behind the
  selected cover (color, shape, strength and how it appears).
- **Controls:** L3 changes the library view (hold to clear the queue); hold R2 / L2 to fast forward / rewind, also
  with the lid closed; L2 can pick a random visualizer.
- **Volume on the Thor:** both screens use the top screen's volume; single-screen mode uses the volume of the
  screen DualAmp is on.
- **Other:** song info fades after 3-30 s or never; Visualizer reactivity setting; quitting DualAmp stops the music;
  waveforms now load for every song; settings are grouped into sections.

## Installing

Download from the [latest release](https://github.com/njrozen/DualAmp/releases/latest): **DualAmp-1.6.apk** (the app) and,
optionally, **DualAmp-Server.exe** (for playing your PC's music) and **DualAmp-Mp3tag-actions.zip** (for tidying
artist tags, see [Multiple artists](#multiple-artists)).

1. Copy `DualAmp-1.6.apk` to the Thor (or any Android 11+ device) and open it (allow installing from your file manager when asked), or from a PC:
   `adb install DualAmp-1.6.apk`. It installs over earlier versions and keeps your settings and playlists.
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
| Y (or long-press) | Item menu: play next, add to queue, add to playlist, like, go to album/artist. Hold Y: like the song playing or add it to a playlist |
| X / R2 | Play / pause (R2 also works asleep, see below). Hold R2: fast forward |
| Start | Settings (press again to close); hold for the visualizer folders |
| L1 / R1 | Previous / next track (also asleep) |
| L2 | Next visualizer, or a random one from the folder (Settings > L2 button). With album art: the next style, in order. Hold: rewind |
| Playlist button (next to Like, top screen) | Add the playing song to a playlist |
| Select | Next tab (PC, Library, Equalizer); from Visualizers or Settings, back to the tab you were in; hold to search with the keyboard open. Over the big player (single-screen tabs layout), Select, Start and their holds close it first |
| L3 | Next library view (grid, list, Cover Flow); hold to clear the queue (asks first); double-click in the single-screen tabs layout: the big player |
| R3 | Show or hide the song info; double-click: lyrics on / off |

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
(Y / long-press a preset > Add to visualizer folder) and "Use only this folder". Settings > Visualizer shows can
also build the visualizer from the song's cover (see [Album art visualizer](#album-art-visualizer)).

**Rotation:** see [Screens, landscape and portrait](#screens-landscape-and-portrait). Alone on the bottom screen,
DualAmp turns with the motion sensor and follows the system's auto-rotate switch.

**Volume on the Ayn Thor:** the Thor has a volume for each screen. With both screens DualAmp uses the top screen's
volume (even while you browse the library below); in single-screen mode it uses the volume of the screen it is on
(e.g. the bottom screen as a Cocoon companion app). Apps such as Thor Volume Link still decide which screen the
volume buttons change.

**Two screens, one app:** closing either screen closes both, Home on either screen sends both to the home menu, and
reopening either screen (from recents) brings both back.
Music keeps playing in the background (notification controls stay available); quitting DualAmp stops it.

**First launch:** DualAmp asks for your music folder, then offers to turn on the asleep shoulder buttons (Accessibility).
Both can be skipped and done later in Settings.

**Music folders:** on first launch you pick the folder(s) that hold your music (Settings > Music folders to add or
remove). Playlist files (.m3u) inside those folders are hidden unless you turn them on there.

**Albums vs Singles:** a single is a song alone on a release of the same name ("Cartier" on "Cartier"). A lone song from a bigger album still counts as an album.

**When the queue runs out:** a playlist starts over (reshuffled when shuffle is on, so a shuffled playlist never
stops). After an album, artist, song list or single song, **Autoplay** keeps going with similar songs (sharing
genres, artists and era with what just played), lined up a song ahead so there's no gap. Turn it off on the Queue tab
or in Settings > Queue, and the music stops at the end. Next (R1 or the button) at the end always keeps going.

**Play next / Add to queue** come before the rest of the album or playlist playing, shuffled or not: Play next goes
straight after the current song, Add to queue after the songs you queued before. The Queue tab shows only what is up
next (the playing song is the header). Holding L3 or "Clear queue" on the Queue tab removes everything else but keeps
the current song playing (it asks first).

**Top-screen visualizer menus:** the folder button picks what the visualizer cycles through: all presets, a
visualizer type (Dancer, Drawing, Geometric, ...) or one of your folders. Tap the preset name to add it to a folder.

**Tabs:** PC music, Library and Equalizer sit in the header next to the title, as icons (the title names the tab you are
in; Select switches between them). Settings is the gear at the top right. The equalizer preset is a drop-down list.

**Theme:** Settings > Theme picks the look of the bottom screen (Default, Light, OLED black, Midnight), and Accent its
highlight color. The accent also colors like / shuffle / repeat on the top screen;
the visualizer buttons keep their purple. In Cover Flow, the cover the gamepad is on has a soft glow behind it
(Settings > Cover Flow glow: Auto is white, or black in the Light theme, or one of many colors; a shape: circle,
tall oval, tall diamond or cross / X; strength 0-200 %; and how it appears: expand from the centre, drop in from the
top, or none).

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

**Bottom-screen player bar:** add to playlist, previous, play/pause, next. Tap the song for the bottom screen's **now-playing
view**: the title, a big progress bar (drag to seek), previous, back / forward 10 s, play / pause, next, like, add to playlist,
shuffle, repeat, lyrics and the queue. With lyrics on there, they leave the top screen (just the visualizer) and come
back to it when you close the view (back arrow, B or Back). D-pad moves along the buttons, A presses, Y is the song's menu.
Tap the title (or artist) for the same menu. Settings > Now playing > **Album art background** shows the
song's cover, blurred, behind the view; **Background behind the buttons** sets how dark the panels under the text,
progress bar, buttons and lyrics are (like Song info background on the top screen).

**Queue:** Y / long-press any song, album or playlist > Play next / Add to queue. On the Queue page, Y on a song
gives Play now, Play next (move up) and Remove from queue.

## How it works

| Piece | Files |
|---|---|
| Opening one activity per screen | `DualScreen.kt`, `LauncherActivity.kt` |
| Playback (Media3 ExoPlayer, notification, headset buttons) | `playback/PlaybackService.kt`, `playback/PlayerConnection.kt` |
| Feeding audio to the visualizer | `playback/TapAudioSink.kt`, `audio/AudioTap.kt` |
| MilkDrop rendering (projectM via JNI) | `viz/MilkSurfaceView.kt`, `cpp/milk_bridge.cpp` |
| Album art visualizer | `viz/AlbumArtViz.kt`, `cpp/albumartpreset.cpp` (shared with the Mixxx visualizer) |
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

## Album art visualizer

Settings > **Visualizer shows** picks MilkDrop presets, **Album art**, or **Both** (taking turns at every change).
The Album art entry on the Visualizers page switches it on and off; picking a preset goes back to the presets.
Songs without a cover show the presets.

DualAmp turns the cover of the song playing into a MilkDrop preset of its own, so it reacts to the music like any
preset. The glow and the waveform take the cover's own main and second colours. Styles (Settings > Album art style):

- **Cover:** the cover in the middle, pulsing with the bass, with a glow, trails and the cover blurred behind it.
- **Melt:** the cover fills the screen and melts with the mids.
- **Kaleidoscope:** mirrored slices of the cover.
- **Tunnel:** the cover lines a tunnel flying towards you.
- **Living photo:** the picture stays recognisable but moves by its own content: bright parts bulge forward on
  the bass, ripples run along its edges, rows sway with their frequencies, colours split on hi-hats and strips
  jump sideways on big beats.

**Change the style:** the arrows, the timer and song changes step through the styles (or "Never"). L2 always goes
to the next style. **Frequency detail:** lows / mids / highs, or 16 bands: the glow round the cover, the melt, each
kaleidoscope ring, each tunnel depth and each row of the living photo then follow their own frequencies. MilkDrop
presets always use lows, mids and highs, which is what they are written for.

Every part has a slider (Settings > Album art visualizer): cover size, bass pulse, warp, spin, trails, glow, treble
sparkle, waveform, background art, colour drift, kaleidoscope slices, and the living photo's depth, outline
ripples, frequency bands, colour split, beat glitches and breathing. **Reset album art** puts them back.

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

- Lid-closed shoulder buttons (Settings > Lid-closed buttons): when the lid closes with music queued (playing or paused), DualAmp keeps the screen on
  (black, dimmest) so L1 / R1 / R2 keep working (hold R2 / L2 to fast forward / rewind); opening the lid (detected with the Thor's lid sensor) brings the screen back,
  and so does a tap. The visualizer stops while the lid is shut (unless it is being streamed). After 10 minutes paused with the lid shut, the screen is allowed to sleep.
  The volume buttons keep controlling DualAmp: on the bottom screen's volume, DualAmp plays at the top screen's volume set to the same level while the lid is
  shut (Android lets only the Thor itself change the bottom screen's volume) and goes back when it opens.
  **SleepManager:** works with its "AYN Thor closed-lid protection" on. That protection turns the screen off with the lid shut unless a dock or TV
  is connected, and the Thor's controller (volume buttons too) stops with the screen off. So while the lid is shut with music, DualAmp adds a
  tiny display named "DualAmp virtual external (lid buttons)" that SleepManager counts as a dock (nothing is shown on it); it goes away when
  the lid opens. SleepManager's JamesDSP option turns JamesDSP off with the lid shut, which makes the music quieter; turn it off to avoid that.

## Artist pictures

Artists (the Artists tab and each artist's page) can show a picture of the artist instead of one of their covers:

- **Fetch artist pictures** (Settings > Artist pictures; off until you turn it on, and the first launch asks): while the
  Thor is on Wi-Fi, DualAmp looks each artist up on Deezer as they come into view (only the artist's name is sent) and
  keeps the photo. Artists it can't find show a cover; they are tried again after two weeks, or when you turn the option
  back on. Turning it off goes back to covers.
- **Your own picture:** Y (or long-press) on an artist > **Choose artist picture**, or **Artist picture** on the
  artist's page, and pick one from your photos. It always wins over the one found online. **Remove your artist picture**
  in the same menu goes back.

## Lyrics

A small cloud next to the lyrics button means the lyrics came from the internet (lrclib.net), not from a `.lrc` file or the song's tags.

The lyrics button at the top left of the player (or double-click the right stick, R3) shows lyrics over the visualizer, on a dark box (Settings > Lyrics background),
and the line being sung in the song info. Timed lyrics follow the song; Settings > Lyrics timing moves them sooner or later. DualAmp looks for
a `.lrc` file next to the song (same name), then lyrics in the song's tags, then lrclib.net (Settings > Find lyrics online; sends the title and artist).
Songs without lyrics just show the visualizer.

## Leaks & unreleased

Songs whose **comment** tag contains "leak", "leaks", "leaked" or "unreleased" (any capitals, as whole words) are kept apart from the
official releases. On an artist's page, Albums and Singles show only official releases, and **Unreleased & leaks** opens a page of
their own, split into Albums and Singles. A release counts as unreleased when more than half of its songs are marked, so an official
album with one leaked bonus track stays with the albums. Settings > Leaks & unreleased turns this off.

DualAmp reads the comment from the files themselves (MP3, FLAC, Ogg / Opus and M4A), once, and again only when a file changes, so the
first scan after updating takes a little longer. Songs from DualAmp Server on a PC are not sorted this way yet.

## Edit song info

Open a song's menu (press Y, or long-press it) and choose **Edit song info** to change its title, artist, album artist, album,
release date, genre, track number, disc number and comment, or its cover art (pick an image from your files or photos).
**Remove lyrics in the song** takes out lyrics stored in the file's tags; `.lrc` files are left alone. **Delete .lrc file**
(also in the song's menu when it has one) deletes the lyrics file next to the song right away (it asks first), for one with another song's lyrics. Nothing else is written
until you choose **Save**; Android may then ask once to let DualAmp change the files. The library reloads with the new info.

**Several songs at once:** **Edit album info** at the bottom of an album, **Edit info of N songs** in the menu of an album,
artist or playlist, or pick songs with **Select multiple** and choose Edit. A field the songs share shows its value; one they
don't shows "Various". Only the fields you change are written, to every song, so you can fix the album artist of a whole
album in one go. Title and track number stay each song's own.

Settings > Edit song info (under Music library) takes the options out of the menus. Songs from DualAmp Server can't be edited.

## Mix mode

A way to try out song transitions on the go: a playlist becomes a DJ mix, played in order with a transition between
each pair of songs, like Spotify's mixed playlists.

**Turning it on.** Open one of your playlists and set **Mix mode** to On. The playlist then plays in order (no shuffle)
and each song shows its **tempo (BPM)** and **key** (Camelot code: 8A, 9B…; neighbours on the wheel mix well).
DualAmp reads each song once in the background to find them (a few seconds a song, kept afterwards). Between every two
songs there is a row: **Add a transition**, or the transition already there (e.g. *Transition: Crossfade · 8 bars*).
**Auto-mix the playlist** makes one for every pair at once; **Play mix** plays it. Turn Mix mode off to play the
playlist normally (shuffle too); the transitions are kept for when you turn it back on. A playlist with transitions
shows the mixer symbol and *Mixed by you* in your playlists. Device playlists (from Android) can't be mixed.

**Editing a transition** (A on its row). At the top: the two songs, their tempo and key, and how well they match
(e.g. *141 → 140 BPM (-1%) · 6B → 9B: keys may clash*).

| Setting | What it does |
|:---|:---|
| **Play the transition** | Plays the playlist as a mix from 8 seconds before song 2 comes in, with the transition as it is now (saved or not). Change things while it plays and hear them right away. |
| **Style** | Ready-made transitions: Auto, Smooth, Bass swap, Rise, Fade, Echo out, Quick cut. Changing anything else makes it *Custom*. |
| **Length** | How long the two songs play together, in bars of song 1 (1 to 32; 4 beats a bar), or seconds when the tempo isn't known. |
| **Snap to the grid** | What moving a song lands on: **Beats** (every beat line, the default), **Bars** (only the first beat of each bar, the brighter lines, so the two songs' bars and phrases start together) or **Off** (anywhere, in 10 ms steps). Applies to left / right and to dragging the waveforms. |
| **Song 2 comes in at** | Where in song 1 the transition starts. Left / right moves it a beat (or a bar, or 10 ms, going by Snap). |
| **Song 2 starts from** | Where in song 2 it comes in from. Left / right moves it a beat (or a bar, or 10 ms). |
| **Volume** | Smooth crossfade, Overlap (both at full volume; song 1 stops right at the end, no fade), Fade in fade out, Cut in fade out, Fade in cut out, Cut, or **Custom** (drawn by you). |
| **EQ** | Bass swap (the two songs' bass changes over halfway, so the low end never doubles up), Start / End bass swap, Bass and mid swap, EQ blend (lows, mids and highs hand over gradually), None, or **Custom** (lows, mids and highs drawn by you). |
| **Filter** | High / low pass filter out (song 1 thins out or gets muffled over the second half), High / low pass filter in (song 2 opens up over the first half), every pair of them (e.g. *Low pass filter in high pass filter out*), None, or **Custom**. |
| **Effects** | Reverb, Echo (repeats that get darker) or Delay (plain repeats) every ½, ¾ or 1 beat. Song 1 goes into the effect over the second half and either fades into it (*out end*) or stops right at the end (*cut end*); *Reverb out center* is gone by halfway. The effect rings on after song 1 is gone, before song 2 carries on alone. How much goes in can be drawn too. |
| **Customize on the waveform** | Shows one setting's curves (Volume, Lows, Mids, Highs, Filter or Effect) with dots on the waveform. **Tap the line** to add a dot, **drag** a dot to move it (it snaps to the beat lines, going by Snap to the grid), **tap a dot** to remove it; the end dots only move up and down. Changing a curve makes that setting *Custom*. Picking **Custom** in a setting's list does the same. |
| **Undo / Redo** | Take back or put back changes (also the arrows above the waveform while customizing). |
| **Looping** | Song 1 repeats the first 1, 2 or 4 bars of the transition instead of playing on (e.g. to stay in a beat before its outro). |
| **Beatmatching** | On by default (whenever both songs' tempos are known): song 2 plays on song 1's beat. **Tempo:** *Song 2 matches song 1* (the default: song 2 plays at song 1's tempo, then eases back to its own after the transition) or *Ease into song 2's tempo* (song 2 starts at song 1's tempo and both ease to song 2's own by the end). Songs twice or half as fast are matched every other beat. |
| **Song 1 EQ / Song 2 EQ** | Lows, mids and highs for each song during the transition (-24 dB to +6 dB; all the way left: Off). |
| **Save transition** | Keeps it in the playlist; it plays every time the playlist plays as a mix. Leaving without saving keeps the old one. **Remove transition** goes back to one song simply following the other. |

**The waveforms.** With both screens the top screen shows song 1's waveform above song 2's (blue lows, orange mids,
cream highs), lined up as they will play (song 2 at song 1's tempo when beatmatched), with the beat lines (each bar's
first beat brighter, the three beats between it fainter), the
transition's window, the volume (blue), bass (yellow), filter (pink) and effect (grey) curves, and a green line where the music
is. The play button on song 2's label row plays the transition (or stops it). For the filter, the middle is no filter,
up is a high pass and down a low pass.
**The beat ruler** between the two waveforms shows both songs' beats meeting in the middle: song 1's hang from the top
(blue), song 2's rise from the bottom (orange); bar lines thick, beat lines thin. Where a line has one of the other song's
lines at the same spot, both turn green (a bar on a bar brighter): green all through the window means the songs are on
the beat. Each song's lines are dimmer where it isn't heard.
**Drag** a song's waveform sideways to slide it under the window; it jumps from beat line to beat line as you drag (or
bar to bar, or moves freely, going by **Snap to the grid**).

**On one screen** the waveforms sit above the settings (side by side only when the screen is clearly wide), the tab bar
and mini player make way so the editor fills the screen, and the settings page leaves out the songs' cover and titles
(the waveforms show them). **Touching the waveforms** makes them bigger (most of the screen) with only Snap and the two
meeting points below; **customizing a curve** does the same with just that setting and Customize below. **Show all
settings** (or B) brings back the full list and the smaller waveforms.

**Controller.** The D-pad moves through the settings, left / right moves a slider (a beat or a bar for the meeting points),
A opens a list or presses a button, B goes back. In a playlist, Y on a song has **Move up / Move down**: in a mix the
order of the songs makes the transitions (a transition belongs to its two songs, so moving a song away leaves it unused
until they are next to each other again).

**How it plays.** While song 1 plays, DualAmp starts song 2 on a second player, keeps it on song 1's beat (nudging its
speed by a fraction) and applies the volume, EQ, filter, echo and loop to each song as the transition goes. When song 1
is gone, the main player takes over song 2 at exactly the same spot, so the queue, lyrics, notification and visualizer
simply move on to song 2. Skipping during a transition stops it; seeking into one picks it up from there.

**The beat grid.** DualAmp puts the beat lines on the kick drums (so hats and bass between the beats in techno
and house don't pull the grid off), checks that the tempo isn't read as 2/3 or 3/4 of the real one, and finds which beat
starts the bars from where new parts of the song come in.

**Limits.** Tempo and key are worked out from the audio and can be off for songs without a steady beat (they then show
"?" and transitions are timed in seconds). The visualizer follows song 1 until the transition ends.

## Single-screen tabs layout

Settings > Screens > Single-screen layout: **Tabs** (made for portrait, works in landscape too). DualAmp opens on Your Library with the tabs
(PC Music, Your Library, Equalizer, Search, Visualizers, Settings) at the bottom or top (Settings > Screens > Tab bar), and the player bar
above the bottom like the bottom screen's. Tap the player bar for the big player: the full now-playing screen with the visualizer. Its back
arrow (top left), B or Back closes it; while it is open the tabs cannot be pressed. Swipe the album art left / right for the next / previous
song. **Side by side** (the default) keeps the player and the library both on screen.

## Multiple artists

Songs with more than one artist in their artist tag ("Kanye West, JAY-Z", "Drake feat. Rihanna") or a feature in the title
("Flashing Lights (feat. Dwele)") are listed under each artist: a release goes under each of its album artists, and every credited artist
gets the song, with other artists' releases they are on under **Appears on** on their page. Settings > Artists:

- **Artist separators:** the text between two names (spaces count: " x " is not "x"). Defaults: `, ` `; ` ` & ` ` feat. ` ` feat ` ` ft. `
  ` featuring ` ` with ` ` x ` ` / `.
- **Never split:** names that contain a separator ("Tyler, The Creator", "Earth, Wind & Fire", "Simon & Garfunkel"...).
- **Features in song titles:** on or off.

### Tidy your tags first: Fix Multiple Artists for Mp3tag

**[Fix Multiple Artists for Mp3tag](https://github.com/njrozen/mp3tag-fix-multiple-artists)** (also by njrozen) is a free Windows companion
for [Mp3tag](https://www.mp3tag.de/en/) with a one-click installer. Select songs in Mp3tag, then Right-click > Tools > Fix multiple artists.

It finds every song whose Artist tag lists more than one artist, however it is written ("A & B feat. C", "A / B", "A X B",
"A with B", "A (feat. B)"), highlights what separates the artists, and rewrites them all in one style you pick, with a preview
before anything is saved and an undo. Duo and band names ("Simon & Garfunkel", "Earth, Wind & Fire") can be kept whole, and you
can fix any separator it got wrong by hand.

With DualAmp, pick a style DualAmp already recognizes, for example `Artist, Artist feat. Artist`. Then every song is listed
under each of its artists without changing DualAmp's separators. Add the names you keep whole to DualAmp's **Never split** list too.

This works best with tidy, consistent artist tags. **Mp3tag actions** (in the release as `DualAmp-Mp3tag-actions.zip`, source in `mp3tag/`)
help: copy the `.mta` files to `%APPDATA%Mp3tagdatactions` and restart Mp3tag; they appear under Actions:

- **DualAmp - Move features from title to artist:** "Song (feat. B)" by "A" becomes "Song" by "A, B".
- **DualAmp - Separate artists with commas:** " feat. ", " ft. ", " featuring ", " & ", " x ", ";" and " / " between artists become ", ".
  It also splits band names like "Simon & Garfunkel", so use it on the files that need it.
- **DualAmp - Album artist from first artist (if empty):** a song by "A, B" with no album artist gets "A", so the release goes
  under the main artist.

## Streaming the visualizer

Settings > Stream visualizer shares the visualizer with any browser on the same Wi-Fi, like casting: open the address it shows (for example
`http://192.168.1.20:8340`) on a TV, PC or phone. Double-click for full screen; the song's title shows for a few seconds when it changes. The music
keeps playing on the device. The stream runs while DualAmp's visualizer is on screen (about 10-20 Mbit/s, 30 frames a second).
