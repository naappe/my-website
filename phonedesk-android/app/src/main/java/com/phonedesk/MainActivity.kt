package com.phonedesk

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
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
import com.phonedesk.identity.AndroidDeviceIdentityStore
import com.phonedesk.remote.PairingManager
import com.phonedesk.remote.PairingOffer
import com.phonedesk.remote.RelayApi
import com.phonedesk.remote.SharedPreferencesTrustedComputerStore
import com.phonedesk.remote.TrustedComputerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var localCoordinator: PhoneDeskCoordinator
    private lateinit var trustedComputerStore: TrustedComputerStore
    private var pairingManager: PairingManager? = null
    private var pairingJob: Job? = null

    private lateinit var remoteStateText: TextView
    private lateinit var trustedComputerText: TextView
    private lateinit var remoteStatusText: TextView
    private lateinit var pairingPanel: LinearLayout
    private lateinit var pairingCodeText: TextView
    private lateinit var pairingIdText: TextView
    private lateinit var candidatePanel: LinearLayout
    private lateinit var candidateText: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var relayUrlInput: EditText
    private lateinit var advancedPanel: LinearLayout

    private lateinit var pairHost: EditText
    private lateinit var pairPort: EditText
    private lateinit var pairCode: EditText
    private lateinit var connectHost: EditText
    private lateinit var connectPort: EditText
    private lateinit var localTrustedText: TextView
    private lateinit var localStatusText: TextView
    private lateinit var pairButton: Button
    private lateinit var verifyButton: Button
    private lateinit var reconnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localCoordinator = PhoneDeskCoordinator(
            client = AndroidKadbClient(applicationContext),
            store = SharedPreferencesProfileStore(applicationContext),
        )
        trustedComputerStore = SharedPreferencesTrustedComputerStore(applicationContext)
        rebuildPairingManager()

        setContentView(buildUi())
        refreshRemoteState()
        refreshLocalTrustedProfile()
    }

    override fun onStop() {
        pairingJob?.cancel()
        pairingJob = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        scroll.addView(root)

        root.addView(title("PhoneDesk"))
        root.addView(body("Private remote access to your own Android phone."))
        root.addView(spacer(14))

        root.addView(card().apply {
            addView(eyebrow("REMOTE ACCESS"))
            remoteStateText = sectionTitle("Ready")
            addView(remoteStateText)
            remoteStatusText = body("No remote session is active.")
            addView(remoteStatusText)

            addView(divider())
            addView(label("My trusted computer"))
            trustedComputerText = body("None paired yet")
            addView(trustedComputerText)

            addView(primaryButton("PAIR NEW COMPUTER") { startRemotePairing() })
            addView(button("Trusted devices") { showTrustedDevices() })
            addView(button("Settings") { toggleSettings() })
        })

        root.addView(spacer(12))
        pairingPanel = card().apply {
            visibility = View.GONE
            addView(eyebrow("PAIRING"))
            addView(sectionTitle("Pair your computer"))
            addView(body("On the Windows PhoneDesk app, enter the pairing ID and the temporary 6-digit code below."))
            pairingCodeText = bigCode("------")
            addView(pairingCodeText)
            addView(label("Temporary 6-digit code"))
            pairingIdText = selectableValue("—")
            addView(pairingIdText)
            addView(label("Pairing ID · tap and hold to copy"))
            addView(note("This code expires after 5 minutes and is never saved."))
        }
        root.addView(pairingPanel)

        root.addView(spacer(12))
        candidatePanel = card().apply {
            visibility = View.GONE
            addView(eyebrow("COMPUTER FOUND"))
            addView(sectionTitle("Do you trust this computer?"))
            candidateText = body("Waiting for a computer…")
            addView(candidateText)
            addView(primaryButton("TRUST THIS COMPUTER") { confirmRemotePairing(true) })
            addView(dangerButton("DENY") { confirmRemotePairing(false) })
            addView(note("Trust only a computer you recognize. PhoneDesk does not ask for your phone PIN, password, or fingerprint."))
        }
        root.addView(candidatePanel)

        root.addView(spacer(12))
        settingsPanel = card().apply {
            visibility = View.GONE
            addView(sectionTitle("Settings"))
            addView(body("The relay address is a development setting. A production build will configure this automatically."))
            relayUrlInput = input("Relay HTTPS address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            relayUrlInput.setText(remotePreferences().getString(KEY_RELAY_URL, ""))
            addView(relayUrlInput)
            addView(primaryButton("SAVE SETTINGS") { saveRemoteSettings() })
        }
        root.addView(settingsPanel)

        root.addView(spacer(12))
        root.addView(button("Advanced / Local diagnostics") { toggleAdvanced() })

        advancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(spacer(10))
            addView(buildLocalDiagnostics())
        }
        root.addView(advancedPanel)

        root.addView(spacer(16))
        root.addView(note("Idle privacy: this pairing stage does not run a foreground service, screen capture, Accessibility control, permanent socket, or persistent PhoneDesk notification."))

        return scroll
    }

    private fun rebuildPairingManager() {
        val relayUrl = remotePreferences().getString(KEY_RELAY_URL, "").orEmpty().trim()
        pairingManager = if (relayUrl.isBlank()) {
            null
        } else {
            PairingManager(
                relay = RelayApi(
                    baseUrl = relayUrl,
                    identityStore = AndroidDeviceIdentityStore(),
                    displayName = Build.MODEL.ifBlank { "My Phone" },
                ),
                store = trustedComputerStore,
            )
        }
    }

    private fun startRemotePairing() {
        val manager = pairingManager
        if (manager == null) {
            setRemoteStatus("Remote service is not configured in this development build. Open Settings and enter the relay HTTPS address.", true)
            settingsPanel.visibility = View.VISIBLE
            return
        }

        pairingJob?.cancel()
        pairingPanel.visibility = View.VISIBLE
        candidatePanel.visibility = View.GONE
        pairingCodeText.text = "------"
        pairingIdText.text = "Creating secure pairing…"
        setRemoteStatus("Creating a temporary pairing code…", false)

        pairingJob = scope.launch {
            runCatching { manager.begin() }
                .onSuccess { offer ->
                    showPairingOffer(offer)
                    pollForComputer(manager, offer)
                }
                .onFailure { error ->
                    setRemoteStatus("Could not start pairing: ${safeError(error)}", true)
                }
        }
    }

    private fun showPairingOffer(offer: PairingOffer) {
        pairingCodeText.text = offer.manualCode
        pairingIdText.text = offer.pairingId
        setRemoteStatus("Waiting for Office Laptop to claim this pairing…", false)
    }

    private suspend fun pollForComputer(manager: PairingManager, offer: PairingOffer) {
        val deadline = System.currentTimeMillis() + offer.expiresInSeconds * 1000L
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val result = runCatching { manager.refresh() }
            val candidate = result.getOrNull()
            if (candidate != null) {
                candidateText.text = "${candidate.displayName}\nFingerprint: ${candidate.fingerprint}"
                candidatePanel.visibility = View.VISIBLE
                setRemoteStatus("Computer found. Check the name and fingerprint before approving.", false)
                return
            }

            val error = result.exceptionOrNull()
            if (error != null && !safeError(error).contains("No computer has claimed", ignoreCase = true)) {
                setRemoteStatus("Waiting for computer: ${safeError(error)}", true)
            }
            delay(2_000)
        }

        pairingPanel.visibility = View.GONE
        candidatePanel.visibility = View.GONE
        setRemoteStatus("Pairing expired. Tap PAIR NEW COMPUTER to create a fresh code.", true)
    }

    private fun confirmRemotePairing(approved: Boolean) {
        val manager = pairingManager ?: return
        pairingJob?.cancel()
        pairingJob = scope.launch {
            runCatching { manager.confirm(approved) }
                .onSuccess {
                    pairingPanel.visibility = View.GONE
                    candidatePanel.visibility = View.GONE
                    if (approved) {
                        setRemoteStatus("Computer trusted. PhoneDesk is ready for the next Remote Access stage.", false)
                    } else {
                        setRemoteStatus("Pairing denied. No computer was trusted.", false)
                    }
                    refreshRemoteState()
                }
                .onFailure { error ->
                    setRemoteStatus("Could not finish pairing: ${safeError(error)}", true)
                }
        }
    }

    private fun refreshRemoteState() {
        if (!::trustedComputerText.isInitialized) return
        val trusted = trustedComputerStore.load()
        if (trusted == null) {
            remoteStateText.text = "Pair your computer"
            trustedComputerText.text = "None paired yet"
            if (remoteStatusText.text.isNullOrBlank()) {
                remoteStatusText.text = "No remote session is active. Pair a computer once to create trusted device identity."
            }
        } else {
            remoteStateText.text = "Ready"
            trustedComputerText.text = "${trusted.displayName}\nTrusted · ${trusted.fingerprint}"
            remoteStatusText.text = "No remote session is active. Trusted pairing is saved."
        }
    }

    private fun showTrustedDevices() {
        val trusted = trustedComputerStore.load()
        val message = trusted?.let {
            "${it.displayName}\nFingerprint: ${it.fingerprint}\n\nThis computer is trusted for future PhoneDesk access requests."
        } ?: "No trusted computer is saved yet."
        android.app.AlertDialog.Builder(this)
            .setTitle("Trusted devices")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .apply {
                if (trusted != null) {
                    setNegativeButton("Remove trust") { _, _ ->
                        trustedComputerStore.clear()
                        refreshRemoteState()
                        setRemoteStatus("Trusted computer removed.", false)
                    }
                }
            }
            .show()
    }

    private fun toggleSettings() {
        settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun saveRemoteSettings() {
        val value = relayUrlInput.text.toString().trim()
        if (value.isNotEmpty() && !value.startsWith("https://")) {
            setRemoteStatus("Relay address must start with https://", true)
            return
        }
        remotePreferences().edit().putString(KEY_RELAY_URL, value).apply()
        rebuildPairingManager()
        settingsPanel.visibility = View.GONE
        setRemoteStatus(if (value.isBlank()) "Remote service setting cleared." else "Remote service setting saved.", false)
    }

    private fun setRemoteStatus(message: String, error: Boolean) {
        remoteStatusText.text = message
        remoteStatusText.setTextColor(if (error) Color.rgb(176, 38, 38) else Color.rgb(42, 91, 66))
    }

    private fun remotePreferences() = getSharedPreferences(REMOTE_PREFERENCES, MODE_PRIVATE)

    private fun toggleAdvanced() {
        advancedPanel.visibility = if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun buildLocalDiagnostics(): View = card().apply {
        addView(eyebrow("OPTIONAL LOCAL TOOLS"))
        addView(sectionTitle("Wireless Debugging diagnostics"))
        addView(body("These same-Wi-Fi tools are kept only for setup and troubleshooting. Final Remote Mode does not depend on them."))
        addView(button("Open Wireless Debugging") { openWirelessDebugging() })
        addView(divider())

        addView(label("Local ADB pairing"))
        pairHost = input("Phone IP address", InputType.TYPE_CLASS_PHONE)
        pairPort = input("Pairing port", InputType.TYPE_CLASS_NUMBER)
        pairCode = input("Temporary 6-digit code", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        addView(pairHost)
        addView(pairPort)
        addView(pairCode)
        pairButton = button("PAIR LOCAL ADB") { startLocalPairing() }
        addView(pairButton)

        addView(divider())
        addView(label("Verify local connection"))
        connectHost = input("Phone IP address", InputType.TYPE_CLASS_PHONE)
        connectPort = input("ADB connection port", InputType.TYPE_CLASS_NUMBER)
        addView(connectHost)
        addView(connectPort)
        verifyButton = button("VERIFY & SAVE LOCAL") { verifyLocalAndSave() }
        addView(verifyButton)

        localTrustedText = body("No local endpoint saved")
        addView(localTrustedText)
        reconnectButton = button("RECONNECT SAVED LOCAL") { reconnectLocalSaved() }
        addView(reconnectButton)
        addView(button("CLEAR LOCAL PROFILE") {
            localCoordinator.clearProfile()
            refreshLocalTrustedProfile()
            setLocalStatus("Local diagnostic endpoint removed.", false)
        })

        addView(divider())
        localStatusText = body("Local diagnostics are idle.")
        addView(localStatusText)
    }

    private fun startLocalPairing() {
        val parsed = PairingInput.parse(
            pairHost.text.toString(),
            pairPort.text.toString(),
            pairCode.text.toString(),
        )
        parsed.onFailure {
            setLocalStatus(it.message ?: "Check local pairing details.", true)
        }.onSuccess { input ->
            setLocalBusy(true)
            setLocalStatus("Pairing local Wireless Debugging…", false)
            scope.launch {
                val result = localCoordinator.pair(input)
                pairCode.text.clear()
                setLocalBusy(false)
                result.onSuccess {
                    connectHost.setText(input.host)
                    setLocalStatus("Local pairing succeeded. Use the current connection port to verify.", false)
                }.onFailure { error ->
                    setLocalStatus("Local pairing failed: ${safeError(error)}", true)
                }
            }
        }
    }

    private fun verifyLocalAndSave() {
        val parsed = ConnectionInput.parse(
            connectHost.text.toString(),
            connectPort.text.toString(),
        )
        parsed.onFailure {
            setLocalStatus(it.message ?: "Check local connection details.", true)
        }.onSuccess { input ->
            setLocalBusy(true)
            setLocalStatus("Verifying local ADB connection…", false)
            scope.launch {
                val result = localCoordinator.verifyAndSave(input)
                setLocalBusy(false)
                result.onSuccess { profile ->
                    refreshLocalTrustedProfile()
                    setLocalStatus("Local endpoint saved: ${profile.address}", false)
                }.onFailure { error ->
                    setLocalStatus("Local verification failed: ${safeError(error)}", true)
                }
            }
        }
    }

    private fun reconnectLocalSaved() {
        val profile = localCoordinator.savedProfile()
        if (profile == null) {
            setLocalStatus("No local diagnostic endpoint is saved.", true)
            return
        }
        setLocalBusy(true)
        setLocalStatus("Reconnecting local diagnostics…", false)
        scope.launch {
            val result = localCoordinator.verifyAndSave(ConnectionInput(profile.host, profile.port))
            setLocalBusy(false)
            result.onSuccess {
                setLocalStatus("Local ADB connection is working.", false)
            }.onFailure { error ->
                setLocalStatus("Local reconnect failed: ${safeError(error)}", true)
            }
        }
    }

    private fun refreshLocalTrustedProfile() {
        if (!::localTrustedText.isInitialized) return
        val profile = localCoordinator.savedProfile()
        localTrustedText.text = profile?.let { "Saved local endpoint: ${it.address}" } ?: "No local endpoint saved"
        reconnectButton.isEnabled = profile != null
    }

    private fun setLocalBusy(busy: Boolean) {
        pairButton.isEnabled = !busy
        verifyButton.isEnabled = !busy
        reconnectButton.isEnabled = !busy && localCoordinator.savedProfile() != null
    }

    private fun setLocalStatus(message: String, error: Boolean) {
        localStatusText.text = message
        localStatusText.setTextColor(if (error) Color.rgb(176, 38, 38) else Color.rgb(31, 65, 114))
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
                    .onFailure { setLocalStatus("Could not open Developer options on this Android build.", true) }
            }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 31f
        setTextColor(Color.rgb(17, 24, 39))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun eyebrow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.08f
        setTextColor(Color.rgb(86, 98, 117))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 21f
        setTextColor(Color.rgb(17, 24, 39))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(55, 65, 81))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(75, 85, 99))
        setLineSpacing(0f, 1.16f)
        setPadding(0, 0, 0, dp(10))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(107, 114, 128))
        setLineSpacing(0f, 1.12f)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun bigCode(text: String) = TextView(this).apply {
        this.text = text
        textSize = 34f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(17, 24, 39))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun selectableValue(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(31, 65, 114))
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(4))
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

    private fun primaryButton(text: String, action: () -> Unit) = button(text, action).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(30, 90, 72))
    }

    private fun dangerButton(text: String, action: () -> Unit) = button(text, action).apply {
        setTextColor(Color.rgb(153, 27, 27))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { topMargin = dp(7) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(229, 231, 235))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REMOTE_PREFERENCES = "phonedesk_remote_settings"
        private const val KEY_RELAY_URL = "relay_url"
    }
}
