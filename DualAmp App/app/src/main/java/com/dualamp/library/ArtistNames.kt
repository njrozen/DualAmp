package com.dualamp.library

import com.dualamp.settings.Prefs

/**
 * Songs with more than one artist ("Kanye West, JAY-Z", "Drake feat. Rihanna", "Song (feat. X)") are listed under each
 * of them. The separators and the names never to split are set in Settings > Multiple artists.
 *
 * Works best with tidy, consistent artist tags (the same spelling and separator everywhere); the Mp3tag action in the
 * GitHub release helps with that.
 */
object ArtistNames {
    /** Defaults for Settings > Multiple artists > Separators, one per line. Spaces matter (" x " is not "x"). */
    val DEFAULT_SEPARATORS = listOf(", ", "; ", " & ", " feat. ", " feat ", " ft. ", " featuring ", " with ", " x ", " / ")
    /** Defaults for Settings > Multiple artists > Never split: names that contain a separator. */
    val DEFAULT_KEEP = listOf(
        "Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash",
        "Simon & Garfunkel", "Hall & Oates", "Daryl Hall & John Oates", "Florence + The Machine", "Peter, Paul and Mary",
        "Mumford & Sons", "Iron & Wine", "Chase & Status", "Above & Beyond", "Sonny & Cher", "Brooks & Dunn",
        "Big & Rich", "Love and Rockets", "Eric B. & Rakim", "DJ Jazzy Jeff & The Fresh Prince", "Kool & The Gang",
    )

    fun separators(): List<String> = Prefs.artistSeparators.value.split('\n').filter { it.isNotEmpty() }
    fun keep(): List<String> = Prefs.artistKeep.value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    // "(feat. X)", "[ft. X & Y]", " feat. X" at the end of a title.
    private val TITLE_FEATURE = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]+)[)\]]|\s(?:feat\.?|ft\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Cache: splitting runs for every song when the library is grouped. */
    private var cacheKey = ""
    private val cache = HashMap<String, List<String>>()

    /** The artists in one tag, in order, each once (by [artistKey]). Off in Settings: the tag as it is. */
    fun split(tag: String): List<String> {
        val name = tag.trim()
        if (name.isEmpty()) return emptyList()
        if (!Prefs.splitArtists.value) return listOf(name)
        val key = Prefs.artistSeparators.value + "\u0000" + Prefs.artistKeep.value
        synchronized(cache) {
            if (key != cacheKey) { cache.clear(); cacheKey = key }
            cache[name]?.let { return it }
        }
        val result = splitWith(name, separators(), keep())
        synchronized(cache) { cache[name] = result }
        return result
    }

    /** [split] with explicit rules (also used by Settings to preview). */
    fun splitWith(tag: String, separators: List<String>, keep: List<String>): List<String> {
        // Names that must stay whole are swapped for placeholders first, so their own commas or "&" do not split them.
        var text = tag.trim()
        val kept = ArrayList<String>()
        for (k in keep.sortedByDescending { it.length }) {
            var at = text.indexOf(k, ignoreCase = true)
            while (at >= 0) {
                kept += text.substring(at, at + k.length)
                val mark = "\u0001${kept.size - 1}\u0001"
                text = text.substring(0, at) + mark + text.substring(at + k.length)
                at = text.indexOf(k, at + mark.length, ignoreCase = true)
            }
        }
        var parts = listOf(text)
        for (sep in separators.sortedByDescending { it.length }) {
            parts = parts.flatMap { p -> p.split(sep, ignoreCase = true) }
        }
        val restore = Regex("\u0001(\\d+)\u0001")
        return parts.asSequence()
            .map { restore.replace(it) { m -> kept[m.groupValues[1].toInt()] } }
            .map { it.trim().trim('(', ')', '[', ']').trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { artistKey(it) }
            .toList()
    }

    /** Artists named in a title ("Song (feat. A & B)"), when Settings > Multiple artists > From titles is on. */
    fun fromTitle(title: String): List<String> {
        if (!Prefs.splitArtists.value || !Prefs.artistsFromTitles.value) return emptyList()
        val m = TITLE_FEATURE.find(title) ?: return emptyList()
        val names = m.groupValues[1].ifBlank { m.groupValues[2] }
        return split(names)
    }

    /** Everyone credited on [song]: its artist tag and features in the title (the album artist is separate). */
    fun credited(song: Song): List<String> = (split(song.artist) + fromTitle(song.title)).distinctBy { artistKey(it) }
}
