package com.undead85.messagebot.service

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.undead85.messagebot.model.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Cubre el bug que causó el envío masivo de respuestas: el bot se respondía a
 * sí mismo porque no distinguía su propio mensaje (la respuesta ya enviada) del
 * mensaje entrante real, y el throttle que evita ráfagas de mensajes.
 */
@RunWith(RobolectricTestRunner::class)
class WhatsAppNotificationListenerTest {

    private fun buildListener(): WhatsAppNotificationListener =
        Robolectric.setupService(WhatsAppNotificationListener::class.java)

    private fun buildSbn(
        listener: WhatsAppNotificationListener,
        notification: Notification,
        key: String,
        packageName: String = "com.facebook.pages.app",
    ) = StatusBarNotification(
        packageName,
        packageName,
        key.hashCode(),
        key,
        android.os.Process.myUid(),
        0,
        0,
        notification,
        Process.myUserHandle(),
        System.currentTimeMillis(),
    )

    @Test
    fun `extractMessage devuelve el mensaje cuando lo envia el contacto`() {
        val listener = buildListener()
        val contact = Person.Builder().setName("Ana").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Yo").build())
            .setConversationTitle("Ana")
            .addMessage("Hola, ¿cómo estás?", System.currentTimeMillis(), contact)
        val notification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setStyle(style)
            .build()
        val sbn = buildSbn(listener, notification, "incoming")

        val result = listener.extractMessage(sbn)

        assertNotNull("Un mensaje entrante real no debería descartarse", result)
        assertEquals("Hola, ¿cómo estás?", result!!.message)
        assertEquals("Ana", result.sender)
    }

    @Test
    fun `extractMessage ignora el mensaje cuando lo envia el propio bot`() {
        val listener = buildListener()
        // Tal como hace WhatsApp: tras enviar la respuesta, actualiza la
        // notificación y el "último mensaje" pasa a ser el nuestro (person null).
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Yo").build())
            .setConversationTitle("Ana")
            .addMessage("🤖 [Respuesta automática de prueba] Recibido tu mensaje", System.currentTimeMillis(), null as Person?)
        val notification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setStyle(style)
            .build()
        val sbn = buildSbn(listener, notification, "self-reply")

        val result = listener.extractMessage(sbn)

        assertNull("El propio mensaje del bot no debe tratarse como entrante", result)
    }

    @Test
    fun `extractMessage detecta el canal Instagram y limpia el remitente`() {
        val listener = buildListener()
        // Meta Business Suite marca el canal en el nombre: "Ana (Instagram)".
        val contact = Person.Builder().setName("Ana (Instagram)").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Yo").build())
            .setConversationTitle("Ana (Instagram)")
            .addMessage("¿Tienen stock?", System.currentTimeMillis(), contact)
        val notification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana (Instagram)")
            .setStyle(style)
            .build()
        val sbn = buildSbn(listener, notification, "instagram-msg")

        val result = listener.extractMessage(sbn)

        assertNotNull(result)
        assertEquals(Channel.INSTAGRAM, result!!.channel)
        assertEquals("Ana", result.sender)
        assertEquals("Ana", result.chatTitle)
    }

    @Test
    fun `extractMessage asigna el canal segun el paquete de la app individual`() {
        val listener = buildListener()
        val contact = Person.Builder().setName("Ana").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Yo").build())
            .addMessage("Hola", System.currentTimeMillis(), contact)
        val notification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setStyle(style)
            .build()

        val igResult = listener.extractMessage(
            buildSbn(listener, notification, "ig-pkg", packageName = "com.instagram.android"))
        val fbResult = listener.extractMessage(
            buildSbn(listener, notification, "fb-pkg", packageName = "com.facebook.orca"))
        val waResult = listener.extractMessage(
            buildSbn(listener, notification, "w4b-pkg", packageName = "com.whatsapp.w4b"))

        assertEquals(Channel.INSTAGRAM, igResult!!.channel)
        assertEquals(Channel.MESSENGER, fbResult!!.channel)
        assertEquals(Channel.WHATSAPP, waResult!!.channel)
    }

    @Test
    fun `extractMessage asigna canal WhatsApp para la app de WhatsApp`() {
        val listener = buildListener()
        val contact = Person.Builder().setName("Ana").build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Yo").build())
            .addMessage("Hola", System.currentTimeMillis(), contact)
        val notification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setStyle(style)
            .build()
        val sbn = buildSbn(listener, notification, "wa-msg", packageName = "com.whatsapp")

        val result = listener.extractMessage(sbn)

        assertNotNull(result)
        assertEquals(Channel.WHATSAPP, result!!.channel)
    }

    @Test
    fun `extractMessage ignora notificaciones de actividad sin MessagingStyle ni category msg`() {
        val listener = buildListener()
        // Como "Indicó que le gusta tu reel": título + texto pero sin
        // MessagingStyle y sin category=msg.
        val likeNotification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("A Sol L. Huenupán")
            .setContentText("Indicó que le gusta tu reel")
            .build()
        val sbn = buildSbn(listener, likeNotification, "reel-like", packageName = "com.instagram.android")

        assertNull("Una notificación de actividad no debe tratarse como mensaje", listener.extractMessage(sbn))
    }

    @Test
    fun `extractMessage acepta plan B solo con category msg`() {
        val listener = buildListener()
        val msgNotification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setContentText("Hola")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        val sbn = buildSbn(listener, msgNotification, "planb-msg", packageName = "com.instagram.android")

        val result = listener.extractMessage(sbn)

        assertNotNull(result)
        assertEquals("Hola", result!!.message)
    }

    @Test
    fun `extractMessage parsea comentarios de publicacion via Business Suite`() {
        val listener = buildListener()
        // Formato real verificado con dumpsys: BigTextStyle, título = Página,
        // texto = "Remitente: comentario", subText = "Nuevo comentario de publicación".
        val commentNotification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Mi Página ")
            .setContentText("Juan Pérez: Se ven calentitos! Qué precio?")
            .setSubText("Nuevo comentario de publicación")
            .build()
        val sbn = buildSbn(listener, commentNotification, "fb-comment", packageName = "com.facebook.pages.app")

        val result = listener.extractMessage(sbn)

        assertNotNull(result)
        assertEquals("Juan Pérez", result!!.sender)
        assertEquals("Se ven calentitos! Qué precio?", result.message)
        assertEquals("Mi Página", result.chatTitle)
        assertEquals(Channel.FACEBOOK, result.channel)
    }

    @Test
    fun `sendReplyThrottled espera entre 3 y 5 segundos entre envios`() = runBlocking {
        val listener = buildListener()
        val plainNotification = NotificationCompat.Builder(listener, "canal")
            .setContentTitle("Ana")
            .setContentText("sin acciones de respuesta")
            .build()
        val sbn = buildSbn(listener, plainNotification, "throttle")

        val t0 = System.currentTimeMillis()
        listener.sendReplyThrottled(sbn, "primera")
        val t1 = System.currentTimeMillis()
        listener.sendReplyThrottled(sbn, "segunda")
        val t2 = System.currentTimeMillis()

        assertTrue("La primera respuesta no debería esperar (no hay envío previo)", (t1 - t0) < 500)

        val gapMs = t2 - t1
        assertTrue("El intervalo fue de ${gapMs}ms, se esperaba >= 3000ms", gapMs >= 3000)
        assertTrue("El intervalo fue de ${gapMs}ms, se esperaba <= 5500ms (holgura sobre el máximo de 5000ms)", gapMs <= 5500)
    }
}
