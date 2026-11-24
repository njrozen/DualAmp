package com.dualamp.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GenresTest {
    @Test fun oneGenre() = assertEquals(listOf("Rock"), splitGenres(" Rock "))
    @Test fun semicolons() = assertEquals(listOf("Rock", "Punk"), splitGenres("Rock; Punk"))
    @Test fun commas() = assertEquals(listOf("Rock", "Punk", "Grunge"), splitGenres("Rock,Punk , Grunge"))
    @Test fun nullSeparated() = assertEquals(listOf("Rock", "Punk"), splitGenres("Rock\u0000Punk"))
    @Test fun slashStaysOneGenre() = assertEquals(listOf("Hip-Hop/Rap"), splitGenres("Hip-Hop/Rap"))
    @Test fun duplicatesAndBlanks() = assertEquals(listOf("Rock"), splitGenres("Rock;; rock ;"))
    @Test fun empty() = assertEquals(emptyList<String>(), splitGenres(""))
}
