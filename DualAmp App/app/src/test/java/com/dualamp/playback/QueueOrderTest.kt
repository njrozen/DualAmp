package com.dualamp.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOrderTest {
    @Test fun inOrderAfterCurrent() =
        // Playing 1 of 0..3; two songs go in at list position 2 and play next.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), orderWithInserted(listOf(0, 1, 2, 3), after = 1, listIndex = 2, count = 2))

    @Test fun shuffledAfterCurrent() =
        // Shuffled 3,0,2,1 playing 0 (play position 1): new songs at list 1 play next; 2 and 1 shift up.
        assertEquals(listOf(4, 0, 1, 3, 2), orderWithInserted(listOf(3, 0, 2, 1), after = 1, listIndex = 1, count = 1))

    @Test fun afterEarlierQueued() =
        // Playing 2; 5 was queued after it (play position 1). Add to queue goes after 5, at list position 6.
        assertEquals(listOf(2, 5, 6, 7, 0, 1, 3, 4), orderWithInserted(listOf(2, 5, 0, 1, 3, 4), after = 1, listIndex = 6, count = 2))

    @Test fun atTheEnd() =
        assertEquals(listOf(1, 0, 2), orderWithInserted(listOf(1, 0), after = 1, listIndex = 2, count = 1))
}
