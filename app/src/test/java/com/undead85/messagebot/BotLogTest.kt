package com.undead85.messagebot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BotLogTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `escribe lee y limpia el registro`() {
        BotLog.clear(context)
        assertEquals("", BotLog.read(context))

        BotLog.log(context, "mensaje de prueba")
        val content = BotLog.read(context)
        assertTrue(content.contains("mensaje de prueba"))

        BotLog.clear(context)
        assertEquals("", BotLog.read(context))
    }

    @Test
    fun `el registro acumula lineas en orden`() {
        BotLog.clear(context)
        BotLog.log(context, "primera")
        BotLog.log(context, "segunda")
        val lines = BotLog.read(context).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("primera"))
        assertTrue(lines[1].contains("segunda"))
    }
}
