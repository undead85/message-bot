package com.angel.whatsappbot.ai

import com.angel.whatsappbot.model.IncomingMessage
import kotlinx.coroutines.delay

/**
 * Implementación de prueba: simula la latencia de inferencia y devuelve una
 * respuesta enlatada. Útil para validar el pipeline completo
 * (notificación → extracción → "IA" → inline reply) antes de integrar el LLM real.
 */
class MockAiProcessor : LocalAiProcessor {

    override suspend fun generateReply(incoming: IncomingMessage): String? {
        // Regla de seguridad para las pruebas: no responder en grupos.
        if (incoming.isGroup) return null

        // Simular el tiempo de inferencia de un modelo local.
        delay(500)

        // ------------------------------------------------------------------
        // AQUÍ se sustituiría por la llamada real al motor de IA, p. ej.:
        //   val prompt = buildPrompt(incoming)
        //   return generativeModel.generateContent(prompt).text   // AICore
        //   return llmInference.generateResponse(prompt)          // MediaPipe
        //   return nativeGenerate(modelPtr, prompt)               // llama.cpp/JNI
        // ------------------------------------------------------------------
        return "🤖 [Respuesta automática de prueba · ${incoming.channel}] Recibido tu mensaje: \"${incoming.message.take(80)}\""
    }
}
