package com.odyssey.data

import android.content.Context
import com.odyssey.domain.Session
import com.odyssey.domain.SessionStore

/** Keeps the signed-in session across app restarts. Swap for EncryptedSharedPreferences before production. */
class AndroidSessionStore(context: Context, name: String) : SessionStore {
    private val prefs = context.getSharedPreferences("odyssey.$name", Context.MODE_PRIVATE)

    override fun load(): Session? = prefs.getString("session", null)?.let { runCatching { apiJson.decodeFromString(Session.serializer(), it) }.getOrNull() }

    override fun save(session: Session?) {
        prefs.edit().apply { if (session == null) remove("session") else putString("session", apiJson.encodeToString(Session.serializer(), session)) }.apply()
    }
}
