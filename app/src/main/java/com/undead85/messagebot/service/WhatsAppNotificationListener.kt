package com.undead85.messagebot.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.undead85.messagebot.BotLog
import com.undead85.messagebot.BotPrefs
import com.undead85.messagebot.ai.LocalAiProcessor
import com.undead85.messagebot.ai.MediaPipeAiProcessor
import com.undead85.messagebot.ai.MockAiProcessor
import com.undead85.messagebot.model.Channel
import com.undead85.messagebot.model.IncomingMessage
import com.undead85.messagebot.reply.ReplySender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import kotlin.random.Random

/**
 * Servicio que escucha las notificaciones del sistema y reacciona a las de WhatsApp.
 *
 * PERSISTENCIA EN SEGUNDO PLANO
 * -----------------------------
 * Un NotificationListenerService es un servicio ENLAZADO POR EL SISTEMA: mientras
 * el usuario tenga concedido el "Acceso a notificaciones", el propio sistema lo
 * mantiene vivo y lo re-enlaza tras reinicios o si el proceso muere. No necesita
 * (ni debe usar) startForeground(). Aun así, en OEMs agresivos (Xiaomi/MIUI,
 * Huawei, Samsung...) conviene:
 *   1. Pedir la exención de optimización de batería (ver MainActivity).
 *   2. Excluir la app de "apps en suspensión" / autostart en los ajustes del OEM.
 *   3. Si el listener deja de recibir eventos, forzar un re-enlace con
 *      requestRebind() (ver onListenerDisconnected).
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    /**
     * Scope propio del servicio: SupervisorJob para que un fallo en el procesado
     * de un mensaje no cancele el resto, y Dispatchers.Default porque la
     * inferencia del LLM es trabajo intensivo de CPU. onNotificationPosted()
     * llega en el hilo principal: NUNCA ejecutar la IA ahí.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Capa de IA: LLM local vía MediaPipe si el modelo .task está en el
     * dispositivo; si no, cae al mock. Lazy para no tocar disco en onCreate.
     */
    private val aiProcessor: LocalAiProcessor by lazy {
        val mediaPipe = MediaPipeAiProcessor(applicationContext)
        if (mediaPipe.isModelAvailable()) {
            Log.i(TAG, "IA: LLM local MediaPipe (${mediaPipe.modelFile()?.absolutePath})")
            mediaPipe
        } else {
            Log.w(TAG, "IA: modelo .task no encontrado; usando MockAiProcessor")
            MockAiProcessor()
        }
    }

    private val replySender = ReplySender()

    /** Deduplicación: WhatsApp re-emite la misma notificación varias veces. */
    private val processedMessages = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Límite de envío: serializa los envíos y fuerza una espera entre
     * MIN_REPLY_DELAY_MS y MAX_REPLY_DELAY_MS respecto al envío anterior, para
     * que nunca salgan mensajes en ráfaga aunque varios se procesen en paralelo.
     */
    private val sendMutex = Mutex()
    private var lastSendAtMs = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 0) Interruptor global: apagado desde la app, se ignora todo.
        if (!BotPrefs.isEnabled(this)) return

        // 1) Filtrar solo las apps objetivo (Meta Business Suite por defecto).
        if (sbn.packageName !in TARGET_PACKAGES) return

        val notification = sbn.notification ?: return

        // 2) Descartar notificaciones que no son mensajes reales:
        //    - Resúmenes de grupo ("5 mensajes de 2 chats").
        //    - Notificaciones persistentes (llamadas, "comprobando mensajes nuevos", backup).
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // 2.5) Sin acción de respuesta no hay nada que hacer: descarta likes,
        //      comentarios y agregadores (Meta Business Suite) antes de gastar
        //      inferencia en algo que no se puede responder.
        if (!replySender.hasReplyAction(notification)) {
            Log.d(TAG, "Notificación de ${sbn.packageName} sin acción de respuesta; ignorada")
            return
        }

        // 3) Extraer remitente y texto.
        val incoming = extractMessage(sbn) ?: return

        // 4) Deduplicar por chat + timestamp + texto.
        val dedupKey = "${incoming.chatTitle}|${incoming.timestamp}|${incoming.message.hashCode()}"
        if (!processedMessages.add(dedupKey)) return
        if (processedMessages.size > MAX_DEDUP_ENTRIES) {
            // Evitar crecimiento indefinido: eliminar la entrada más antigua.
            processedMessages.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }

        Log.d(TAG, "[${incoming.channel}] Mensaje de ${incoming.sender} en '${incoming.chatTitle}': ${incoming.message}")
        BotLog.log(this, "📥 [${incoming.channel}] ${incoming.sender}: ${incoming.message.take(80)}")

        // 5) Procesar con la IA fuera del hilo principal y responder.
        serviceScope.launch {
            try {
                val reply = aiProcessor.generateReply(incoming)
                if (reply.isNullOrBlank()) {
                    Log.d(TAG, "La IA decidió no responder a este mensaje.")
                    return@launch
                }
                val sent = sendReplyThrottled(sbn, reply)
                Log.d(TAG, if (sent) "Respuesta enviada: $reply" else "No se encontró acción de respuesta")
                BotLog.log(
                    applicationContext,
                    if (sent) "📤 → ${incoming.sender}: ${reply.take(80)}"
                    else "⚠️ Sin acción de respuesta para ${incoming.sender}",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error procesando/respondiendo el mensaje", t)
                BotLog.log(applicationContext, "❌ Error con mensaje de ${incoming.sender}: ${t.message}")
            }
        }
    }

    /**
     * Envía la respuesta respetando un espaciado aleatorio de 3 a 5 segundos
     * desde el último envío. El Mutex serializa las llamadas concurrentes para
     * que dos mensajes procesados a la vez no se salten la espera entre sí.
     */
    @VisibleForTesting
    internal suspend fun sendReplyThrottled(sbn: StatusBarNotification, reply: String): Boolean =
        sendMutex.withLock {
            val gapMs = Random.nextLong(MIN_REPLY_DELAY_MS, MAX_REPLY_DELAY_MS + 1)
            val waitMs = gapMs - (System.currentTimeMillis() - lastSendAtMs)
            if (waitMs > 0) delay(waitMs)
            val sent = replySender.sendReply(applicationContext, sbn, reply)
            lastSendAtMs = System.currentTimeMillis()
            sent
        }

    /**
     * Extrae el último mensaje de la notificación.
     *
     * Se prioriza MessagingStyle (lo que usan WhatsApp y Meta Business Suite):
     * contiene la lista de mensajes con su remitente real, lo que resuelve
     * correctamente los grupos (donde EXTRA_TITLE es el nombre del grupo, no
     * del remitente). Como plan B se usan los extras clásicos EXTRA_TITLE /
     * EXTRA_TEXT.
     *
     * Meta Business Suite centraliza Instagram, Facebook y WhatsApp Business:
     * el canal de origen se infiere del sufijo que añade al remitente o al
     * título (ej. "Juan Pérez (Instagram)") y se limpia del nombre.
     */
    @VisibleForTesting
    internal fun extractMessage(sbn: StatusBarNotification): IncomingMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)

        if (style != null) {
            val last = style.messages.lastOrNull() ?: return null
            // Un person nulo (o igual al "user" del estilo) significa que el mensaje
            // lo enviaste tú (p. ej. nuestra propia respuesta). Ignorarlo evita que
            // el bot se responda a sí mismo en bucle infinito.
            if (last.person == null || last.person == style.user) return null
            val text = last.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val rawSender = last.person?.name?.toString()
                ?: style.user.name?.toString()
                ?: title
            val chatTitle = style.conversationTitle?.toString() ?: title
            return IncomingMessage(
                sender = stripChannelSuffix(rawSender),
                message = text,
                isGroup = style.isGroupConversation,
                chatTitle = stripChannelSuffix(chatTitle),
                timestamp = last.timestamp,
                notificationKey = sbn.key,
                channel = detectChannel(sbn.packageName, rawSender, chatTitle, subText),
            )
        }

        // Comentarios de publicaciones vía Business Suite: BigTextStyle sin
        // category, título = nombre de la Página, texto = "Remitente: comentario".
        // Solo llegan aquí si la notificación tiene acción de respuesta (el
        // filtro previo del onNotificationPosted lo garantiza). OJO: responder
        // publica un comentario PÚBLICO en la publicación.
        if (sbn.packageName == "com.facebook.pages.app") {
            return extractPageComment(sbn, title, subText)
        }

        // Plan B: notificaciones sin MessagingStyle. Solo mensajes reales:
        // las notificaciones de actividad (likes, comentarios, "le gusta tu
        // reel") no llevan category=msg y deben ignorarse.
        if (notification.category != Notification.CATEGORY_MESSAGE) return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return null
        // Heurística: descartar avisos internos de las apps (no son chats).
        if (title.equals("WhatsApp", ignoreCase = true)) return null
        if (title.contains("Meta Business", ignoreCase = true)) return null

        return IncomingMessage(
            sender = stripChannelSuffix(title),
            message = text,
            isGroup = false,
            chatTitle = stripChannelSuffix(title),
            timestamp = notification.`when`,
            notificationKey = sbn.key,
            channel = detectChannel(sbn.packageName, title, text, subText),
        )
    }

    /** Parsea "Remitente: comentario" de una notificación de comentario de página. */
    private fun extractPageComment(
        sbn: StatusBarNotification,
        pageTitle: String,
        subText: String,
    ): IncomingMessage? {
        val raw = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val separator = raw.indexOf(": ")
        if (separator <= 0) return null
        return IncomingMessage(
            sender = raw.substring(0, separator).trim(),
            message = raw.substring(separator + 2).trim(),
            isGroup = false,
            chatTitle = pageTitle.trim(),
            timestamp = sbn.notification.`when`,
            notificationKey = sbn.key,
            channel = if (subText.contains("instagram", ignoreCase = true)) {
                Channel.INSTAGRAM
            } else {
                Channel.FACEBOOK
            },
        )
    }

    /** Quita el sufijo de canal del nombre: "Ana (Instagram)" → "Ana". */
    private fun stripChannelSuffix(name: String): String =
        name.replace(CHANNEL_SUFFIX, "").trim()

    /**
     * Infiere el canal de origen. Para las apps individuales el paquete lo
     * determina directamente; para Meta Business Suite (agregador) se busca la
     * marca del canal en remitente, título o subtexto de la notificación.
     */
    private fun detectChannel(packageName: String, vararg candidates: String): Channel {
        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> return Channel.WHATSAPP
            "com.instagram.android" -> return Channel.INSTAGRAM
            "com.facebook.orca" -> return Channel.MESSENGER
        }
        val haystack = candidates.joinToString(" ").lowercase()
        return when {
            "instagram" in haystack -> Channel.INSTAGRAM
            "whatsapp" in haystack -> Channel.WHATSAPP
            "messenger" in haystack || "facebook" in haystack -> Channel.MESSENGER
            else -> Channel.UNKNOWN
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "Listener conectado")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Listener desconectado; solicitando re-enlace")
        // En API 24+ se puede pedir al sistema que vuelva a enlazar el servicio.
        requestRebind(android.content.ComponentName(this, WhatsAppNotificationListener::class.java))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WaBotListener"
        private const val MAX_DEDUP_ENTRIES = 200
        private const val MIN_REPLY_DELAY_MS = 3000L
        private const val MAX_REPLY_DELAY_MS = 5000L
        /**
         * Meta Business Suite (com.facebook.pages.app) publica sus notificaciones
         * SIN acción de respuesta (RemoteInput), por lo que no permite inline
         * reply: se mantiene solo para registrar los mensajes que llegan por ahí.
         * Las respuestas reales salen por las apps individuales de cada canal,
         * que sí exponen la acción de responder.
         */
        private val TARGET_PACKAGES = setOf(
            "com.instagram.android",  // Instagram (DMs de la cuenta business)
            "com.facebook.orca",      // Messenger (mensajes de la Página)
            "com.whatsapp.w4b",       // WhatsApp Business
            "com.facebook.pages.app", // Meta Business Suite (solo lectura, sin inline reply)
            // "com.whatsapp",        // Descomentar para WhatsApp personal
        )

        /**
         * Sufijo de canal que Meta Business Suite añade al remitente en sus
         * notificaciones, ej. "Juan Pérez (Instagram)" o "María · WhatsApp".
         */
        private val CHANNEL_SUFFIX = Regex(
            """\s*[(\[·•-]\s*(instagram|whatsapp|facebook|messenger)\s*[)\]]?\s*$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
