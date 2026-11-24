package com.fuelstride.app

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Shown by Health Connect when you ask how Lightweight Baby uses your health data. */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 16f
            text = """
                How Lightweight Baby uses your health data

                The app reads your daily step count from Health Connect (which Samsung Health and other fitness apps sync to). It only reads steps; it never writes or changes health data.

                Step counts are stored on this phone, inside the app, to show your progress and daily calorie budget. They are not uploaded anywhere.

                When you ask for a workout plan, the app sends a short summary (such as "6,200 steps today") to NVIDIA's NIM API, using your own API key, so the plan can take your activity into account.

                You can remove access at any time in Settings > Health Connect > App permissions.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
