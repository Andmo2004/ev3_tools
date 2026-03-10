package com.nxp.ntag424tool

// ─── Resultado genérico de operación ───────────────────────────────────────

data class OperationResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
) {
    companion object {
        fun ok(message: String) = OperationResult<Unit>(true, message)
        fun <T> ok(message: String, data: T) = OperationResult(true, message, data)
        fun fail(message: String) = OperationResult<Unit>(false, message)
    }
}

// ─── Información de la tarjeta ──────────────────────────────────────────────

data class CardInfo(
    val uid: String = "--",
    val cardType: String = "--",
    val vendor: String = "--",
    val hwMajor: String = "--",
    val hwMinor: String = "--",
    val swMajor: String = "--",
    val swMinor: String = "--",
    val storage: String = "--",
    val batchNo: String = "--",
    val isTagTamper: Boolean = false,
    val ttPermStatus: String = "--",
    val ttCurrStatus: String = "--",
    val fileSettings: String = ""
)

// ─── Configuración SDM completa (AN12196) ───────────────────────────────────

data class SdmConfig(
    // FileOption
    var sdmEnabled: Boolean = true,
    var commMode: CommMode = CommMode.PLAIN,

    // SDMOptions
    var uidMirror: Boolean = false,
    var counterMirror: Boolean = true,
    var encPiccData: Boolean = true,
    var encFileData: Boolean = false,
    var ttMirror: Boolean = false,
    var asciiEncoding: Boolean = true,
    var ctrLimitEnabled: Boolean = false,
    var ctrLimit: Int = 0xFFFFFF,

    // SDMAccessRights
    var sdmMetaReadKey: KeyAccess = KeyAccess.Key(2),
    var sdmFileReadKey: KeyAccess = KeyAccess.Key(1),
    var sdmCtrRetKey: KeyAccess = KeyAccess.Free,

    // FileAccessRights
    var rwKey: KeyAccess = KeyAccess.Key(0),
    var changeKey: KeyAccess = KeyAccess.Key(0),
    var readKey: KeyAccess = KeyAccess.Free,
    var writeKey: KeyAccess = KeyAccess.Key(0),

    // Offsets (bytes, decimal)
    var uidOffset: Int = 32,
    var ctrOffset: Int = 46,
    var piccEncOffset: Int = 32,
    var encOffset: Int = 68,
    var encLength: Int = 32,
    var macInputOffset: Int = 67,
    var macOffset: Int = 67
) {
    /** Construye el byte FileOption según la especificación. */
    fun buildFileOptionByte(): Byte {
        var opt = 0
        if (sdmEnabled) opt = opt or 0x40
        opt = opt or when (commMode) {
            CommMode.PLAIN -> 0x00
            CommMode.MAC   -> 0x01
            CommMode.FULL  -> 0x03
        }
        return opt.toByte()
    }

    /** Construye los 2 bytes de AccessRights [RW|Change][Read|Write]. */
    fun buildAccessRightsBytes(): ByteArray {
        val rw = rwKey.nibble
        val ch = changeKey.nibble
        val r  = readKey.nibble
        val w  = writeKey.nibble
        return byteArrayOf(((rw shl 4) or ch).toByte(), ((r shl 4) or w).toByte())
    }

    /** Construye el byte SDMOptions. */
    fun buildSdmOptionsByte(): Byte {
        var opt = 0
        if (uidMirror)        opt = opt or 0x80
        if (counterMirror)    opt = opt or 0x40
        if (ctrLimitEnabled)  opt = opt or 0x20
        if (encFileData)      opt = opt or 0x10
        if (ttMirror)         opt = opt or 0x08
        if (asciiEncoding)    opt = opt or 0x01
        return opt.toByte()
    }

    /** Construye los 2 bytes de SDMAccessRights [0xF|CtrRet][MetaRead|FileRead]. */
    fun buildSdmAccessRightsBytes(): ByteArray {
        val ctrRet  = sdmCtrRetKey.nibble
        val metaRead = sdmMetaReadKey.nibble
        val fileRead = sdmFileReadKey.nibble
        return byteArrayOf((0xF0 or ctrRet).toByte(), ((metaRead shl 4) or fileRead).toByte())
    }
}

// ─── Tipos auxiliares ───────────────────────────────────────────────────────

enum class CommMode { PLAIN, MAC, FULL }

/** Representa un valor de acceso a clave: key 0-4, libre (E) o sin acceso (F). */
sealed class KeyAccess(val nibble: Int) {
    class Key(val index: Int) : KeyAccess(index)
    object Free     : KeyAccess(0x0E)
    object NoAccess : KeyAccess(0x0F)

    override fun toString(): String = when (this) {
        is Key      -> "Clave $index"
        is Free     -> "Libre (E)"
        is NoAccess -> "Sin acceso (F)"
        else        -> "?"
    }
}

/** Convierte un índice de spinner (0-6) a KeyAccess. */
fun Int.toKeyAccess(): KeyAccess = when (this) {
    in 0..4 -> KeyAccess.Key(this)
    5       -> KeyAccess.Free
    else    -> KeyAccess.NoAccess
}

/** Convierte un KeyAccess a índice de spinner. */
fun KeyAccess.toSpinnerIndex(): Int = when (this) {
    is KeyAccess.Key -> index
    is KeyAccess.Free -> 5
    else -> 6
}
