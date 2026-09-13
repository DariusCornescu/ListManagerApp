package com.darius.listmanager.diagnostics

import android.app.Activity
import android.os.Bundle

/** Preview-only entry point used by ADB to verify crash reporting on a real device. */
class ControlledCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw ControlledCrashException()
    }
}

class ControlledCrashException : RuntimeException("ListManager controlled preview crash")
