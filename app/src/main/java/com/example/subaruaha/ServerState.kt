package com.example.subaruaha

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerState {
    var isRunning: Boolean = false
    var connectionState: String = "Stopped"
    var statusColorResId: Int = R.color.state_inactive
    var isServerMode: Boolean = false
    val logs = mutableListOf<String>()
    var onUpdateListener: (() -> Unit)? = null

    fun log(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logs) {
            logs.add("[$timestamp] $msg")
            if (logs.size > 200) {
                logs.removeAt(0)
            }
        }
        triggerUpdate()
    }

    fun updateState(running: Boolean, connState: String, colorRes: Int) {
        isRunning = running
        connectionState = connState
        statusColorResId = colorRes
        triggerUpdate()
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
        }
        triggerUpdate()
    }

    private fun triggerUpdate() {
        onUpdateListener?.invoke()
    }
}
