package com.angel.whatsappbot.model

/**
 * Red social de origen del mensaje. Meta Business Suite centraliza los tres
 * canales; el canal se infiere del texto de la notificación (ej. "Ana (Instagram)").
 */
enum class Channel {
    WHATSAPP,
    INSTAGRAM,
    MESSENGER,
    /** Comentario público en una publicación de Facebook (vía Business Suite). */
    FACEBOOK,
    UNKNOWN,
}

/**
 * Representa un mensaje entrante ya limpio, listo para pasarlo al LLM.
 *
 * @param sender      Nombre del remitente (contacto o participante del grupo).
 * @param message     Texto del mensaje.
 * @param isGroup     true si proviene de un chat de grupo.
 * @param chatTitle   Título del chat (nombre del contacto o del grupo).
 * @param timestamp   Marca de tiempo del mensaje (ms epoch).
 * @param notificationKey  Clave de la StatusBarNotification origen (para responder/deduplicar).
 * @param channel     Canal de origen (WhatsApp, Instagram, Messenger) si se pudo inferir.
 */
data class IncomingMessage(
    val sender: String,
    val message: String,
    val isGroup: Boolean,
    val chatTitle: String,
    val timestamp: Long,
    val notificationKey: String,
    val channel: Channel = Channel.UNKNOWN,
)
