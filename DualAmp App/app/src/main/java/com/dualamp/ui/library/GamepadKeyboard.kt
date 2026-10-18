package com.dualamp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dualamp.input.ControllerBus
import com.dualamp.ui.Accent
import com.dualamp.ui.Surface1
import com.dualamp.ui.Surface2
import com.dualamp.ui.Surface3
import com.dualamp.ui.TextDim

/**
 * On-screen keyboard for search that works with the gamepad (and touch):
 * D-pad moves, A types the key, X deletes, Y types a space, L1 / R1 move the text cursor, L2 (or the ?123 / ABC
 * key) switches between letters and numbers/symbols, Start finishes (closes the keyboard and keeps the results).
 */
class KeyboardState(initial: String) {
    var text by mutableStateOf(initial)
    var cursor by mutableIntStateOf(initial.length)
    var symbols by mutableStateOf(false)
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)

    val rows: List<List<String>> get() = if (symbols) SYMBOLS else LETTERS

    fun type(s: String) {
        text = text.substring(0, cursor) + s + text.substring(cursor)
        cursor += s.length
    }

    fun delete() {
        if (cursor == 0) return
        text = text.substring(0, cursor - 1) + text.substring(cursor)
        cursor--
    }

    fun moveCursor(by: Int) { cursor = (cursor + by).coerceIn(0, text.length) }

    fun toggleSymbols() {
        symbols = !symbols
        clampSelection()
    }

    private fun clampSelection() {
        row = row.coerceIn(0, rows.lastIndex)
        col = col.coerceIn(0, rows[row].lastIndex)
    }

    fun move(dRow: Int, dCol: Int) {
        if (dRow != 0) {
            // Keep roughly the same horizontal spot when moving between rows of different length.
            val frac = (col + 0.5f) / rows[row].size
            row = (row + dRow).mod(rows.size)
            col = (frac * rows[row].size).toInt().coerceIn(0, rows[row].lastIndex)
        }
        if (dCol != 0) col = (col + dCol).mod(rows[row].size)
    }

    /** Presses the selected (or [key]) key. Returns true for Done. */
    fun press(key: String = rows[row][col]): Boolean {
        when (key) {
            KEY_SPACE -> type(" ")
            KEY_DELETE -> delete()
            KEY_SWITCH, KEY_LETTERS -> toggleSymbols()
            KEY_LEFT -> moveCursor(-1)
            KEY_RIGHT -> moveCursor(1)
            KEY_DONE -> return true
            else -> type(key)
        }
        return false
    }

    /** Handles a gamepad event. Returns true when the user finished (Start / Done). */
    fun handle(nav: ControllerBus.Nav): Boolean {
        when (nav) {
            ControllerBus.Nav.UP -> move(-1, 0)
            ControllerBus.Nav.DOWN -> move(1, 0)
            ControllerBus.Nav.LEFT -> move(0, -1)
            ControllerBus.Nav.RIGHT -> move(0, 1)
            ControllerBus.Nav.SELECT -> return press()
            ControllerBus.Nav.KB_DELETE -> delete()
            ControllerBus.Nav.KB_CURSOR_LEFT -> moveCursor(-1)
            ControllerBus.Nav.KB_CURSOR_RIGHT -> moveCursor(1)
            ControllerBus.Nav.KB_SPACE -> type(" ")
            ControllerBus.Nav.KB_SYMBOLS -> toggleSymbols()
            ControllerBus.Nav.OPEN_SETTINGS -> return true // Start = done
            else -> Unit
        }
        return false
    }

    companion object {
        const val KEY_SPACE = "space"
        const val KEY_DELETE = "⌫"
        const val KEY_SWITCH = "?123"
        const val KEY_LETTERS = "ABC"
        const val KEY_LEFT = "◀"
        const val KEY_RIGHT = "▶"
        const val KEY_DONE = "Done"

        private val LETTERS = listOf(
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() } + "'",
            "zxcvbnm".map { it.toString() } + listOf("-", KEY_DELETE),
            listOf(KEY_SWITCH, KEY_LEFT, KEY_SPACE, KEY_RIGHT, KEY_DONE),
        )
        private val SYMBOLS = listOf(
            "1234567890".map { it.toString() },
            listOf("!", "?", "&", "#", "$", "%", "(", ")", "@", "+"),
            listOf(".", ",", ":", ";", "/", "_", "*", "=", "\"", KEY_DELETE),
            listOf(KEY_LETTERS, KEY_LEFT, KEY_SPACE, KEY_RIGHT, KEY_DONE),
        )
    }
}

/** The typed text with a visible cursor (shown in the header while searching). */
@Composable
internal fun KeyboardTextDisplay(state: KeyboardState, onTap: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "Artists, albums, songs") {
    Box(
        modifier
            .height(44.dp)
            .background(Surface3, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (state.text.isEmpty()) {
            Text("|  $placeholder", color = TextDim, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        } else {
            val shown = state.text.substring(0, state.cursor) + "|" + state.text.substring(state.cursor)
            Text(shown, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
    }
}

@Composable
internal fun GamepadKeyboard(state: KeyboardState, highlight: Boolean, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.rows.forEachIndexed { r, keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEachIndexed { c, key ->
                    val selected = highlight && r == state.row && c == state.col
                    val wide = key == KeyboardState.KEY_SPACE
                    Box(
                        Modifier
                            .weight(if (wide) 3f else if (key.length > 1) 1.4f else 1f)
                            .height(46.dp)
                            .background(if (selected) Accent.copy(alpha = 0.3f) else Surface2, RoundedCornerShape(8.dp))
                            .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable {
                                state.row = r; state.col = c
                                if (state.press(key)) onDone()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(key, color = Color.White, fontWeight = if (key.length > 1) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Text("A type · X delete · Y space · L1/R1 cursor · L2 ?123/ABC · Start done · B close", color = TextDim,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}
