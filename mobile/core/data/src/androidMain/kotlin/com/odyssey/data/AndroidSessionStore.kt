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

/** Small persistent key-value storage for roster and the offline action queue. */
class AndroidKeyValueStore(context: Context, name: String) : com.odyssey.domain.KeyValueStore {
    private val prefs = context.getSharedPreferences("odyssey.kv.$name", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}
