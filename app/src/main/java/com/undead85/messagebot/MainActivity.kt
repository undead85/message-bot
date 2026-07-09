package com.undead85.messagebot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Pantalla mínima de configuración. Su única misión es guiar al usuario para:
 *  1. Conceder el acceso a notificaciones (imprescindible para el listener).
 *  2. (Opcional) Eximir la app de las optimizaciones de batería para que los
 *     OEMs agresivos no maten el proceso.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<MaterialSwitch>(R.id.switchBotEnabled).apply {
            isChecked = BotPrefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                BotPrefs.setEnabled(this@MainActivity, checked)
                updateStatus()
            }
        }

        findViewById<Button>(R.id.btnNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryOptimization).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName)

        statusText.text = getString(
            R.string.status_template,
            if (listenerEnabled) "✅" else "❌",
            if (batteryExempt) "✅" else "❌",
            getString(if (BotPrefs.isEnabled(this)) R.string.bot_state_on else R.string.bot_state_off),
        )
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        // Nota: este intent directo está restringido en Google Play salvo casos
        // justificados. Para una app personal (sideload) es la vía más cómoda.
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        )
    }
}
