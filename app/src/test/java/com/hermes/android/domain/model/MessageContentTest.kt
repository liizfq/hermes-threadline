package com.hermes.android.domain.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageContentTest {

    @Test
    fun `Text content holds html and plainText`() {
        val text = MessageContent.Text("<b>Bold</b>", "Bold")
        assertEquals("<b>Bold</b>", text.html)
        assertEquals("Bold", text.plainText)
    }

    @Test
    fun `Image content holds mxcUrl and dimensions`() {
        val image = MessageContent.Image("mxc://server/img123", 1920, 1080)
        assertEquals("mxc://server/img123", image.mxcUrl)
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
    }

    @Test
    fun `Image content allows null dimensions`() {
        val image = MessageContent.Image("mxc://server/img456", null, null)
        assertNull(image.width)
        assertNull(image.height)
    }

    @Test
    fun `Audio content holds mxcUrl and duration`() {
        val audio = MessageContent.Audio("mxc://server/audio", 30000L)
        assertEquals("mxc://server/audio", audio.mxcUrl)
        assertEquals(30000L, audio.duration)
    }

    @Test
    fun `File content holds mxcUrl, fileName, and fileSize`() {
        val file = MessageContent.File("mxc://server/file", "doc.pdf", 1024L)
        assertEquals("mxc://server/file", file.mxcUrl)
        assertEquals("doc.pdf", file.fileName)
        assertEquals(1024L, file.fileSize)
    }

    @Test
    fun `sealed class variants are distinct`() {
        val text: MessageContent = MessageContent.Text("a", "a")
        val image: MessageContent = MessageContent.Image("mxc://x", null, null)
        val audio: MessageContent = MessageContent.Audio("mxc://x", null)
        val file: MessageContent = MessageContent.File("mxc://x", "f", null)

        assertTrue(text is MessageContent.Text)
        assertFalse(text is MessageContent.Image)
        assertTrue(image is MessageContent.Image)
        assertTrue(audio is MessageContent.Audio)
        assertTrue(file is MessageContent.File)
    }
}
