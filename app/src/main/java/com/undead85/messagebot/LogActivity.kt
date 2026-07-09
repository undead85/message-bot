package com.undead85.messagebot

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Muestra el registro de actividad del bot (BotLog) sin necesidad de logcat. */
class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        title = getString(R.string.log_title)

        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            BotLog.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val content = BotLog.read(this)
        logText.text = content.ifBlank { getString(R.string.log_empty) }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
