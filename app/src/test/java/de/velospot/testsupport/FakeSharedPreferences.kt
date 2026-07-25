package de.velospot.testsupport

import android.content.Context
import android.content.SharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/**
 * In-memory [SharedPreferences] for JVM unit tests (no Robolectric). Backs a plain
 * map and supports the KTX `edit { }` extension (apply/commit + remove/clear).
 */
class FakeSharedPreferences : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()

    inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var doClear = false
        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending[key!!] = REMOVE }
        override fun clear() = apply { doClear = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }
        private fun flush() {
            if (doClear) map.clear()
            pending.forEach { (k, v) -> if (v === REMOVE) map.remove(k) else map[k] = v }
            pending.clear(); doClear = false
        }
    }

    private companion object { val REMOVE = Any() }
}

/**
 * A mock [Context] whose `getSharedPreferences(name, mode)` returns a per-name
 * [FakeSharedPreferences] (stable across calls, so writes are visible on re-read).
 */
fun fakeContextWithPrefs(): Context {
    val byName = HashMap<String, FakeSharedPreferences>()
    return mock {
        on { getSharedPreferences(any(), any()) } doAnswer { inv ->
            byName.getOrPut(inv.getArgument(0)) { FakeSharedPreferences() }
        }
    }
}

