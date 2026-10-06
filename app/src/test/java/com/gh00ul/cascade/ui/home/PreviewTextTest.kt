package com.gh00ul.cascade.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.gh00ul.cascade.ui.theme.LauncherStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A favorite's preview line: who it's from in semibold at full strength, then what it says. */
class PreviewTextTest {
    private val style = LauncherStyle(darkText = false, accent = Color.White)

    @Test fun theSenderLeadsInSemibold() {
        val line = previewText("Maya Chen", "Are we still on for 7?", style)
        assertEquals("Maya Chen  Are we still on for 7?", line.text)
        val sender = line.spanStyles.single()
        assertEquals(0 until 9, sender.start until sender.end)
        assertEquals(FontWeight.SemiBold, sender.item.fontWeight)
        assertEquals(style.content, sender.item.color)
    }

    @Test fun aMissingSenderOrMessageLeavesNoGap() {
        assertEquals("Are we still on?", previewText("", "Are we still on?", style).text)
        assertTrue(previewText("", "Are we still on?", style).spanStyles.isEmpty())
        assertEquals("Maya Chen", previewText(" Maya Chen ", "  ", style).text)
    }
}
