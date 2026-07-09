package com.undead85.messagebot.ai

import android.content.Context
import com.undead85.messagebot.BuildConfig
import com.undead85.messagebot.model.Channel
import com.undead85.messagebot.model.IncomingMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * El enrutado al catálogo ocurre ANTES de tocar el modelo, así que estos tests
 * corren sin el .task ni la librería nativa de MediaPipe.
 */
@RunWith(RobolectricTestRunner::class)
class MediaPipeAiProcessorTest {

    private fun incoming(message: String) = IncomingMessage(
        sender = "Cliente",
        message = message,
        isGroup = false,
        chatTitle = "Cliente",
        timestamp = 0L,
        notificationKey = "k",
        channel = Channel.WHATSAPP,
    )

    private fun processor(): MediaPipeAiProcessor {
        val context = RuntimeEnvironment.getApplication() as Context
        return MediaPipeAiProcessor(context)
    }

    @Test
    fun `preguntas de precio o stock responden con el enlace del catalogo`() = runBlocking {
        // Sin secrets.properties no hay catálogo configurado y el atajo se desactiva.
        org.junit.Assume.assumeTrue(BuildConfig.CATALOG_URL.isNotBlank())
        val p = processor()
        val questions = listOf(
            "Hola, qué precio tienen las mermeladas?",
            "Cuánto vale el pack?",
            "¿Tienen stock disponible?",
            "Me mandas el catálogo porfa",
            "Que productos venden?",
        )
        for (q in questions) {
            val reply = p.generateReply(incoming(q))
            assertTrue(
                "'$q' debería derivar al catálogo, respondió: $reply",
                reply != null && reply.contains(BuildConfig.CATALOG_URL),
            )
        }
    }

    @Test
    fun `mensajes de grupo no se responden`() = runBlocking {
        val p = processor()
        val group = incoming("precio?").copy(isGroup = true)
        assertNull(p.generateReply(group))
    }

    @Test
    fun `con el LLM apagado los mensajes no-catalogo no se responden`() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        com.undead85.messagebot.BotPrefs.setLlmEnabled(context, false)
        try {
            // Sin palabras de catálogo: iría al LLM, pero está apagado.
            assertNull(processor().generateReply(incoming("hola, buenas tardes")))
        } finally {
            com.undead85.messagebot.BotPrefs.setLlmEnabled(context, true)
        }
    }
}
