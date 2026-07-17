package com.hermes.android.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM (no Robolectric) tests for [MessageContentParser].
 *
 * Each test drives the parser directly and asserts on the returned
 * [MessageSegment] list. No Android types are touched, so the tests run
 * under plain JUnit 5 on the host JVM.
 */
class MessageContentParserTest {

    private val parser = MessageContentParser()

    // ── h1–h6 ──────────────────────────────────────────────────────────────

    @Test
    fun `parses all six heading levels with correct level`() {
        val html = """
            <h1>Level 1</h1>
            <h2>Level 2</h2>
            <h3>Level 3</h3>
            <h4>Level 4</h4>
            <h5>Level 5</h5>
            <h6>Level 6</h6>
        """.trimIndent()

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(6, segments.size)
        for ((index, segment) in segments.withIndex()) {
            val heading = segment as MessageSegment.Heading
            val expectedLevel = index + 1
            assertEquals(expectedLevel, heading.level, "segment[$index].level")
            assertEquals("Level $expectedLevel", heading.plainText)
        }
    }

    @Test
    fun `heading plain text equals inner text for plain heading`() {
        val html = "<h2>Project Notes</h2>"
        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val heading = segments[0] as MessageSegment.Heading
        assertEquals("Project Notes", heading.plainText)
        assertEquals("Project Notes", heading.html)
    }

    // ── repeated same-text headings stay distinct ─────────────────────────

    @Test
    fun `repeated identical headings are emitted as separate segments`() {
        val html = "<h2>Summary</h2><p>middle</p><h2>Summary</h2>"

        val segments = parser.parse(body = html, formattedBody = html)

        val headings = segments.filterIsInstance<MessageSegment.Heading>()
        assertEquals(2, headings.size, "two distinct <h2> segments expected")
        // Each must be its own instance — no deduplication.
        headings.forEach { h ->
            assertEquals(2, h.level)
            assertEquals("Summary", h.plainText)
        }
    }

    // ── heading preserves inner HTML (strong / link) ──────────────────────

    @Test
    fun `heading preserves strong and anchor in html`() {
        val href = "https://example.org/x"
        val html = """<h3>Hello <strong>world</strong> and <a href="$href">link</a></h3>"""

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val heading = segments[0] as MessageSegment.Heading
        assertEquals(3, heading.level)

        // Inner HTML is preserved (the <h3> wrapper itself is dropped).
        assertTrue(heading.html.contains("<strong>world</strong>"),
            "html must retain <strong>; was: ${heading.html}")
        assertTrue(heading.html.contains("<a"),
            "html must retain <a>; was: ${heading.html}")
        assertTrue(heading.html.contains("href=\"$href\""),
            "html must retain href attribute; was: ${heading.html}")

        // Plain text collapses tags but keeps inner text.
        assertEquals("Hello world and link", heading.plainText)
    }

    // ── ul / ol → Text(isListBlock = true) ────────────────────────────────

    @Test
    fun `unordered list is emitted as a list-block Text segment`() {
        val html = "<ul><li>alpha</li><li>beta</li></ul>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertTrue(text.isListBlock, "ul must set isListBlock=true")
        assertNotNull(text.html)
        assertTrue(text.html!!.contains("<ul"))
        assertTrue(text.html!!.contains("<li>alpha</li>"))
        assertEquals("alpha beta", text.plainText)
    }

    @Test
    fun `ordered list is emitted as a list-block Text segment`() {
        val html = "<ol><li>first</li><li>second</li></ol>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertTrue(text.isListBlock, "ol must set isListBlock=true")
        assertTrue(text.html!!.contains("<ol"))
        assertEquals("first second", text.plainText)
    }

    @Test
    fun `default Text segment is not a list block`() {
        val html = "<p>just a paragraph</p>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertFalse(text.isListBlock)
    }

    // ── p + ul + p ordering ────────────────────────────────────────────────

