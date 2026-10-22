package com.offlineai.touchfree

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Lightweight, responsive launcher screen that requests offline runtime permissions
 * (Microphone, Phone, Contacts) and opens Accessibility Settings with one tap.
 * Scrolls on small screens / landscape and shows live setup status.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request required offline permissions on launch
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, permissions, 1001)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "TouchFree Offline Voice AI\nZero-Internet Mode Enabled"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val steps = TextView(this).apply {
            text = "1. Grant Microphone, Phone, and Contacts permissions.\n" +
                "2. Tap 'Open Accessibility Settings', then enable 'TouchFree Offline Voice AI'.\n" +
                "3. Say \"Jarvis\" followed by any command."
            textSize = 16f
            setPadding(0, 0, 0, dp(16))
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(16))
        }

        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }

        val grantBtn = Button(this).apply {
            text = "Grant Permissions"
            layoutParams = btnParams
            setOnClickListener { ActivityCompat.requestPermissions(this@MainActivity, permissions, 1001) }
        }

        val openAccessibilityBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            layoutParams = btnParams
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val commands = TextView(this).apply {
            text = "Voice commands (say \"Jarvis\" first):\n" +
                "• go back / go home / recent apps / notifications / quick settings\n" +
                "• lock screen / take screenshot\n" +
                "• scroll down / scroll up / swipe left / swipe right\n" +
                "• click <button name> / tap <button name>\n" +
                "• type <text>\n" +
                "• open <app name> / launch <app name>\n" +
                "• call <contact or number>\n" +
                "• flashlight on / flashlight off\n" +
                "• volume up / volume down / mute\n" +
                "• set alarm for 7 30\n\n" +
                "Optional: with an on-device LLM model file installed, free-form requests are understood too."
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }

        content.addView(title)
        content.addView(steps)
        content.addView(statusText)
        content.addView(grantBtn)
        content.addView(openAccessibilityBtn)
        content.addView(commands)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/", ignoreCase = true)
    }

    private fun refreshStatus() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        statusText.text = "Status\n" +
            "${mark(mic)} Microphone\n" +
            "${mark(phone)} Phone calls\n" +
            "${mark(contacts)} Contacts\n" +
            "${mark(isAccessibilityEnabled())} Accessibility service"
    }
}
