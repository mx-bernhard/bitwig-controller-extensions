package com.b3rnhard

import com.bitwig.extension.controller.api.ControllerHost
import java.time.Duration
import java.time.Instant

enum class Severity {
  none,
  error,
  warning,
  info,
  trace,
}

class LogEntry(val message: String, val key: String, val instant: Instant = Instant.now())

class Logger(val host: ControllerHost, val getSeverity: () -> Severity) {
  lateinit var severity: Severity
  private val recentLogs = mutableListOf<LogEntry>()
  private fun schedulePopupNotifications() {
    var runnable = Runnable { }
    runnable = Runnable {
      val cutoffTime = Instant.now().minus(Duration.ofSeconds(5))
      recentLogs.removeAll({ it.instant.isBefore(cutoffTime) })
      if (recentLogs.isEmpty()) { return@Runnable }
      val text = recentLogs.joinToString(" | ") { it.message }
      host.showPopupNotification(text)
      host.scheduleTask(runnable, 1000)
    }
    host.scheduleTask(runnable, 0)
  }

  fun logMessage(message: String, severity: Severity, key: String = message) {
    if (severity.ordinal > getSeverity().ordinal) return
    host.println(message)
    recentLogs.removeAll { it.message == message || it.key == key }
    recentLogs += LogEntry(message, key)
    schedulePopupNotifications()

  }
}