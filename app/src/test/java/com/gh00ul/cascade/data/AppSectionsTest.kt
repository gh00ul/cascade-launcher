package com.gh00ul.cascade.data

import android.app.Application
import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A–Z sections and order on Robolectric's real ICU data. Every case also checks what LauncherScreen's rows rely
 * on: each section is one run (so no header repeats) and "#" comes first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppSectionsTest {
    /** [labels] in A–Z list order for [languages] (BCP 47 tags, the first one primary), each with its section. */
    private fun sectioned(languages: String, vararg labels: String): List<Pair<String, String>> =
        sortIntoSections(labels.toList(), { it }, LocaleList.forLanguageTags(languages))
            .also { assertOneRunPerSection(it, languages) }

    private fun assertOneRunPerSection(sorted: List<Pair<*, String>>, languages: String) {
        val sections = sorted.map { it.second }
        val runs = sections.zipWithNext().count { (a, b) -> a != b } + if (sections.isEmpty()) 0 else 1
        assertEquals("a section split in $languages: $sections", sections.distinct().size, runs)
        assertTrue("\"#\" not first in $languages: $sections", sections.indexOf("#") <= 0)
    }

    @Test fun accentedLatinFilesUnderTheBaseLetter() {
        assertEquals(
            listOf("Ångström" to "A", "Éclair" to "E", "Übersetzer" to "U"),
            sectioned("en-US", "Übersetzer", "Éclair", "Ångström"),
        )
    }

    @Test fun digitsSymbolsEmojiAndBlankLabelsGoUnderHashFirst() {
        val result = sectioned("en-US", "Maps", "2048", "@Home", "#hashtag", "🎮 Games", "", "   ")
        assertEquals("Maps" to "M", result.last())
        assertEquals(setOf("2048", "@Home", "#hashtag", "🎮 Games", "", "   "), result.dropLast(1).map { it.first }.toSet())
        assertTrue(result.dropLast(1).all { it.second == "#" })
    }

    @Test fun leadingSpacesAreIgnoredForSectionAndOrder() {
        // ICU doesn't ignore spaces: sorted by the untrimmed label, " Maps" came before "Mail" and " Zoom" before "Zebra".
        assertEquals(
            listOf("Mail" to "M", " Maps" to "M", "Music" to "M", "Zebra" to "Z", " Zoom" to "Z"),
            sectioned("en-US", " Zoom", "Music", "Zebra", " Maps", "Mail"),
        )
    }

    @Test fun hanWithoutChineseGoesUnderHash() {
        // No Chinese language, no pinyin sections: Han sorts after every alphabet with labels, into ICU's overflow bucket.
        assertEquals(listOf("微信" to "#", "Maps" to "M"), sectioned("en-US", "Maps", "微信"))
        // Japanese has kana rows but no kanji sections either (ICU files Han in an inflow bucket there). Japanese
        // collation puts kana and Han before Latin, so the kana rows come before A–Z.
        assertEquals(
            listOf("微信" to "#", "カメラ" to "か", "かんたん" to "か", "ゆうちょ" to "や", "Zoom" to "Z"),
            sectioned("ja-JP", "ゆうちょ", "微信", "Zoom", "かんたん", "カメラ"),
        )
    }

    @Test fun greekAndCyrillicGetTheirOwnLettersAfterLatin() {
        // The Greek Epsilon and Cyrillic Te, not the Latin E and T.
        assertEquals(
            listOf("Apple" to "A", "Zoom" to "Z", "Ελληνικά" to "Ε", "Телеграм" to "Т", "Яндекс" to "Я"),
            sectioned("en-US", "Телеграм", "Ελληνικά", "Zoom", "Яндекс", "Apple"),
        )
    }

    @Test fun koreanFilesUnderInitialConsonantAndJapaneseUnderKanaRow() {
        // Katakana and hiragana share a row; Hangul comes before kana, as in ICU's root order.
        assertEquals(
            listOf("카카오톡" to "ㅋ", "カメラ" to "か", "かんたん" to "か", "ゆうちょ" to "や"),
            sectioned("en-US", "ゆうちょ", "かんたん", "카카오톡", "カメラ"),
        )
    }

    @Test fun swedishLettersGetTheirOwnSectionsAfterZ() {
        assertEquals(
            listOf("Apple" to "A", "Zoom" to "Z", "Åka" to "Å", "Ärlig" to "Ä", "Öppet" to "Ö"),
            sectioned("sv-SE", "Öppet", "Zoom", "Ärlig", "Åka", "Apple"),
        )
    }

    @Test fun thePrimaryLanguageDecidesTheAlphabet() {
        // Swedish as a second language adds no Å, Ä, Ö sections: in English they are an accented A and O.
        assertEquals(
            listOf("Åka" to "A", "Ärlig" to "A", "Öppet" to "O", "Zoom" to "Z"),
            sectioned("en-US,sv-SE", "Zoom", "Öppet", "Ärlig", "Åka"),
        )
    }

    @Test fun czechChIsALetterBetweenHAndI() {
        assertEquals(
            listOf(
                "Calendar" to "C", "Čtečka" to "Č", "Hry" to "H", "Chrome" to "CH", "Instagram" to "I", "Rádio" to "R",
                "Řízek" to "Ř",
            ),
            sectioned("cs-CZ", "Řízek", "Instagram", "Chrome", "Rádio", "Hry", "Čtečka", "Calendar"),
        )
    }

    @Test fun chineseFilesHanUnderPinyinLetters() {
        // Han and Latin share the letter sections; Chinese collation puts Han first within one.
        assertEquals(
            listOf("淘宝" to "T", "Telegram" to "T", "微信" to "W", "支付宝" to "Z", "Zoom" to "Z"),
            sectioned("zh-CN", "Zoom", "微信", "Telegram", "支付宝", "淘宝"),
        )
    }

    @Test fun withinASectionCaseAndAccentsAreIgnored() {
        // By code point it would be Ant, Azure, alpha, ápple.
        assertEquals(listOf("alpha", "Ant", "ápple", "Azure"), sectioned("en-US", "Azure", "ápple", "Ant", "alpha").map { it.first })
    }

    @Test fun equalNamesKeepTheirInputOrder() {
        // Like an app and its work-profile twin: the collator ties them, so the list order stands.
        val apps = listOf("Éclair" to 0, "eclair" to 10, "ECLAIR" to 11, "Éclair" to 12)
        assertEquals(apps, sortIntoSections(apps, { it.first }, LocaleList.forLanguageTags("en-US")).map { it.first })
    }

    @Test fun everySectionIsOneRunInManyLanguages() {
        val labels = arrayOf(
            "2048", "@Home", "#hashtag", "🎮 Games", "", " ", " Zoom", "Zebra", "Éclair", "Ångström", "Übersetzer", "Straße",
            "ß", "Chrome", "Hry", "Čtečka", "Řízek", "Åka", "Ärlig", "Öppet", "Wiki", "Vlc", "Ελληνικά", "Ωmega", "Телеграм",
            "Ёлка", "Эльдорадо", "Яндекс", "Ґанок", "Ђак", "עברית", "عربي", "ไทย", "카카오톡", "가계부", "까치", "カメラ",
            "かんたん", "ゆうちょ", "ヴ", "ん", "微信", "支付宝", "淘宝", "高德地图", "ㄅ", "ㄱ", "Ⅻ", "½", "١٢٣", "Ⓐpp", "ﬁle",
            "Ｗｉｄｅ", "हिन्दी", "ᏣᎳᎩ",
        )
        val languages = listOf(
            "en-US", "sv-SE", "cs-CZ", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "ru-RU", "de-DE", "el-GR", "ar-EG", "he-IL",
            "th-TH", "uk-UA", "sr-RS", "hi-IN", "da-DK", "pl-PL", "tr-TR", "vi-VN", "en-US,zh-CN,ja-JP", "fr-FR,ru-RU",
            "ko-KR,zh-TW,sv-SE",
        )
        for (tags in languages) {
            // sectioned() checks the runs; nothing may go missing on the way.
            assertEquals(tags, labels.sorted(), sectioned(tags, *labels).map { it.first }.sorted())
        }
    }
}
