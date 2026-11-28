package com.odyssey.data

import com.odyssey.domain.KeyValueStore
import com.odyssey.domain.Session
import com.odyssey.domain.SessionStore

@JsFun("(k) => window.localStorage.getItem(k)")
private external fun lsGet(key: String): String?

@JsFun("(k, v) => window.localStorage.setItem(k, v)")
private external fun lsSet(key: String, value: String)

@JsFun("(k) => window.localStorage.removeItem(k)")
private external fun lsRemove(key: String)

/** Keeps the signed-in session in the browser's localStorage, so a refresh does not sign the customer out. */
class WebSessionStore(private val name: String) : SessionStore {
    private val key = "odyssey.$name.session"
    override fun load(): Session? = runCatching { lsGet(key) }.getOrNull()?.let { runCatching { apiJson.decodeFromString(Session.serializer(), it) }.getOrNull() }
    override fun save(session: Session?) {
        runCatching { if (session == null) lsRemove(key) else lsSet(key, apiJson.encodeToString(Session.serializer(), session)) }
    }
}

class WebKeyValueStore(private val name: String) : KeyValueStore {
    override fun get(key: String): String? = runCatching { lsGet("odyssey.kv.$name.$key") }.getOrNull()
    override fun put(key: String, value: String?) {
        runCatching { if (value == null) lsRemove("odyssey.kv.$name.$key") else lsSet("odyssey.kv.$name.$key", value) }
    }
}
