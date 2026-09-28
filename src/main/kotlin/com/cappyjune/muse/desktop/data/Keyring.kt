package com.cappyjune.muse.desktop.data

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * The Jellyfin sign-in, kept in GNOME's keyring (the Secret Service, via libsecret) like other
 * desktop apps' passwords; if there's no keyring running, in a file only this user can read.
 */
object Keyring {
    @Suppress("FunctionName")
    private interface LibSecret : Library {
        /** Built-in schemas: 1 = SECRET_SCHEMA_TYPE_COMPAT_NETWORK (attributes user, server, protocol...). */
        fun secret_get_schema(type: Int): Pointer
        fun secret_password_store_sync(schema: Pointer, collection: String?, label: String, password: String,
            cancellable: Pointer?, error: PointerByReference?, vararg attributes: Any?): Boolean
        fun secret_password_lookup_sync(schema: Pointer, cancellable: Pointer?, error: PointerByReference?, vararg attributes: Any?): Pointer?
        fun secret_password_clear_sync(schema: Pointer, cancellable: Pointer?, error: PointerByReference?, vararg attributes: Any?): Boolean
        fun secret_password_free(password: Pointer)
    }

    private val lib: LibSecret? by lazy { runCatching { Native.load("secret-1", LibSecret::class.java) }.getOrNull() }
    private val fallback = File(Paths.config, "account.json")
    // What the entry is found by; the terminating null ends libsecret's attribute list.
    private val attrs = arrayOf<Any?>("server", "muse-desktop", "protocol", "jellyfin", null)

    fun put(value: String?) {
        val l = lib
        val stored = l != null && runCatching {
            val schema = l.secret_get_schema(1)
            if (value == null) l.secret_password_clear_sync(schema, null, null, *attrs)
            else l.secret_password_store_sync(schema, null, "muSE: Jellyfin sign-in", value, null, null, *attrs)
        }.getOrDefault(false)
        if (value == null) { fallback.delete(); return }
        if (stored) fallback.delete() else Paths.writePrivate(fallback, value)
    }

    fun get(): String? {
        val l = lib
        val fromKeyring = l?.let { runCatching {
            val p = l.secret_password_lookup_sync(l.secret_get_schema(1), null, null, *attrs) ?: return@runCatching null
            p.getString(0).also { l.secret_password_free(p) }
        }.getOrNull() }
        return fromKeyring ?: fallback.takeIf { it.exists() }?.readText()
    }
}
