package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every new translatable key must be present in every language the app offers. Older untranslated
 * keys are recorded in [legacyMissingKeys] until their locale catalogues are completed; without
 * that baseline this test fails on unrelated pre-existing gaps and hides new translation regressions.
 */
@RunWith(Parameterized::class)
class LocaleStringsTest(private val tag: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales(): List<String> = SUPPORTED_LANGUAGES.map { it.tag }.filter { it != "en" }

        private val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }

        private fun parse(file: File): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        /** Keys that need translating: `translatable="false"` entries are excluded. */
        private fun translatableKeys(doc: Document): Set<String> {
            val keys = mutableSetOf<String>()
            for (tagName in listOf("string", "plurals", "string-array")) {
                val nodes = doc.getElementsByTagName(tagName)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("translatable") == "false") continue
                    val name = element.getAttribute("name")
                    if (name.isNotEmpty()) keys.add(name)
                }
            }
            return keys
        }

        private val baseKeys: Set<String> by lazy {
            translatableKeys(parse(File(resDir, "values/strings.xml")))
        }

        /** Existing English-fallback strings; newly added keys must not be added here. */
        private val legacyMissingKeys = setOf(
            "automix_art_transition",
            "automix_art_transition_subtitle",
            "automix_mini_art_transition",
            "automix_mini_art_transition_subtitle",
            "replay_year_albums",
            "replay_year_albums_word",
            "replay_year_all_day",
            "replay_year_all_day_detail",
            "replay_year_artist_start",
            "replay_year_artists_word",
            "replay_year_building",
            "replay_year_busiest_minutes",
            "replay_year_busiest_song",
            "replay_year_busiest_start",
            "replay_year_clock_start",
            "replay_year_daytime",
            "replay_year_daytime_detail",
            "replay_year_discovery_start",
            "replay_year_early_bird",
            "replay_year_early_bird_detail",
            "replay_year_genres_start",
            "replay_year_golden_hour",
            "replay_year_golden_hour_detail",
            "replay_year_minutes_days_end",
            "replay_year_minutes_days_start",
            "replay_year_minutes_days_value",
            "replay_year_minutes_hours_value",
            "replay_year_minutes_start",
            "replay_year_minutes_together",
            "replay_year_minutes_word",
            "replay_year_months",
            "replay_year_mute",
            "replay_year_night_owl",
            "replay_year_night_owl_detail",
            "replay_year_open",
            "replay_year_opener_detail",
            "replay_year_opener_end",
            "replay_year_opener_start",
            "replay_year_played_times",
            "replay_year_song_start",
            "replay_year_songs_word",
            "replay_year_summary_artists",
            "replay_year_summary_minutes",
            "replay_year_summary_songs",
            "replay_year_summary_title",
            "replay_year_top_artists",
            "replay_year_top_songs",
            "replay_year_unmute",
            "show_best_part",
            "show_best_part_subtitle",
        )

        /**
         * App language tags are BCP-47 (`zh-CN` / `zh-TW`) while Android
         * resource directories use `-r` regions (`values-zh`, `values-zh-rTW`).
         * Mirrors what Android resolves at runtime: Simplified is the bare
         * `zh` catalogue, Traditional is `zh-rTW`.
         */
        internal fun tagToQualifier(tag: String): String = when (tag) {
            "zh-CN" -> "zh"
            "zh-TW" -> "zh-rTW"
            else -> tag
        }
    }

    @Test
    fun strings_containsAllBaseKeys() {
        val qualifier = tagToQualifier(tag)
        val localeFile = File(resDir, "values-$qualifier/strings.xml")
        assertTrue("values-$qualifier/strings.xml must exist (for language tag $tag)", localeFile.exists())

        val missingKeys = (baseKeys - translatableKeys(parse(localeFile))) - legacyMissingKeys
        assertTrue(
            "values-$qualifier/strings.xml (language $tag) has new untranslated keys: $missingKeys",
            missingKeys.isEmpty(),
        )
    }
}
