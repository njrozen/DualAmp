package com.dualamp.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dualamp.ui.Accent
import com.dualamp.ui.IconTint
import com.dualamp.ui.TextMain
import com.dualamp.ui.Surface2
import com.dualamp.ui.TextDim

/** What every button and gesture does. Shown at launch and under Settings > Controls. */
object ControlsHelp {
    /** Shown once per app start (unless turned off), not every time the screen is rebuilt. */
    var shownThisLaunch = false

    /** What DualAmp does and how to use it (shown before the controls in the Help guide). */
    val features: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Welcome to DualAmp" to listOf(
            "Two screens" to "The top screen shows what's playing over a MilkDrop visualizer; the bottom screen is your library",
            "Your music" to "Add the folders that hold your music in Settings > Music folders. New files appear after a rescan",
            "Rotation" to "Auto orientation turns both screens; in portrait the D-pad turns with the screen",
            "Single-screen" to "Settings > Swap sides moves the library to the left / top; the grid has its own size there",
            "Volume" to "On the Thor, both screens: the top screen's volume. Single-screen: the volume of the screen DualAmp is on",
            "One screen" to "Settings > Use both screens: Top or Bottom Screen. Opening DualAmp on the bottom screen (or as a Cocoon companion app) also runs it there alone",
            "Lyrics" to "The lyrics button (top left of the player) or double-click the right stick (R3). From .lrc files next to your songs, the songs' tags, or lrclib.net. Settings > Lyrics: background, timing",
            "Stream" to "Settings > Stream visualizer: open the address it shows in a TV, PC or phone browser on the same Wi-Fi",
            "Tabs layout" to "Settings > Screens > Single-screen layout: Tabs. The library with tabs (top or bottom); the player bar opens the big player (back arrow or B closes it; swipe the cover to skip)",
            "Several artists" to "Songs with several artists (\"A, B\", \"A feat. B\") are listed under each. Settings > Multiple artists sets the separators",
        ),
        "Library" to listOf(
            "Tabs" to "Playlists, Liked Songs, Artists, Albums, Singles, Songs, Genres, Years, Folders and the Queue",
            "Search" to "The magnifier, top right. Artists come first, then albums, then song titles, then everything else",
            "Singles" to "A song alone on a release of the same name. A lone song from a bigger album still counts as an album",
            "Artist pages" to "All releases, Albums and Singles, newest first",
            "Cover Flow glow" to "A glow behind the cover the gamepad is on: color, shape (circle, oval, diamond, cross), strength and pop-in animation in Settings",
            "Launch view" to "Settings > View on launch: start in grid, list or Cover Flow, or keep whatever you used last",
            "Theme" to "Settings > Theme: Default, Light, OLED black or Midnight, and the accent color",
            "Views" to "Grid (3 to 6 per row), list or Cover Flow: Settings > Library view, shared everywhere or separate for categories and the pages inside them; sort by name, artist, year, date added and more",
            "Playlists" to "Create, rename and fill them from any item's menu. Playlist files in your folders show up too",
            "Queue" to "Play next / add to queue from any menu. Clearing keeps the current song",
        ),
        "Visualizer" to listOf(
            "Presets" to "Nearly 10,000 MilkDrop presets built in; import your own .milk folders in Settings",
            "Visualizer folders" to "Your own playlists of visualizers. The button at the top of the bottom screen opens them",
            "Changing" to "Every few seconds, on big beats, or only when you swipe / press L2; lock keeps the current one",
            "Arrows and L2" to "The arrows step through the folder in order and wrap round; L2 goes to the next or a random one",
            "Reactivity" to "Settings > Visualizer reactivity: higher makes it jump harder on beats, lower calms it. Quiet songs move it as much as loud ones",
            "Off" to "The eye button (or Settings) shows the album art instead and saves battery",
        ),
        "Sound" to listOf(
            "Equalizer" to "10 bands with presets, plus bass, treble, balance, stereo width, mono and a limiter",
        ),
        "PC music" to listOf(
            "DualAmp Server" to "A free program for Windows PCs (DualAmp Server.exe) that plays the PC's music on this device over Wi-Fi",
            "First launch" to "Its window asks for your music: foobar2000's library, if it finds one, or a folder you choose. It remembers your choice",
            "Changing music" to "Add, change or remove folders in the server's window; it shows its progress while it reads your music",
            "Connect" to "Each time you start DualAmp: Settings > PC music > Connect (or Find PC on Wi-Fi / Enter PC address). The PC tab then shows its music",
            "Connection" to "The PC tab shows a checkmark while connected and a spinning circle while looking for the PC (orange: lost)",
            "Not connecting" to "A pop-up tells you within a few seconds. Keep the server window open, use the same Wi-Fi, and allow it in Windows Firewall",
        ),
        "Lid closed" to listOf(
            "Buttons" to "With Settings > Lid-closed buttons on, L1 / R1 skip and R2 plays / pauses with the lid shut; hold R2 / L2 to fast forward / rewind",
            "Screen off" to "With Shoulder buttons while asleep on, a press wakes the Thor, acts, and puts it back to sleep",
        ),
    )

    val sections: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Gamepad" to listOf(
            "D-pad" to "Move around the list (left/right switch library tabs at the top level). Up from the top row: the categories (Playlists, Artists, Albums...; left / right switch), then the header (PC, Library, Equalizer, search, visualizers, settings); A picks, down goes back",
            "A" to "Open / play the selected item",
            "B" to "Back (also closes search)",
            "Y" to "Menu for the selected item: play next, add to queue, add to playlist…. Hold: like the song playing or add it to a playlist",
            "X / R2" to "Play / pause. Hold R2: fast forward",
            "Start" to "Settings (press again to close). Hold: visualizer folders",
            "L1 / R1" to "Previous / next song",
            "L2" to "Next visualizer, or a random one (Settings > L2 button). Hold: rewind",
            "L3 (press left stick)" to "Next library view (grid, list, Cover Flow). Hold: clear the queue, keeping the current song (asks first). Double-click (single-screen tabs layout): the big player",
            "R3 (press right stick)" to "Show / hide the song info on the top screen; double-click: lyrics on / off",
            "Select" to "Next tab: PC, Library, Equalizer. Hold: search with the keyboard open",
        ),
        "Top screen (touch)" to listOf(
            "Tap" to "Show / hide the song info",
            "Swipe left / right, or the arrows" to "Next / previous visualizer in the folder you are using (wraps round)",
            "Tap the visualizer name" to "Add it to a visualizer folder",
            "Long-press the album art" to "Play next, add to queue / playlist / Liked Songs, go to album or artist",
            "Eye button" to "Visualizer on / off (off shows the album art)",
            "Folder button" to "Choose what the visualizer cycles through",
            "Lock button" to "Keep the current visualizer",
            "Waveform" to "Tap or drag to jump in the song",
        ),
        "Bottom screen (touch)" to listOf(
            "Tap" to "Open / play",
            "Long-press" to "Item menu (same as Y)",
            "Select multiple (in the item menu)" to "Then tap / A to tick more songs, albums or artists; the bar adds them all to the queue or a playlist",
            "View button" to "List, grid or Cover Flow; the library categories share one view",
            "Player bar" to "Add to playlist, previous, play/pause, next; tap the song for the queue, long-press it for album / artist",
            "Search / name keyboard" to "D-pad + A type, X delete, Y space, L1/R1 move cursor, L2 letters / symbols, Start done, B close (nothing typed: leaves search)",
            "Header tabs" to "PC music, Library and Equalizer, shown as icons (the title says which one you are in)",
            "Header buttons" to "Search; visualizer folders and Settings, each its own tab (press again to go back). The DualAmp logo shows About",
        ),
        "With the screen off or lid closed" to listOf(
            "L1 / R1 / R2" to "Previous / next / play-pause. Lid shut: hold R2 / L2 to fast forward / rewind",
            "Needs" to "Settings > Lid-closed buttons, and Shoulder buttons while asleep (Accessibility)",
        ),
    )
}

