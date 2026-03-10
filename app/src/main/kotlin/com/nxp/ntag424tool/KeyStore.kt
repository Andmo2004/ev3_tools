package com.nxp.ntag424tool

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Almacena y persiste las 5 claves AES-128 (0-4) de la aplicación NTAG 424 DNA.
 * Los valores se guardan en SharedPreferences como strings hex.
 */
class KeyStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "ntag424_keys"
        private const val KEY_PREFIX = "key_"
        const val KEY_COUNT = 5
        val DEFAULT_KEY = ByteArray(16) // todo ceros

        fun generateRandom(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keys: Array<ByteArray> = Array(KEY_COUNT) { i ->
        prefs.getString("$KEY_PREFIX$i", null)
            ?.takeIf { it.length == 32 }
            ?.runCatching { hexToByteArray() }
            ?.getOrNull()
            ?: DEFAULT_KEY.clone()
    }

    operator fun get(index: Int): ByteArray {
        require(index in 0 until KEY_COUNT)
        return keys[index].clone()
    }

    operator fun set(index: Int, value: ByteArray) {
        require(index in 0 until KEY_COUNT)
        require(value.size == 16) { "La clave debe ser de 16 bytes" }
        keys[index] = value.clone()
    }

    fun getHex(index: Int): String = get(index).toHexString()

    /** Retorna true si la cadena hex es válida y guarda la clave. */
    fun setFromHex(index: Int, hex: String): Boolean {
        if (!hex.isValidHexKey()) return false
        return runCatching {
            set(index, hex.replace("\\s+".toRegex(), "").hexToByteArray())
            true
        }.getOrDefault(false)
    }

    fun save() {
        prefs.edit().apply {
            keys.forEachIndexed { i, k -> putString("$KEY_PREFIX$i", k.toHexString()) }
        }.apply()
    }

    fun resetToDefaults() {
        keys.indices.forEach { keys[it] = DEFAULT_KEY.clone() }
        save()
    }
}
