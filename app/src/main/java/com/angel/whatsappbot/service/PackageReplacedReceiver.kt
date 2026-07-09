package com.angel.whatsappbot.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Tras actualizar/reinstalar la app, el sistema deja el NotificationListener
 * habilitado pero SIN enlazar hasta que el usuario apaga y enciende el acceso
 * a notificaciones. Este receiver fuerza el re-enlace automáticamente.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "App actualizada; solicitando re-enlace del listener")
        NotificationListenerService.requestRebind(
            ComponentName(context, WhatsAppNotificationListener::class.java)
        )
    }

    private companion object {
        const val TAG = "WaBotRebind"
    }
}
