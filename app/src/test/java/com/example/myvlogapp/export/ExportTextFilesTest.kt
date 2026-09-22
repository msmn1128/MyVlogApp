package com.example.myvlogapp.export

import com.example.myvlogapp.TextSegment
import com.example.myvlogapp.testClip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExportTextFilesTest {

    private val workDir: File = Files.createTempDirectory("vlog_text_files_test").toFile()
    private val textFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    @Test
    fun `ひとことは改行コードの種類によらず同じ行に分かれ、行に改行コードが残らない`() {
        val clip = testClip(texts = listOf(TextSegment(0L, "一行目\r\n二行目\r三行目\n四行目")))

        val lines = writeSpanTextFiles(workDir, 1L, 0, clip, textFiles).single().lineFiles

        assertEquals(listOf("一行目", "二行目", "三行目", "四行目"), lines.map { it?.readText() })
    }

    @Test
    fun `ひとことの空行は位置だけ残して描かない`() {
        val clip = testClip(texts = listOf(TextSegment(0L, "上\r\n\r\n下")))

        val lines = writeSpanTextFiles(workDir, 1L, 0, clip, textFiles).single().lineFiles

        assertEquals(3, lines.size)
        assertNull(lines[1])
    }

    @Test
    fun `タイトルも改行コードの種類によらず行に分かれ、空行は詰める`() {
        val files = writeTitleTextFiles(workDir, 1L, "2026/09/22\r\n\r\n旅行", textFiles)

        assertEquals(listOf("2026/09/22", "旅行"), files.map { it.readText() })
    }
}
