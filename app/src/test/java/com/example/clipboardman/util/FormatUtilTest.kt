package com.example.clipboardman.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilTest {

    @Test
    fun `zero and null return empty string`() {
        assertEquals("", formatFileSize(null))
        assertEquals("", formatFileSize(0L))
    }

    @Test
    fun `bytes shown as B`() {
        assertEquals("512 B", formatFileSize(512L))
    }

    @Test
    fun `kilobytes shown with one decimal`() {
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("512.0 KB", formatFileSize(524288L))
    }

    @Test
    fun `megabytes shown with one decimal`() {
        assertEquals("1.5 MB", formatFileSize(1572864L))
    }

    @Test
    fun `exactly 1 KB`() {
        assertEquals("1.0 KB", formatFileSize(1024L))
    }

    @Test
    fun `exactly 1 MB`() {
        assertEquals("1.0 MB", formatFileSize(1048576L))
    }
}
