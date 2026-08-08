package com.naappe.inboxagenda

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        val email = prefs.getString("email", null) ?: return Result.success()
        return runCatching {
            val data = GoogleData.load(applicationContext, email)
            val latestId = data.mail.firstOrNull()?.id
            val previousId = prefs.getString("latest_mail", null)
            if (latestId != null && previousId != null && latestId != previousId) {
                val mail = data.mail.first()
                val notification = NotificationCompat.Builder(applicationContext, "updates")
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(mail.subject)
                    .setContentText(mail.sender)
                    .setAutoCancel(true)
                    .build()
                try { NotificationManagerCompat.from(applicationContext).notify(latestId.hashCode(), notification) } catch (_: SecurityException) { }
            }
            prefs.edit().putString("latest_mail", latestId).apply()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