    @Test
    fun `paragraph list paragraph preserves source order`() {
        val html = "<p>Intro</p><ul><li>item</li></ul><p>Outro</p>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(3, segments.size)

        val first = segments[0] as MessageSegment.Text
        val middle = segments[1] as MessageSegment.Text
        val last = segments[2] as MessageSegment.Text

        assertFalse(first.isListBlock, "first segment is a paragraph")
        assertEquals("Intro", first.plainText)

        assertTrue(middle.isListBlock, "middle segment is the list")
        assertEquals("item", middle.plainText)

        assertFalse(last.isListBlock, "last segment is a paragraph")
        assertEquals("Outro", last.plainText)
    }

    // ── table ──────────────────────────────────────────────────────────────

    @Test
    fun `parses html table with headers and rows`() {
        val html = """
            <table>
              <thead>
                <tr><th>Name</th><th>Count</th></tr>
              </thead>
              <tbody>
                <tr><td>alpha</td><td>1</td></tr>
                <tr><td>beta</td><td>2</td></tr>
              </tbody>
            </table>
        """.trimIndent()

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val table = segments[0] as MessageSegment.Table
        assertEquals(listOf("Name", "Count"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("alpha", "1"), table.rows[0])
        assertEquals(listOf("beta", "2"), table.rows[1])
        assertEquals(2, table.alignments.size)
    }

    @Test
    fun `table column alignment parsed from style attribute`() {
        val html = """
            <table>
              <tr>
                <th style="text-align: center">C</th>
                <th style="text-align: right">R</th>
                <th>L</th>
              </tr>
              <tr><td>1</td><td>2</td><td>3</td></tr>
            </table>
        """.trimIndent()

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val table = segments[0] as MessageSegment.Table
        assertEquals(
            listOf(TableAlignment.CENTER, TableAlignment.RIGHT, TableAlignment.LEFT),
            table.alignments,
        )
    }

    // ── pre / code ─────────────────────────────────────────────────────────

    @Test
    fun `parses pre block as CodeBlock with language class`() {
        val html = """<pre><code class="language-kotlin">fun foo() = 42</code></pre>"""

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val block = segments[0] as MessageSegment.CodeBlock
        assertEquals("fun foo() = 42", block.code)
        assertEquals("kotlin", block.language)
    }

    @Test
    fun `pre block without language class yields null language`() {
        val html = "<pre><code>plain code\nline two</code></pre>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val block = segments[0] as MessageSegment.CodeBlock
        assertEquals("plain code\nline two", block.code)
        assertNull(block.language)
    }

    @Test
    fun `pre without code element uses wholeText`() {
        val html = "<pre>raw\ncode</pre>"

        val segments = parser.parse(body = html, formattedBody = html)
        assertEquals(1, segments.size)
        val block = segments[0] as MessageSegment.CodeBlock
        assertEquals("raw\ncode", block.code)
    }

    // ── formattedBody == null → plain text fallback ────────────────────────

    @Test
    fun `formattedBody null yields single plain text segment`() {
        val plain = "Just plain text without any html at all"

        val segments = parser.parse(body = plain, formattedBody = null)

        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertNull(text.html, "html must be null when formattedBody is null")
        assertEquals(plain, text.plainText)
        assertFalse(text.isListBlock)
    }

    @Test
    fun `formattedBody null with markdown table yields Table plus Text`() {
        val plain = """
            Intro line.

            | A | B |
            |---|---|
            | 1 | 2 |

            Outro.
        """.trimIndent()

        val segments = parser.parse(body = plain, formattedBody = null)
        // Intro text, table, outro text — three segments.
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageSegment.Text)
        assertTrue(segments[1] is MessageSegment.Table)
        assertTrue(segments[2] is MessageSegment.Text)
    }

    // ── block-level splitting (no extra blank lines between siblings) ───────

    @Test
    fun `two sibling paragraphs emit two separate Text segments`() {
        val html = "<p>p1</p><p>p2</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(2, segments.size)
        val first = segments[0] as MessageSegment.Text
        val second = segments[1] as MessageSegment.Text
        assertFalse(first.isListBlock)
        assertFalse(second.isListBlock)
        assertEquals("p1", first.plainText)
        assertEquals("p2", second.plainText)
    }

