package com.angel.whatsappbot.ai

import android.content.Context
import android.util.Log
import com.angel.whatsappbot.BuildConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.angel.whatsappbot.model.Channel
import com.angel.whatsappbot.model.IncomingMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Motor de IA real sobre MediaPipe LLM Inference con Gemma 3 1B IT cuantizado
 * (formato .task, ~555MB). Solo se aceptan modelos Gemma: la plantilla de
 * prompt es la de Gemma y otro modelo respondería basura.
 *
 * El modelo NO va dentro del APK: se sube al dispositivo con adb y se busca en
 * las rutas de [candidateModelFiles]. Si no existe, el listener cae al mock.
 *
 * Subir el modelo (el nombre debe contener "gemma"):
 *   adb shell mkdir -p /data/local/tmp/llm
 *   adb push Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task /data/local/tmp/llm/gemma3-1b-q4.task
 *   adb shell chmod 644 /data/local/tmp/llm/gemma3-1b-q4.task
 */
class MediaPipeAiProcessor(private val context: Context) : LocalAiProcessor {

    /** La inferencia no es reentrante: un solo generateResponse a la vez. */
    private val engineLock = Mutex()
    private var engine: LlmInference? = null

    /**
     * Memoria de conversación: últimos [MAX_TURNS] intercambios (mensaje,
     * respuesta) por chat, con expulsión LRU al superar [MAX_CHATS] chats.
     * Vive solo mientras viva el proceso; suficiente para dar continuidad.
     */
    private val chatHistory =
        object : LinkedHashMap<String, ArrayDeque<Pair<String, String>>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ArrayDeque<Pair<String, String>>>,
            ) = size > MAX_CHATS
        }

    private fun historyKey(incoming: IncomingMessage) = "${incoming.channel}|${incoming.chatTitle}"

    private fun recallHistory(incoming: IncomingMessage): List<Pair<String, String>> =
        synchronized(chatHistory) { chatHistory[historyKey(incoming)]?.toList().orEmpty() }

    private fun remember(incoming: IncomingMessage, reply: String) {
        synchronized(chatHistory) {
            val turns = chatHistory.getOrPut(historyKey(incoming)) { ArrayDeque() }
            turns.addLast(incoming.message.take(200) to reply.take(MAX_REPLY_CHARS))
            while (turns.size > MAX_TURNS) turns.removeFirst()
        }
    }

    /** El .task más reciente de los directorios de modelos gana. */
    fun modelFile(): File? = candidateModelFiles()
        .filter { it.length() > 0 }
        .maxByOrNull { it.lastModified() }

    fun isModelAvailable(): Boolean = modelFile() != null

    private fun candidateModelFiles(): List<File> = buildList {
        context.getExternalFilesDir(null)?.resolve("llm")
            ?.listFiles(::isGemmaTask)?.let(::addAll)
        File("/data/local/tmp/llm")
            .listFiles(::isGemmaTask)?.let(::addAll)
    }

    private fun isGemmaTask(f: File): Boolean =
        f.extension == "task" && f.name.contains("gemma", ignoreCase = true)

    override suspend fun generateReply(incoming: IncomingMessage): String? {
        if (incoming.isGroup) return null

        // Preguntas de precio/stock/productos: respuesta determinista con el
        // enlace exacto del catálogo, sin pasar por el LLM (instantánea y sin
        // riesgo de que un modelo pequeño deforme la URL).
        if (CATALOG_URL.isNotBlank() && CATALOG_TRIGGER.containsMatchIn(incoming.message)) {
            Log.d(TAG, "Pregunta de catálogo detectada; respuesta directa con enlace")
            remember(incoming, CATALOG_REPLY)
            return CATALOG_REPLY
        }

        val history = recallHistory(incoming)
        val prompt = buildPrompt(incoming, history)
        Log.d(TAG, "Prompt con ${history.size} turnos de historial (${prompt.length} chars)")
        return engineLock.withLock {
            val startMs = System.currentTimeMillis()
            // Sesión nueva por mensaje: consultas independientes, sin arrastrar
            // contexto, y con temperatura baja para respuestas menos divagantes.
            val session = LlmInferenceSession.createFromOptions(
                obtainEngine(),
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.4f)
                    .setTopK(40)
                    .build(),
            )
            val raw = try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally {
                session.close()
            }
            Log.d(TAG, "Inferencia en ${System.currentTimeMillis() - startMs}ms")
            Log.d(TAG, "Salida cruda (${raw?.length ?: -1} chars): ${raw?.take(200)}")
            raw?.let { truncateReply(it) }?.also { remember(incoming, it) }
        }
    }

    /**
     * Un modelo pequeño ignora a veces el "máximo 2 frases" del prompt: se
     * recorta aquí al primer párrafo, acumulando frases completas hasta un
     * largo razonable (contar frases penalizaba interjecciones como "¡Hola!").
     */
    private fun truncateReply(raw: String): String? {
        val clean = raw.substringBefore(GEMMA_END_TOKEN).trim()
        if (clean.isEmpty()) return null
        val firstParagraph = clean.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val reply = buildString {
            for (match in SENTENCE_SPLIT.findAll(firstParagraph)) {
                val sentence = match.value.trim()
                if (isNotEmpty() && length + sentence.length + 1 > TARGET_REPLY_CHARS) break
                if (isNotEmpty()) append(' ')
                append(sentence)
            }
        }.ifEmpty { firstParagraph }
        return reply.take(MAX_REPLY_CHARS).trim().takeIf { it.isNotEmpty() }
    }

    /** Carga perezosa: la primera inferencia paga el coste de abrir el modelo. */
    private fun obtainEngine(): LlmInference = engine ?: run {
        val model = modelFile() ?: error("Modelo .task no encontrado")
        Log.i(TAG, "Cargando modelo ${model.absolutePath} (${model.length() / 1_000_000}MB)")
        LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                // Contexto total (entrada+salida). Con 3 turnos de historial el
                // prompt puede rondar ~600 tokens; 800 deja margen de salida
                // sin disparar el peor caso de latencia.
                .setMaxTokens(800)
                .build(),
        ).also { engine = it }
    }

    /** Plantilla de turnos de Gemma con las instrucciones del negocio. */
    private fun buildPrompt(
        incoming: IncomingMessage,
        history: List<Pair<String, String>>,
    ): String {
        val channelContext = when (incoming.channel) {
            Channel.FACEBOOK ->
                "El mensaje es un COMENTARIO PÚBLICO en una publicación de Facebook; tu respuesta también será pública."
            Channel.INSTAGRAM -> "El mensaje llega por mensaje directo de Instagram."
            Channel.MESSENGER -> "El mensaje llega por Messenger de la página de Facebook."
            Channel.WHATSAPP -> "El mensaje llega por WhatsApp."
            Channel.UNKNOWN -> "El mensaje llega por una red social."
        }
        val system = """
            Eres el asistente de $BUSINESS_NAME, emprendimiento artesanal de
            $OWNER_NAME en $BUSINESS_LOCATION. $channelContext
            Reglas estrictas:
            - Responde en español, cálido y breve.
            - Nunca inventes precios, stock, promociones ni plazos: si preguntan por
              eso, di solo que pueden revisar el catálogo ($CATALOG_URL)
              que contiene esa información.
            - No prometas nada en nombre de $OWNER_SHORT ni pidas datos personales.
            - Si te preguntan si eres un bot o una IA, dilo con honestidad: eres el
              asistente automático de $BUSINESS_NAME y $OWNER_SHORT responderá en persona.
            - Tienes el historial reciente de esta conversación: úsalo para
              responder preguntas sobre lo que el cliente ya contó (su nombre,
              qué busca, para quién). Eso NO es inventar: es leer la conversación.
        """.trimIndent()

        // SIN marcadores de turno: el bundle .task trae la plantilla de Gemma
        // y la sesión la aplica sola (prefix/suffix alrededor del query).
        // Añadirlos a mano los duplica y el modelo corta la generación con
        // salida vacía. Solo texto plano.
        return buildString {
            append(system)
            append("\n\n")
            if (history.isNotEmpty()) {
                append("Conversación reciente:\n")
                history.forEach { (userMsg, botReply) ->
                    append("${incoming.sender}: $userMsg\n")
                    append("Tú: $botReply\n")
                }
                append("\n")
            }
            append("${incoming.sender} escribe: ${incoming.message}")
        }
    }

    private companion object {
        const val TAG = "WaBotMediaPipe"
        const val GEMMA_END_TOKEN = "<end_of_turn>"
        const val MAX_REPLY_CHARS = 300
        const val TARGET_REPLY_CHARS = 220
        const val MAX_TURNS = 3
        const val MAX_CHATS = 50
        /** Frases terminadas en . ! ? … (o el resto final sin puntuación). */
        val SENTENCE_SPLIT = Regex("""[^.!?…]+[.!?…]+|[^.!?…]+$""")

        // Datos del negocio inyectados desde secrets.properties vía BuildConfig
        // (no viven en el código fuente ni en el repo).
        const val BUSINESS_NAME = BuildConfig.BUSINESS_NAME
        const val OWNER_NAME = BuildConfig.OWNER_NAME
        const val OWNER_SHORT = BuildConfig.OWNER_SHORT
        const val BUSINESS_LOCATION = BuildConfig.BUSINESS_LOCATION
        const val CATALOG_URL = BuildConfig.CATALOG_URL
        val CATALOG_REPLY = "¡Hola! Puedes ver nuestros productos, precios y " +
            "disponibilidad actualizada en nuestro catálogo: $CATALOG_URL " +
            "Cualquier otra consulta, $OWNER_SHORT te responde por aquí. 😊"

        /** Preguntas de precio/stock/productos que se derivan al catálogo. */
        val CATALOG_TRIGGER = Regex(
            """precio|cu[aá]nto|cuesta|\bvale\b|\bvalor\b|stock|disponib|cat[aá]logo|producto|venden|ofertas?""",
            RegexOption.IGNORE_CASE,
        )
    }
}
