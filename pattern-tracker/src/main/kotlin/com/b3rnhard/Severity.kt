package com.b3rnhard

import com.bitwig.extension.api.Host
import com.bitwig.extension.controller.api.ControllerHost

enum class Severity {
  none,
  error,
  warning,
  info,
  trace,
}

class Logger(val host: ControllerHost, val getSeverity: () -> Severity) {
  fun logMessage(message: String, severity: Severity) {
    if (severity.ordinal <= getSeverity().ordinal) return
    host.println(message)
    host.showPopupNotification(message)
  }
}