package com.naappe.inboxagenda

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.naappe.inboxagenda.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var signInClient: GoogleSignInClient

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        GoogleSignIn.getSignedInAccountFromIntent(result.data).addOnSuccessListener { account ->
            saveAccount(account.email.orEmpty())
            refresh()
        }.addOnFailureListener { binding.statusText.text = "Google sign-in failed: ${it.localizedMessage}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        createNotificationChannel()

        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(*GoogleData.scopes.map(::Scope).toTypedArray())
            .build()
        signInClient = GoogleSignIn.getClient(this, options)

        binding.connectButton.setOnClickListener { signInLauncher.launch(signInClient.signInIntent) }
        binding.refreshButton.setOnClickListener { refresh() }
        requestNotifications()
        accountName()?.let { refresh() }
    }

    private fun refresh() {
        val email = accountName() ?: run {
            binding.statusText.text = "Connect your Google account first"
            return
        }
        binding.accountText.text = email
        binding.statusText.text = "Refreshing Gmail and Calendar…"
        lifecycleScope.launch {
            runCatching { GoogleData.load(this@MainActivity, email) }
                .onSuccess { data ->
                    render(data)
                    binding.statusText.text = "Updated now · ${data.mail.size} emails · ${data.agenda.size} events"
                    scheduleSync()
                }
                .onFailure { binding.statusText.text = "Could not sync: ${it.localizedMessage}" }
        }
    }

    private fun render(data: DashboardData) {
        binding.mailList.removeAllViews()
        binding.calendarList.removeAllViews()
        data.agenda.forEach { addCard(binding.calendarList, it.title, it.whenText, "#315C4A") }
        if (data.agenda.isEmpty()) addCard(binding.calendarList, "No events in the next 7 days", "Your calendar is clear", "#315C4A")
        data.mail.forEach { addCard(binding.mailList, it.subject, it.sender + "\n" + it.snippet, "#C15F32") }
        if (data.mail.isEmpty()) addCard(binding.mailList, "Inbox is clear", "No recent inbox messages", "#C15F32")
    }

    private fun addCard(parent: ViewGroup, title: String, detail: String, accent: String) {
        val view = TextView(this).apply {
            text = "$title\n$detail"
            textSize = 15f
            setTextColor(Color.parseColor("#18231F"))
            setBackgroundColor(Color.WHITE)
            setPadding(18.dp, 15.dp, 18.dp, 15.dp)
        }
        parent.addView(view, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10.dp
        })
    }

    private fun saveAccount(email: String) = getSharedPreferences("account", MODE_PRIVATE).edit().putString("email", email).apply()
    private fun accountName() = getSharedPreferences("account", MODE_PRIVATE).getString("email", null)

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("google-sync", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("updates", "Inbox and calendar updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
