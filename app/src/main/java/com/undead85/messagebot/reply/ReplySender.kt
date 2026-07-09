package com.undead85.messagebot.reply

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Localiza la acción de respuesta rápida (inline reply) de una notificación de
 * WhatsApp e inyecta un texto a través del RemoteInput, simulando lo que hace
 * el usuario al responder desde la propia notificación. WhatsApp nunca se abre:
 * su PendingIntent de respuesta recibe el texto y lo envía él mismo.
 */
class ReplySender {

    /**
     * @return true si la notificación permite responder inline. Útil para
     * descartar temprano notificaciones de actividad (likes, comentarios) y
     * agregadores sin acción de respuesta, antes de gastar inferencia.
     */
    fun hasReplyAction(notification: Notification): Boolean =
        findReplyAction(notification) != null

    /**
     * @return true si se encontró la acción de respuesta y se disparó el envío.
     */
    fun sendReply(context: Context, sbn: StatusBarNotification, replyText: String): Boolean {
        val notification = sbn.notification ?: return false

        val replyAction = findReplyAction(notification) ?: run {
            Log.w(TAG, "La notificación no expone acción con RemoteInput")
            return false
        }

        return try {
            // 1) Construir el Intent de resultados con el texto de respuesta
            //    en la clave que la propia acción declara (resultKey).
            val resultsIntent = Intent()
            val resultsBundle = Bundle()
            replyAction.remoteInputs?.forEach { remoteInput ->
                resultsBundle.putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(replyAction.remoteInputs, resultsIntent, resultsBundle)

            // 2) Disparar el PendingIntent de la acción: el sistema entrega el
            //    texto al proceso de WhatsApp, que envía el mensaje.
            replyAction.actionIntent.send(context, 0, resultsIntent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent de respuesta cancelado (notificación caducada)", e)
            false
        }
    }

    /**
     * Busca la Notification.Action que contenga un RemoteInput de texto libre.
     *
     * WhatsApp expone la respuesta rápida de dos formas; se comprueban ambas:
     *  1. Acciones directas de la notificación (notification.actions).
     *  2. Acciones del WearableExtender (histórico soporte para Wear OS);
     *     en versiones antiguas de WhatsApp solo existía esta.
     */
    private fun findReplyAction(notification: Notification): Notification.Action? {
        // 1) Acciones directas.
        notification.actions?.forEach { action ->
            if (action.hasFreeFormRemoteInput()) return action
        }

        // 2) Acciones del extensor wearable (son NotificationCompat.Action).
        val wearableAction = NotificationCompat.WearableExtender(notification).actions
            .firstOrNull { compatAction ->
                compatAction.remoteInputs?.any { it.allowFreeFormInput } == true
            }
        if (wearableAction != null) {
            // Convertir la acción compat a una Action de plataforma equivalente.
            return buildPlatformAction(wearableAction)
        }
        return null
    }

    private fun Notification.Action.hasFreeFormRemoteInput(): Boolean =
        remoteInputs?.any { it.allowFreeFormInput } == true

    private fun buildPlatformAction(compat: NotificationCompat.Action): Notification.Action? {
        val actionIntent = compat.actionIntent ?: return null
        val builder = Notification.Action.Builder(null, compat.title, actionIntent)
        compat.remoteInputs?.forEach { ri ->
            builder.addRemoteInput(
                RemoteInput.Builder(ri.resultKey)
                    .setLabel(ri.label)
                    .setAllowFreeFormInput(ri.allowFreeFormInput)
                    .build()
            )
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "WaBotReplySender"
    }
}
