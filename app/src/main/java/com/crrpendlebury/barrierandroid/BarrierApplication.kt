/*
 * Application entry point for the Barrier Android client.
 *
 * Role: records process start and any uncaught exception to the in-app debug
 * log (barrier_debug.log), then hands the exception to the default handler so
 * Android still reports it. Direction of control: the computer runs the Barrier
 * server; this process is the phone-side client.
 *
 * Known gap: the uncaught handler runs on the dying thread, so the log write is
 * synchronous and best effort.
 */
package com.crrpendlebury.barrierandroid

import android.app.Application
import com.crrpendlebury.barrierandroid.accessibility.BarrierAccessibilityService

class BarrierApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BarrierAccessibilityService.log(this, "app process started")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            BarrierAccessibilityService.log(
                this,
                "uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}",
            )
            previous?.uncaughtException(thread, throwable)
        }
    }
}