/** Help guide (features, then controls): at launch and from Settings > Help. A closes it; Y ticks "Don't show again"; up / down scroll. */
@Composable
internal fun ControlsOverlay(
    dontShowAgain: Boolean,
    onToggleDontShow: () -> Unit,
    onClose: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        // Narrow (half of the bottom screen in single-screen mode): each control above its description.
        val narrow = maxWidth < 420.dp
        Surface(
            color = Surface2,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 12.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                Text("DualAmp help", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn(
                    Modifier.weight(1f, fill = false).heightIn(max = 560.dp),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    (ControlsHelp.features + ControlsHelp.sections).forEach { (title, rows) ->
                        item {
                            Text(title, color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        items(rows) { (control, what) ->
                            if (narrow) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(control, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(what, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                            } else Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(control, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(150.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(what, color = TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                val dontShow: @Composable () -> Unit = {
                    Row(Modifier.clickable(onClick = onToggleDontShow), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShowAgain, onCheckedChange = { onToggleDontShow() },
                            colors = CheckboxDefaults.colors(checkedColor = Accent))
                        Text("Don't show again (Y)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (narrow) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.End) {
                    Row(Modifier.fillMaxWidth()) { dontShow() }
                    Button(onClick = onClose) { Text("Got it (A)") }
                } else Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    dontShow()
                    Button(onClick = onClose) { Text("Got it (A)") }
                }
            }
        }
    }
}

/** First-launch prompts. */
internal enum class Onboarding(val title: String, val body: String, val primary: String, val secondary: String) {
    FOLDER(
        "Add your music",
        "Choose the folder that holds your music, for example a folder on your SD card. " +
            "You can skip this and add folders later in Settings > Music folders.",
        "Choose folder (A)", "Later (B)",
    ),
    ACCESSIBILITY(
        "Shoulder buttons with the lid closed",
        "To skip songs (L1 / R1) and play or pause (R2) while the Thor is asleep, turn on DualAmp in " +
            "Accessibility settings (Installed apps > DualAmp). It only reacts to those buttons while the screen " +
            "is off, so games are not affected. You can do this later in Settings > Shoulder buttons while asleep.",
        "Open settings (A)", "Not now (B)",
    ),
}

@Composable
internal fun OnboardingOverlay(step: Onboarding, onPrimary: () -> Unit, onSecondary: () -> Unit) =
    PromptOverlay(step.title, step.body, step.primary, step.secondary, onPrimary, onSecondary)

/** A question in a box over the screen: title, text, and two buttons (A and B on the gamepad). */
@Composable
internal fun PromptOverlay(title: String, body: String, primary: String, secondary: String, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Surface(color = Surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.heightIn(min = 10.dp))
                Text(body, color = TextDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.heightIn(min = 16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(onClick = onSecondary) { Text(secondary, color = TextMain) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onPrimary) { Text(primary) }
                }
            }
        }
    }
}

/** About: app name, version, the device it runs on, and credits. A / B or a tap closes it. */
@Composable
internal fun AboutOverlay(onClose: () -> Unit) {
    // "AYN Thor", not "AYN AYN Thor": the model often already starts with the maker.
    val maker = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL
    val device = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        // Narrow (half the bottom screen, single-screen side by side): wider box, each label above its value, and it
        // scrolls if it is still too tall.
        val narrow = maxWidth < 420.dp
        val short = maxHeight < 420.dp
        Surface(color = Surface2, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(if (narrow) 0.92f else 0.7f)) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(if (narrow) 16.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(if (short) 52.dp else 72.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF120F1F)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(com.dualamp.R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(if (short) 78.dp else 108.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("DualAmp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Version ${com.dualamp.BuildConfig.VERSION_NAME}", color = TextDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                AboutRow("Running on", "$device · Android ${android.os.Build.VERSION.RELEASE}", narrow)
                AboutRow("Made by", "njrozen and Claude", narrow)
                AboutRow("Visualizer", "projectM (MilkDrop compatible)", narrow)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("Close (A)") }
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, stacked: Boolean = false) {
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, color = TextDim, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** Shown when DualAmp can't reach DualAmp Server on the PC. */
internal fun pcProblemText(problem: String) = problem + "\n\n" +
    "• Is DualAmp Server running on the PC, with its window open?\n" +
    "• Are the phone and the PC on the same Wi-Fi?\n" +
    "• Windows Firewall: allow DualAmp Server (Windows asks the first time it runs), or set the PC's Wi-Fi network to Private.\n\n" +
    "Songs on the phone keep playing as normal."
