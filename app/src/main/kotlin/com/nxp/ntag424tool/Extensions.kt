package com.nxp.ntag424tool

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment

// ─── ByteArray / String ─────────────────────────────────────────────────────

fun ByteArray.toHexString(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToByteArray(): ByteArray {
    val clean = replace("\\s+".toRegex(), "")
    require(clean.length % 2 == 0) { "Longitud hex debe ser par" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/** Convierte un Int en 3 bytes little-endian (para offsets AN12196). */
fun Int.to3BytesLE(): ByteArray = byteArrayOf(
    (this and 0xFF).toByte(),
    ((this shr 8) and 0xFF).toByte(),
    ((this shr 16) and 0xFF).toByte()
)

fun String.isValidHexKey(): Boolean =
    replace("\\s+".toRegex(), "").let { it.length == 32 && it.all { c -> c.isHexDigit() } }

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

// ─── UI helpers ─────────────────────────────────────────────────────────────

fun View.show() { visibility = View.VISIBLE }
fun View.hide() { visibility = View.GONE }
fun View.invisible() { visibility = View.INVISIBLE }

fun Context.toast(msg: String, long: Boolean = false) =
    Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Fragment.toast(msg: String, long: Boolean = false) =
    requireContext().toast(msg, long)

// ─── Decodificación de versión ──────────────────────────────────────────────

fun decodeStorageSize(code: Int): String = when (code) {
    0x0F -> "128 bytes"
    0x11 -> "256 bytes"
    0x13 -> "512 bytes"
    0x15 -> "1 KB"
    0x17 -> "2 KB"
    0x19 -> "4 KB"
    0x1A -> "4 KB (exacto)"
    else  -> "Código: 0x%02X".format(code)
}

// ─── Plantillas NDEF SDM ────────────────────────────────────────────────────

object NdefTemplates {
    fun piccCmac(base: String) =
        "${base}uid=00000000000000&ctr=000000&c=0000000000000000"

    fun encPiccCmac(base: String) =
        "${base}e=00000000000000000000000000000000&c=0000000000000000"

    fun fullEnc(base: String) =
        "${base}picc_data=00000000000000000000000000000000" +
        "&enc=00000000000000000000000000000000" +
        "&cmac=0000000000000000"
}
