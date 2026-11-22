package com.keytag.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Simple setup screen. Everything else runs in MediaListenerService. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val main = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Keytag"; textSize = 28f })
        statusView = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad) }
        root.addView(statusView)

        root.addView(button("1. Allow Bluetooth") { askBluetooth() })
        root.addView(button("2. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Reconnect keytag") { MediaListenerService.instance?.reconnect() })

        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "Do steps 1 and 2 once. Then switch the keytag on and play a video in Brave.\n\n" +
                "You can close this app - it keeps working in the background."
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        main.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresher)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun bluetoothPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBluetooth() =
        bluetoothPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun askBluetooth() {
        if (!hasBluetooth()) requestPermissions(bluetoothPerms(), 1)
    }

    private fun refresh() {
        val bt = if (hasBluetooth()) "OK" else "needed - tap step 1"
        val na = if (hasNotificationAccess()) "OK" else "needed - tap step 2"
        statusView.text =
            "Bluetooth permission: $bt\n" +
            "Notification access: $na\n\n" +
            "Keytag: ${Status.connection}\n" +
            "Now playing: ${Status.nowPlaying}"
    }
}
