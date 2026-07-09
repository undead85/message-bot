package com.undead85.messagebot.ai

import com.undead85.messagebot.model.IncomingMessage

/**
 * Abstracción del motor de IA local. El servicio de notificaciones solo conoce
 * esta interfaz, de modo que cambiar de motor (mock → AICore → llama.cpp → ONNX)
 * no requiere tocar la lógica de escucha/respuesta.
 *
 * PUNTOS DE INTEGRACIÓN FUTUROS
 * -----------------------------
 * 1) Google AI Edge SDK / Gemini Nano (on-device, dispositivos compatibles):
 *    - Dependencia: com.google.ai.edge.aicore (ver app/build.gradle.kts).
 *    - Implementación típica: crear un GenerativeModel con GenerationConfig
 *      y llamar a generateContent(prompt) dentro de generateReply().
 *    - Ventaja: sin gestionar pesos del modelo; el sistema provee Gemini Nano.
 *
 * 2) MediaPipe LLM Inference API (Gemma/Phi en formato .task):
 *    - Dependencia: com.google.mediapipe:tasks-genai.
 *    - Cargar el modelo desde almacenamiento (LlmInference.createFromOptions)
 *      una sola vez (es costoso) y reutilizar la instancia.
 *
 * 3) llama.cpp vía JNI:
 *    - Compilar libllama con el NDK (CMake) y exponer funciones nativas
 *      (loadModel/generate) con external fun + System.loadLibrary.
 *    - Modelos GGUF cuantizados (Q4) de 1-3B parámetros van bien en móvil.
 *
 * 4) ONNX Runtime Mobile:
 *    - Dependencia: com.microsoft.onnxruntime:onnxruntime-android.
 *    - Crear OrtEnvironment + OrtSession con el .onnx y ejecutar la generación
 *      token a token (requiere implementar el bucle de decodificación y el
 *      tokenizer, p. ej. con DJL tokenizers o sentencepiece).
 *
 * En todos los casos: cargar el modelo de forma perezosa (lazy) y en
 * Dispatchers.Default/IO, y liberar recursos en un método close() si el motor
 * lo requiere.
 */
interface LocalAiProcessor {

    /**
     * Genera la respuesta para un mensaje entrante.
     *
     * Es una función `suspend`: el llamante (el servicio) ya la ejecuta fuera
     * del hilo principal, y la implementación real puede tardar segundos.
     *
     * @return El texto a enviar, o null/vacío si no se debe responder
     *         (p. ej. filtros: no responder en grupos, horarios, etc.).
     */
    suspend fun generateReply(incoming: IncomingMessage): String?
}