    @Test
    fun `paragraph blockquote paragraph emit three Text segments with blockquote html preserved`() {
        val html = "<p>a</p><blockquote>b</blockquote><p>c</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(3, segments.size)
        segments.forEach { assertTrue(it is MessageSegment.Text, "all three are Text") }
        val bq = segments[1] as MessageSegment.Text
        assertTrue(bq.html!!.contains("<blockquote"),
            "blockquote outerHtml must be preserved; was: ${bq.html}")
        assertEquals("b", bq.plainText)
    }

    @Test
    fun `div with two paragraphs emits two separate Text segments`() {
        val html = "<div><p>a</p><p>b</p></div>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(2, segments.size)
        assertEquals("a", (segments[0] as MessageSegment.Text).plainText)
        assertEquals("b", (segments[1] as MessageSegment.Text).plainText)
    }

    @Test
    fun `div with inline strong emits single Text preserving strong`() {
        val html = "<div>inline <strong>text</strong></div>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertTrue(text.html!!.contains("<strong>text</strong>"),
            "strong formatting must be preserved; was: ${text.html}")
        assertEquals("inline text", text.plainText)
    }

    @Test
    fun `nested heading inside blockquote becomes plain Text, not Heading segment`() {
        val html = "<blockquote><h2>nested</h2></blockquote>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is MessageSegment.Text,
            "blockquote should produce a Text segment, not a Heading; got ${segments[0]}")
        val text = segments[0] as MessageSegment.Text
        assertEquals("nested", text.plainText)
        // The h2 was renamed to p so its content stays visible.
        assertTrue(text.html!!.contains("<p>nested</p>"),
            "nested heading should be transformed to p; was: ${text.html}")
    }

    @Test
    fun `blank paragraph is skipped and only non-blank paragraph emitted`() {
        val html = "<p></p><p>real</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        assertEquals("real", (segments[0] as MessageSegment.Text).plainText)
    }

    @Test
    fun `bare text with br runs stays as one Text segment with all brs preserved`() {
        val html = "text1<br>text2<br><br>text3"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        // Three <br> tags preserved verbatim — no global newline replacement.
        assertEquals(3, text.html!!.split("<br>").size - 1,
            "expected 3 <br> tags in html; was: ${text.html}")
    }

    @Test
    fun `heading paragraph list paragraph preserves source order across four blocks`() {
        val html = "<h1>t</h1><p>a</p><ul><li>i1</li><li>i2</li></ul><p>b</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(4, segments.size)
        assertTrue(segments[0] is MessageSegment.Heading, "seg[0] should be Heading")
        assertEquals("t", (segments[0] as MessageSegment.Heading).plainText)

        val seg1 = segments[1] as MessageSegment.Text
        assertFalse(seg1.isListBlock, "seg[1] should be plain Text")
        assertEquals("a", seg1.plainText)

        val seg2 = segments[2] as MessageSegment.Text
        assertTrue(seg2.isListBlock, "seg[2] should be list Text")
        assertEquals("i1 i2", seg2.plainText)

        val seg3 = segments[3] as MessageSegment.Text
        assertFalse(seg3.isListBlock, "seg[3] should be plain Text")
        assertEquals("b", seg3.plainText)
    }

    @Test
    fun `two consecutive unordered lists emit two separate list Text segments`() {
        val html = "<ul><li>a</li></ul><ul><li>b</li></ul>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(2, segments.size)
        val first = segments[0] as MessageSegment.Text
        val second = segments[1] as MessageSegment.Text
        assertTrue(first.isListBlock, "first should be a list block")
        assertTrue(second.isListBlock, "second should be a list block")
        assertEquals("a", first.plainText)
        assertEquals("b", second.plainText)
    }

    @Test
    fun `bare text surrounding a paragraph yields three segments without losing content`() {
        val html = "before<p>middle</p>after"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(3, segments.size)
        assertEquals("before", (segments[0] as MessageSegment.Text).plainText)
        assertEquals("middle", (segments[1] as MessageSegment.Text).plainText)
        assertEquals("after", (segments[2] as MessageSegment.Text).plainText)
    }

    @Test
    fun `div with surrounding bare text and a paragraph yields three segments`() {
        val html = "<div>before<p>middle</p>after</div>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(3, segments.size)
        assertEquals("before", (segments[0] as MessageSegment.Text).plainText)
        assertEquals("middle", (segments[1] as MessageSegment.Text).plainText)
        assertEquals("after", (segments[2] as MessageSegment.Text).plainText)
    }

    // ── unknown containers recurse instead of being dropped ───────────────

    @Test
    fun `unknown wrapper with two paragraphs emits two Text segments`() {
        val html = "<custom-wrapper><p>a</p><p>b</p></custom-wrapper>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(2, segments.size)
        assertEquals("a", (segments[0] as MessageSegment.Text).plainText)
        assertEquals("b", (segments[1] as MessageSegment.Text).plainText)
    }

    @Test
    fun `unknown wrapper preserves inline strong across mixed children`() {
        val html = "<custom-wrapper>before<strong>bold</strong>after</custom-wrapper>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertTrue(text.html!!.contains("<strong>bold</strong>"),
            "strong formatting inside unknown wrapper must be preserved; was: ${text.html}")
        // Jsoup concatenates inline siblings without inserting whitespace at
        // tag boundaries; the strong tag itself survives in html.
        assertEquals("beforeboldafter", text.plainText)
    }

    @Test
    fun `unknown inline span expands so its text is not dropped`() {
        val html = "<span>visible</span>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size)
        val text = segments[0] as MessageSegment.Text
        assertEquals("visible", text.plainText,
            "span content must survive even though wysiwyg has no span case")
    }

    // ── mx-reply fallback is dropped, surrounding text preserved ──────────

    @Test
    fun `mx-reply subtree is discarded and only the new paragraph remains`() {
        // Matrix standard reply fallback. The `<mx-reply>` block quotes the
        // ancestor message and exists for clients that don't understand
        // replies; rendering it would re-show the quoted body as if it were
        // the sender's own words. It must be dropped as a block boundary,
        // leaving only the actual reply body.
        val html = "<mx-reply><blockquote>old</blockquote></mx-reply><p>new</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size, "mx-reply must be discarded; got: $segments")
        val text = segments[0] as MessageSegment.Text
        assertEquals("new", text.plainText)
        assertFalse(text.html!!.contains("old"),
            "mx-reply quoted body must not survive into html; was: ${text.html}")
    }

    // ── inline-ish unknown wrapper does not split surrounding text ────────

    @Test
    fun `inline span between bare text stays in a single Text segment`() {
        // Unknown inline-ish wrappers (span, custom tags, …) must recurse
        // without flushing so they remain in the same Text segment as their
        // siblings; block handlers inside still flush themselves.
        val html = "before<span>visible</span>after"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size, "inline wrapper must not split surrounding text; got: $segments")
        val text = segments[0] as MessageSegment.Text
        assertEquals("beforevisibleafter", text.plainText)
    }

    // ── markdown table inside HTML (formattedBody set) ───────────────────

    @Test
    fun `markdown table inside p is extracted even when formattedBody is set`() {
        // Hermes Agent always sends formatted_body. GFM tables survive as raw
        // pipe text inside <p> separated by <br>. Parser must still recognise
        // them and emit a Table segment.
        val html = "<p>| Name | Age |<br>|---|---|<br>| Alice | 30 |<br>| Bob | 25 |</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size, "expected one Table segment; got: $segments")
        val table = segments[0] as MessageSegment.Table
        assertEquals(listOf("Name", "Age"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Alice", "30"), table.rows[0])
        assertEquals(listOf("Bob", "25"), table.rows[1])
    }

    @Test
    fun `markdown table inside p with leading prose splits into Text then Table`() {
        val html = "<p>Here is a table:<br>| A | B |<br>|---|---|<br>| 1 | 2 |</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(2, segments.size, "got: $segments")
        assertTrue(segments[0] is MessageSegment.Text, "first segment should be prose Text")
        assertTrue(segments[1] is MessageSegment.Table, "second segment should be Table")
    }

    @Test
    fun `paragraph with pipes but not table shape stays Text`() {
        // Single pipe in prose should not be misread as a table.
        val html = "<p>a | b</p>"

        val segments = parser.parse(body = html, formattedBody = html)

        assertEquals(1, segments.size, "got: $segments")
        assertTrue(segments[0] is MessageSegment.Text)
    }
}
