package com.undead85.messagebot

import android.content.Context

/**
 * Flag global de encendido/apagado del bot. Lo escribe el switch de
 * MainActivity y lo consulta el listener antes de procesar cada notificación:
 * apagado, el servicio sigue enlazado pero ignora todo.
 */
object BotPrefs {

    private const val PREFS_NAME = "bot_prefs"
    private const val KEY_ENABLED = "bot_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
