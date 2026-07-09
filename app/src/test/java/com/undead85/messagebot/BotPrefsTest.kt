package com.undead85.messagebot

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BotPrefsTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `el bot arranca habilitado por defecto`() {
        assertTrue(BotPrefs.isEnabled(context))
    }

    @Test
    fun `el flag persiste al deshabilitar y rehabilitar`() {
        BotPrefs.setEnabled(context, false)
        assertFalse(BotPrefs.isEnabled(context))

        BotPrefs.setEnabled(context, true)
        assertTrue(BotPrefs.isEnabled(context))
    }

    @Test
    fun `el LLM arranca habilitado y su flag persiste`() {
        assertTrue(BotPrefs.isLlmEnabled(context))

        BotPrefs.setLlmEnabled(context, false)
        assertFalse(BotPrefs.isLlmEnabled(context))

        BotPrefs.setLlmEnabled(context, true)
        assertTrue(BotPrefs.isLlmEnabled(context))
    }
}
