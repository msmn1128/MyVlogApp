package com.example.myvlogapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 画面に出す文言と表示の整形（Formatters.kt）

class FormattersTest {

    @Test
    fun formatSeconds_isMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", formatSeconds(0L))
        assertEquals("0:59", formatSeconds(59_000L))
        assertEquals("1:01", formatSeconds(61_000L))
        assertEquals("10:00", formatSeconds(600_000L))
    }

    @Test
    fun formatSeconds_roundsToTheNearestSecond() {
        // 切り捨てだと、3.2〜15.1秒が「0:03 〜 0:15（0:11）」になり、引き算と合わなく見えていた
        assertEquals("0:03", formatSeconds(3_499L))
        assertEquals("0:04", formatSeconds(3_500L))
        assertEquals("1:00", formatSeconds(59_999L))
    }

    @Test
    fun roundedTrim_alwaysMatchesTheDifferenceOfTheDisplayedEnds() {
        // 3.2〜15.1秒（11.9秒）→「0:03 〜 0:15（0:12）」
        assertEquals("0:12", formatSeconds(roundedTrimMs(3_200L, 15_100L)))
        // 3.5〜15.4秒（11.9秒）→「0:04 〜 0:15」。長さを丸めると0:12でずれるが、両端の差なら0:11
        assertEquals("0:11", formatSeconds(roundedTrimMs(3_500L, 15_400L)))
        // 前後が入れ替わった値でも負にならない
        assertEquals(0L, roundedTrimMs(5_000L, 2_000L))
    }

    @Test
    fun uniqueSaveName_keepsANewNameAsIs() {
        assertEquals("旅行", uniqueSaveName("旅行", emptyList()))
        assertEquals("旅行", uniqueSaveName("旅行", listOf("別の名前", "旅行2")))
    }

    @Test
    fun uniqueSaveName_appendsTheFirstFreeNumberForATypedName() {
        assertEquals("旅行 (1)", uniqueSaveName("旅行", listOf("旅行")))
        assertEquals("旅行 (2)", uniqueSaveName("旅行", listOf("旅行", "旅行 (1)")))
        // 途中が空いていれば、そこを使う（消した番号の再利用）
        assertEquals("旅行 (1)", uniqueSaveName("旅行", listOf("旅行", "旅行 (2)")))
    }

    @Test
    fun uniqueSaveName_treatsNamesAsCaseAndSpaceSensitive() {
        // 完全一致だけを重複とみなす（「旅行」と「旅行 」は別の名前）
        assertEquals("abc", uniqueSaveName("abc", listOf("ABC")))
        assertEquals("旅行", uniqueSaveName("旅行", listOf("旅行 ")))
    }

    @Test
    fun uniqueSaveName_putsTheNumberBeforeTheSuffixNotAfterIt() {
        // 書き出しファイル名はこちらを使う。拡張子は連番の外側に来ること
        assertEquals("Vlog_2026-09-20.mp4", uniqueSaveName("Vlog_2026-09-20", emptyList(), ".mp4"))
        assertEquals(
            "Vlog_2026-09-20 (1).mp4",
            uniqueSaveName("Vlog_2026-09-20", listOf("Vlog_2026-09-20.mp4"), ".mp4")
        )
        assertEquals(
            "Vlog_2026-09-20 (2).mp4",
            uniqueSaveName(
                "Vlog_2026-09-20",
                listOf("Vlog_2026-09-20.mp4", "Vlog_2026-09-20 (1).mp4"),
                ".mp4"
            )
        )
    }

    @Test
    fun uniqueSaveName_ignoresNamesThatOnlyMatchWithoutTheSuffix() {
        // 拡張子まで含めた完全一致だけが重複。連番なしの名前と衝突させない
        assertEquals(
            "Vlog_2026-09-20.mp4",
            uniqueSaveName("Vlog_2026-09-20", listOf("Vlog_2026-09-20"), ".mp4")
        )
    }

    @Test
    fun defaultSaveName_appendsFirstUnusedNumber() {
        val now = 1_800_000_000_000L
        val base = defaultSaveName(now, emptyList())

        assertEquals(base, defaultSaveName(now, listOf("別の名前")))
        assertEquals("$base (1)", defaultSaveName(now, listOf(base)))
        assertEquals("$base (2)", defaultSaveName(now, listOf(base, "$base (1)")))
    }
}

