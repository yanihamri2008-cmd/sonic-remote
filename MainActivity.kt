package com.sonicteam.sonicremote

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Typeface
import android.graphics.Color
import android.view.ViewGroup

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var agentConsentGiven = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.rgb(12, 16, 25))
        }

        val title = TextView(this).apply {
            text = "SONIC REMOTE"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Secure remote support"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "No active session"
            textSize = 16f
            setTextColor(Color.rgb(130, 210, 170))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }

        val controller = makeButton("Open Controller")
        controller.setOnClickListener {
            status.text = "Controller selected. Pairing service is not connected yet."
        }

        val agent = makeButton("Open Agent")
        agent.setOnClickListener {
            status.text = "Agent mode: waiting for the device owner's approval."
            agentConsentGiven = false
        }

        val consent = makeButton("I approve a visible support session")
        consent.setOnClickListener {
            agentConsentGiven = true
            status.text = "Local approval recorded. No screen is being shared yet."
        }

        val stop = makeButton("Stop / Revoke")
        stop.setOnClickListener {
            agentConsentGiven = false
            status.text = "Session stopped locally. No active connection."
        }

        val note = TextView(this).apply {
            text = "The Agent must approve each session. Screen sharing will require Android's system permission prompt. This starter does not capture or transmit your screen."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        root.addView(title, match())
        root.addView(subtitle, match())
        root.addView(status, match())
        root.addView(controller, buttonParams())
        root.addView(agent, buttonParams())
        root.addView(consent, buttonParams())
        root.addView(stop, buttonParams())
        root.addView(note, match())

        setContentView(root)
    }

    private fun makeButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
        }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = 10
    }
}
