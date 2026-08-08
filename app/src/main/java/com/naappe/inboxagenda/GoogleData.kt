package com.naappe.inboxagenda

import android.content.Context
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MailItem(val id: String, val sender: String, val subject: String, val snippet: String)
data class AgendaItem(val title: String, val whenText: String)
data class DashboardData(val mail: List<MailItem>, val agenda: List<AgendaItem>)

object GoogleData {
    val scopes = listOf(GmailScopes.GMAIL_READONLY, CalendarScopes.CALENDAR_READONLY)

    suspend fun load(context: Context, accountName: String): DashboardData = withContext(Dispatchers.IO) {
        val credential = GoogleAccountCredential.usingOAuth2(context, scopes).apply {
            selectedAccountName = accountName
        }
        val transport = AndroidHttp.newCompatibleTransport()
        val json = GsonFactory.getDefaultInstance()
        val gmail = Gmail.Builder(transport, json, credential).setApplicationName("Inbox & Agenda").build()
        val calendar = Calendar.Builder(transport, json, credential).setApplicationName("Inbox & Agenda").build()

        val messages = gmail.users().messages().list("me")
            .setQ("in:inbox newer_than:7d")
            .setMaxResults(20L)
            .execute().messages.orEmpty().mapNotNull { ref ->
                val message = gmail.users().messages().get("me", ref.id)
                    .setFormat("metadata")
                    .setMetadataHeaders(listOf("From", "Subject"))
                    .execute()
                val headers = message.payload?.headers.orEmpty().associate { it.name to it.value }
                MailItem(message.id, headers["From"] ?: "Unknown sender", headers["Subject"] ?: "(No subject)", message.snippet ?: "")
            }

        val now = com.google.api.client.util.DateTime(System.currentTimeMillis())
        val end = com.google.api.client.util.DateTime(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
        val formatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a").withZone(ZoneId.systemDefault())
        val events = calendar.events().list("primary")
            .setTimeMin(now).setTimeMax(end).setSingleEvents(true).setOrderBy("startTime").setMaxResults(20)
            .execute().items.orEmpty().map { event ->
                val millis = event.start.dateTime?.value ?: event.start.date?.value ?: 0L
                AgendaItem(event.summary ?: "Untitled event", if (millis > 0) formatter.format(Instant.ofEpochMilli(millis)) else "")
            }
        DashboardData(messages, events)
    }
}
