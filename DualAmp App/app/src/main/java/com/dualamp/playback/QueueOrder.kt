package com.dualamp.playback

/**
 * The play order after [count] songs are added at list position [listIndex] and should play right after play-order
 * position [after]. [order] is the play order before (list positions in the order they play; shuffled or not).
 * Positions at or past [listIndex] move up by [count], as the list's do.
 */
fun orderWithInserted(order: List<Int>, after: Int, listIndex: Int, count: Int): List<Int> {
    val out = order.map { if (it >= listIndex) it + count else it }.toMutableList()
    out.addAll(after + 1, (listIndex until listIndex + count).toList())
    return out
}
