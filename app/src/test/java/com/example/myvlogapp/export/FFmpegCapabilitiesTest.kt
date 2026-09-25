package com.example.myvlogapp.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FFmpegの機能判定（FFmpegCapabilities.kt）のうち、`-filters` の一覧の読み方 */
class FFmpegCapabilitiesTest {

    /** `ffmpeg -hide_banner -filters` の出力の一部（6.0の形） */
    private val filterList = """
        Filters:
          T.. = Timeline support
          ...
         T.C afade             A->A       Fade in/out input audio.
         ... amovie            |->N       Read audio from a movie source.
         TSC drawtext          V->V       Draw text on top of video frames using libfreetype library.
         ... tonemap           V->V       Conversion to/from different dynamic ranges.
         ... zscale            V->V       Apply resizing, colorspace and bit depth conversion.
    """.trimIndent()

    @Test
    fun aListedFilterIsFound() {
        assertTrue(hasFilter(filterList, "drawtext"))
        assertTrue(hasFilter(filterList, "zscale"))
        assertTrue(hasFilter(filterList, "tonemap"))
    }

    @Test
    fun aFilterIsNotMistakenForAnotherThatEndsWithTheSameName() {
        // 単に含むかで見ると、movieがamovieに、fadeがafadeに一致してしまう。
        // amovieの説明文にある「movie」という単語にも一致させない
        assertFalse(hasFilter(filterList, "movie"))
        assertFalse(hasFilter(filterList, "fade"))
    }

    @Test
    fun anEmptyListHasNoFilters() {
        // 判定そのものに失敗した回（出力が空）は、どのフィルタも無いと読む
        assertFalse(hasFilter("", "drawtext"))
    }
}
