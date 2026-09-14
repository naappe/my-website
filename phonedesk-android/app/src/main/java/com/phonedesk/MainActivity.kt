package com.phonedesk

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var coordinator: PhoneDeskCoordinator
    private lateinit var pairHost: EditText
    private lateinit var pairPort: EditText
    private lateinit var pairCode: EditText
    private lateinit var connectHost: EditText
    private lateinit var connectPort: EditText
    private lateinit var trustedText: TextView
    private lateinit var statusText: TextView
    private lateinit var pairButton: Button
    private lateinit var verifyButton: Button
    private lateinit var reconnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        coordinator = PhoneDeskCoordinator(
            client = AndroidKadbClient(applicationContext),
            store = SharedPreferencesProfileStore(applicationContext),
        )

        setContentView(buildUi())
        refreshTrustedProfile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        scroll.addView(root)

        root.addView(title("PhoneDesk"))
        root.addView(body("v0.2-dev2 · Wireless Debugging test"))
        root.addView(spacer(12))

        root.addView(card().apply {
            addView(sectionTitle("1. Wireless Debugging"))
            addView(body("Keep Wireless debugging ON. Pairing uses Android's temporary 6-digit code."))
            addView(button("OPEN WIRELESS DEBUGGING") { openWirelessDebugging() })
        })

        root.addView(spacer(12))
        root.addView(card().apply {
            addView(sectionTitle("2. Pair this phone"))
            addView(body("From 'Pair device with pairing code', enter the temporary IP address, pairing port, and 6-digit code."))

            pairHost = input("IP address", InputType.TYPE_CLASS_PHONE)
            pairPort = input("Pairing port", InputType.TYPE_CLASS_NUMBER)
            pairCode = input("6-digit pairing code", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            addView(pairHost)
            addView(pairPort)
            addView(pairCode)

            pairButton = button("PAIR THIS PHONE") { startPairing() }
            addView(pairButton)
            addView(note("The 6-digit pairing code is used once and is never saved."))
        })

        root.addView(spacer(12))
        root.addView(card().apply {
            addView(sectionTitle("3. Verify trusted connection"))
            addView(body("After pairing, close the pairing popup. On the main Wireless debugging page, use its current IP address & Port. This is usually a different port from the pairing port."))

            connectHost = input("IP address", InputType.TYPE_CLASS_PHONE)
            connectPort = input("ADB connection port", InputType.TYPE_CLASS_NUMBER)
            addView(connectHost)
            addView(connectPort)

            verifyButton = button("VERIFY & SAVE") { verifyAndSave() }
            addView(verifyButton)
        })

        root.addView(spacer(12))
        root.addView(card().apply {
            addView(sectionTitle("Trusted device"))
            trustedText = body("None saved")
            addView(trustedText)

            reconnectButton = button("RECONNECT SAVED") { reconnectSaved() }
            addView(reconnectButton)

            addView(button("CLEAR TRUSTED PROFILE") {
                coordinator.clearProfile()
                refreshTrustedProfile()
                setStatus("Trusted endpoint removed. The Android lock credential was never stored.", false)
            })
        })

        root.addView(spacer(12))
        root.addView(card().apply {
            addView(sectionTitle("Diagnostics"))
            statusText = body("Ready. Open Wireless Debugging to begin.")
            addView(statusText)
        })

        root.addView(spacer(12))
        root.addView(note("Security: PhoneDesk stores its ADB host identity and, after successful verification, the trusted endpoint. It does not store your Android PIN, pattern, password, fingerprint, or temporary 6-digit pairing code."))

        return scroll
    }

    private fun startPairing() {
        val parsed = PairingInput.parse(
            pairHost.text.toString(),
            pairPort.text.toString(),
            pairCode.text.toString(),
        )

        parsed.onFailure {
            setStatus(it.message ?: "Check pairing details.", true)
        }.onSuccess { input ->
            setBusy(true)
            setStatus("Pairing with Android Wireless Debugging…", false)
            scope.launch {
                val result = coordinator.pair(input)
                pairCode.text.clear()
                setBusy(false)

                result.onSuccess {
                    connectHost.setText(input.host)
                    setStatus(
                        "Pairing succeeded. Now return to the main Wireless debugging page and enter its current connection port below, then tap VERIFY & SAVE.",
                        false,
                    )
                }.onFailure { error ->
                    setStatus("Pairing failed: ${safeError(error)}", true)
                }
            }
        }
    }

    private fun verifyAndSave() {
        val parsed = ConnectionInput.parse(
            connectHost.text.toString(),
            connectPort.text.toString(),
        )

        parsed.onFailure {
            setStatus(it.message ?: "Check connection details.", true)
        }.onSuccess { input ->
            setBusy(true)
            setStatus("Verifying ADB connection…", false)
            scope.launch {
                val result = coordinator.verifyAndSave(input)
                setBusy(false)

                result.onSuccess { profile ->
                    refreshTrustedProfile()
                    setStatus("Verified and saved: ${profile.address}", false)
                }.onFailure { error ->
                    setStatus("Verification failed: ${safeError(error)}", true)
                }
            }
        }
    }

    private fun reconnectSaved() {
        val profile = coordinator.savedProfile()
        if (profile == null) {
            setStatus("No verified trusted endpoint is saved yet.", true)
            return
        }

        setBusy(true)
        setStatus("Reconnecting to ${profile.address}…", false)
        scope.launch {
            val result = coordinator.verifyAndSave(ConnectionInput(profile.host, profile.port))
            setBusy(false)
            result.onSuccess {
                setStatus("Trusted ADB connection is working: ${profile.address}", false)
            }.onFailure { error ->
                setStatus("Saved endpoint could not reconnect: ${safeError(error)}", true)
            }
        }
    }

    private fun refreshTrustedProfile() {
        if (!::trustedText.isInitialized) return
        val profile = coordinator.savedProfile()
        trustedText.text = profile?.let { "Saved endpoint: ${it.address}" } ?: "None saved yet"
        reconnectButton.isEnabled = profile != null
    }

    private fun setBusy(busy: Boolean) {
        pairButton.isEnabled = !busy
        verifyButton.isEnabled = !busy
        reconnectButton.isEnabled = !busy && coordinator.savedProfile() != null
    }

    private fun setStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (error) Color.rgb(176, 38, 38) else Color.rgb(31, 65, 114))
    }

    private fun safeError(error: Throwable): String {
        val text = error.message?.trim().orEmpty()
        return if (text.isEmpty()) error.javaClass.simpleName else text.take(180)
    }

    private fun openWirelessDebugging() {
        val wireless = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        runCatching { startActivity(wireless) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { setStatus("Could not open Developer options on this Android build.", true) }
            }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 30f
        setTextColor(Color.rgb(21, 28, 41))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTextColor(Color.rgb(21, 28, 41))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(62, 70, 84))
        setLineSpacing(0f, 1.16f)
        setPadding(0, 0, 0, dp(10))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(91, 98, 110))
        setLineSpacing(0f, 1.12f)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun input(hint: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        this.inputType = inputType
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply { topMargin = dp(6) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
