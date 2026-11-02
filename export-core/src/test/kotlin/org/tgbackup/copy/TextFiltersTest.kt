package org.tgbackup.copy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFiltersTest {
    @Test fun arabicLetterMatches() = assertTrue(containsArabic("مرحبا"))
    @Test fun arabicInsideHebrewMatches() = assertTrue(containsArabic("שלום مرحبا world"))
    @Test fun persianMatches() = assertTrue(containsArabic("سلام چطوری"))
    @Test fun hebrewDoesNotMatch() = assertFalse(containsArabic("שלום עולם"))
    @Test fun englishDoesNotMatch() = assertFalse(containsArabic("hello world"))
    @Test fun emptyDoesNotMatch() = assertFalse(containsArabic(""))
    @Test fun arabicCommaAloneDoesNotMatch() = assertFalse(containsArabic("שלום، עולם"))
    @Test fun arabicIndicDigitsDoNotMatch() = assertFalse(containsArabic("١٢٣ 456"))
    @Test fun emojiDoesNotMatch() = assertFalse(containsArabic("😀🔥 ok"))
    @Test fun presentationFormLetterMatches() = assertTrue(containsArabic("ﺍ"))
}
