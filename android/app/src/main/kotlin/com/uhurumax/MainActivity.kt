package com.uhurumax

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var player: UhuruPlayerPlugin? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        player = UhuruPlayerPlugin(
            applicationContext,
            flutterEngine.dartExecutor.binaryMessenger,
            flutterEngine.renderer,
        ) { on ->
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        createAlertsChannel()
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        player?.dispose()
        player = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /** Matches the channel ID the server sends with admin notifications. */
    private fun createAlertsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            "uhurumax_alerts",
            "Taarifa za UhuruMax",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Vifurushi, mechi na taarifa muhimu" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