class AddSkipMessageTest {

    @Test
    fun nothingSkippedMeansNoMessage() {
        assertNull(addSkipMessage(alreadyAdded = 0, unreadable = 0))
    }

    @Test
    fun alreadyAddedOnly() {
        assertEquals("2 件は追加済みのためスキップしました", addSkipMessage(alreadyAdded = 2, unreadable = 0))
    }

    @Test
    fun unreadableOnlyTellsToSelectAgain() {
        assertEquals(
            "1 件は読み込めなかったので追加しませんでした（もう一度選び直してください）",
            addSkipMessage(alreadyAdded = 0, unreadable = 1)
        )
    }

    @Test
    fun overLimitTellsTheLimit() {
        assertEquals(
            "3 件は上限（100本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 0, unreadable = 0, overLimit = 3)
        )
        assertEquals(
            "3 件は上限（50本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 0, unreadable = 0, overLimit = 3, limit = 50)
        )
    }

    @Test
    fun theLimitIs100() {
        // 以前はメモリの実測に基づく上限だったが、区切りごとの書き出し（export/Segments.kt）で
        // メモリは本数によらず一定になった。いまは書き出し時間・中間ファイルの容量・操作性のための値
        // （VlogConstants.MAX_CLIPS）。変えるときは、100本の書き出し時間と空き容量の見積もりを見直すこと
        assertEquals(100, MAX_CLIPS)
    }

    @Test
    fun allThreeKindsAreReportedInOneMessage() {
        assertEquals(
            "1 件は追加済みのためスキップしました\n" +
                "2 件は読み込めなかったので追加しませんでした（もう一度選び直してください）\n" +
                "3 件は上限（100本）を超えるため追加しませんでした",
            addSkipMessage(alreadyAdded = 1, unreadable = 2, overLimit = 3)
        )
    }

    @Test
    fun bothAreReportedInOneMessage() {
        assertEquals(
            "2 件は追加済みのためスキップしました\n" +
                "1 件は読み込めなかったので追加しませんでした（もう一度選び直してください）",
            addSkipMessage(alreadyAdded = 2, unreadable = 1)
        )
    }
}

class CanReplaceWithProjectTest {

    @Test
    fun aProjectWithReadableClipsCanBeLoaded() {
        assertTrue(canReplaceWithProject(loaded = 5, dropped = 0))
    }

    @Test
    fun partiallyReadableProjectCanBeLoaded() {
        assertTrue(canReplaceWithProject(loaded = 3, dropped = 2))
    }

    @Test
    fun aProjectWhoseClipsAreAllGoneIsRefusedToProtectTheCurrentTimeline() {
        assertFalse(canReplaceWithProject(loaded = 0, dropped = 4))
    }

    @Test
    fun anEmptyProjectCanStillBeLoaded() {
        // 保存自体が空（読めなかった動画も無い）なら、そのまま読み出せる
        assertTrue(canReplaceWithProject(loaded = 0, dropped = 0))
    }
}

class ProjectMessageTest {

    @Test
    fun loadedMessageAlwaysMentionsUndo() {
        assertEquals("読み出しました（もとに戻すで読み出す前へ戻ります）", projectLoadedMessage(dropped = 0))
        // 一部が見つからないときも、もとに戻せることを案内する
        assertEquals(
            "読み出しました（2 件の動画は見つかりませんでした）。もとに戻すで読み出す前へ戻ります",
            projectLoadedMessage(dropped = 2)
        )
    }

    @Test
    fun unreadableMessageSaysNothingWasReplaced() {
        val message = projectUnreadableMessage(dropped = 3)
        assertTrue(message.contains("3 件とも見つからない"))
        assertTrue(message.contains("読み出しませんでした"))
    }
}
