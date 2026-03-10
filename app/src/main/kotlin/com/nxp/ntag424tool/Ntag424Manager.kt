package com.nxp.ntag424tool

import android.util.Log
import com.nxp.nfclib.CardType
import com.nxp.nfclib.defaultimpl.KeyData
import com.nxp.nfclib.desfire.INTAG424DNA
import com.nxp.nfclib.desfire.INTAG424DNATT
import com.nxp.nfclib.desfire.MFPCard
import com.nxp.nfclib.desfire.NTAG424DNAFileSettings
import com.nxp.nfclib.ndef.INdefMessage
import com.nxp.nfclib.ndef.NdefMessageWrapper
import com.nxp.nfclib.ndef.NdefRecordWrapper
import javax.crypto.spec.SecretKeySpec

private const val TAG = "Ntag424Manager"

private val NTAG424_APP_NAME = byteArrayOf(
    0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
)

class Ntag424Manager(
    private val tag: INTAG424DNA,
    private val keyStore: KeyStore
) {

    // ─── Conexión ──────────────────────────────────────────────────────────

    private fun ensureConnected() {
        try {
            if (!tag.reader.isConnected) {
                Log.d(TAG, "ensureConnected: reader no conectado, reconectando...")
                tag.reader.connect()
                Log.d(TAG, "ensureConnected: reconectado OK")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureConnected: ${e.message}")
        }
    }

    private fun selectApp() {
        Log.d(TAG, "selectApp: isoSelectPICC...")
        tag.isoSelectPICC()
        Log.d(TAG, "selectApp: isoSelectApplicationByDFName...")
        tag.isoSelectApplicationByDFName(NTAG424_APP_NAME)
        Log.d(TAG, "selectApp: OK")
    }

    // ─── Leer info ────────────────────────────────────────────────────────

    fun readCardInfo(): OperationResult<CardInfo> = runCatching {
        ensureConnected()
        selectApp()

        val uid      = tag.uid?.toHexString() ?: "--"
        val cardType = tag.type
        val isTagTamper = cardType == CardType.NTAG424DNATagTamper
        val typeName = cardType?.tagName ?: "NTAG 424 DNA"

        var hwMajor = "--"; var hwMinor = "--"
        var swMajor = "--"; var swMinor = "--"
        var vendor  = "--"; var storage = "--"; var batch = "--"

        runCatching {
            val v = tag.version
            if (v != null && v.size >= 14) {
                vendor  = if (v[0] == 0x04.toByte()) "NXP Semiconductors" else "Desconocido"
                hwMajor = "0x%02X".format(v[3].toInt() and 0xFF)
                hwMinor = "0x%02X".format(v[4].toInt() and 0xFF)
                swMajor = "0x%02X".format(v[10].toInt() and 0xFF)
                swMinor = "0x%02X".format(v[11].toInt() and 0xFF)
                storage = decodeStorageSize(v[5].toInt() and 0xFF)
                if (v.size >= 21) batch = v.copyOfRange(14, 19).toHexString()
            }
        }

        runCatching { storage = "${tag.totalMemory} bytes" }

        var ttPerm = "--"; var ttCurr = "--"
        if (isTagTamper && tag is INTAG424DNATT) {
            runCatching {
                tag.authenticateEV2First(0, buildKeyData(0), null)
                val tt = (tag as INTAG424DNATT).ttStatus
                ttPerm = tt.permanentStatus.toString()
                ttCurr = tt.currentStatus.toString()
            }.onFailure { ttPerm = "Error auth: ${it.message}" }
        }

        val fileSettings = runCatching {
            ensureConnected()
            selectApp()
            tag.authenticateEV2First(0, buildKeyData(0), null)
            "Archivo NDEF (0x02) presente — ve a la pestaña SDM para configurarlo."
        }.getOrElse { "No autenticado: configura la Clave 0 primero." }

        OperationResult.ok(
            "Tarjeta leída",
            CardInfo(
                uid = uid, cardType = typeName, vendor = vendor,
                hwMajor = hwMajor, hwMinor = hwMinor,
                swMajor = swMajor, swMinor = swMinor,
                storage = storage, batchNo = batch,
                isTagTamper = isTagTamper,
                ttPermStatus = ttPerm, ttCurrStatus = ttCurr,
                fileSettings = fileSettings
            )
        )
    }.getOrElse { e ->
        Log.e(TAG, "readCardInfo: ${e.javaClass.name}: ${e.message}", e)
        OperationResult(false, "Error: ${e.message}", null)
    }

    // ─── Escribir NDEF ────────────────────────────────────────────────────

    fun writeNdef(content: String, type: NdefType, authKeyNum: Int): OperationResult<Unit> =
        runCatching {
            ensureConnected()
            Log.d(TAG, "writeNdef: selectApp...")
            selectApp()
            Log.d(TAG, "writeNdef: autenticando con clave $authKeyNum...")
            tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
            Log.d(TAG, "writeNdef: autenticación OK, construyendo mensaje...")

            val msg = when (type) {
                NdefType.URL -> NdefMessageWrapper(
                    NdefRecordWrapper(
                        NdefRecordWrapper.TNF_WELL_KNOWN,
                        "U".toByteArray(Charsets.US_ASCII),
                        ByteArray(0),
                        buildUriPayload(content)
                    )
                )
                NdefType.TEXT -> {
                    val lang    = "es".toByteArray(Charsets.US_ASCII)
                    val text    = content.toByteArray(Charsets.UTF_8)
                    val payload = ByteArray(1 + lang.size + text.size)
                    payload[0]  = lang.size.toByte()
                    lang.copyInto(payload, 1)
                    text.copyInto(payload, 1 + lang.size)
                    NdefMessageWrapper(
                        NdefRecordWrapper(
                            NdefRecordWrapper.TNF_WELL_KNOWN,
                            "T".toByteArray(Charsets.US_ASCII),
                            ByteArray(0), payload
                        )
                    )
                }
                NdefType.RAW_HEX -> NdefMessageWrapper(
                    NdefRecordWrapper(
                        NdefRecordWrapper.TNF_ABSOLUTE_URI,
                        "application/octet-stream".toByteArray(Charsets.US_ASCII),
                        ByteArray(0),
                        content.replace("\\s+".toRegex(), "").hexToByteArray()
                    )
                )
            }

            Log.d(TAG, "writeNdef: escribiendo mensaje NDEF...")
            tag.writeNDEF(msg)
            Log.d(TAG, "writeNdef: OK")
            OperationResult.ok("NDEF escrito correctamente")
        }.getOrElse { e ->
            Log.e(TAG, "writeNdef: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    // ─── Leer NDEF ────────────────────────────────────────────────────────

    fun readNdef(authKeyNum: Int): OperationResult<String> = runCatching {
        ensureConnected()
        selectApp()

        val msg: INdefMessage = runCatching {
            tag.readNDEF()
        }.getOrElse {
            Log.d(TAG, "readNdef: lectura sin auth falló, autenticando...")
            tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
            tag.readNDEF()
        }

        OperationResult.ok("NDEF leído", msg.ndefToString())
    }.getOrElse { e ->
        Log.e(TAG, "readNdef: ${e.javaClass.name}: ${e.message}", e)
        OperationResult(false, "Error: ${e.message}", null)
    }

    private fun INdefMessage.ndefToString(): String {
        val bytes = toByteArray() ?: return "(vacío)"
        val hex   = bytes.toHexString()
        val text  = runCatching {
            val s       = bytes.toString(Charsets.US_ASCII)
            val httpIdx = s.indexOf("http")
            if (httpIdx >= 0) "URL: ${s.substring(httpIdx)}" else "Texto: ${s.trim()}"
        }.getOrElse { "" }
        return buildString {
            appendLine("Hex: $hex")
            if (text.isNotEmpty()) append(text)
        }
    }

    // ─── Aplicar SDM ──────────────────────────────────────────────────────

    fun applySdmConfig(config: SdmConfig, authKeyNum: Int): OperationResult<String> =
        runCatching {
            ensureConnected()
            selectApp()
            tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)

            val ar = config.buildAccessRightsBytes()
            val fs = NTAG424DNAFileSettings(
                MFPCard.CommunicationMode.Plain,
                ar[0], ar[1], 0x00, 0x00
            )

            fs.isSDMEnabled = config.sdmEnabled

            if (config.sdmEnabled) {
                fs.isUIDMirroringEnabled          = config.uidMirror
                fs.isSDMReadCounterEnabled        = config.counterMirror
                fs.isSDMReadCounterLimitEnabled   = config.ctrLimitEnabled
                fs.isSDMEncryptFileDataEnabled    = config.encFileData
                fs.setSdmAccessRights(config.buildSdmAccessRightsBytes())

                if (config.uidMirror && !config.encPiccData)
                    fs.setUidOffset(config.uidOffset.to3BytesLE())
                if (config.counterMirror && !config.encPiccData)
                    fs.setSdmReadCounterOffset(config.ctrOffset.to3BytesLE())
                if (config.encPiccData)
                    fs.setPiccDataOffset(config.piccEncOffset.to3BytesLE())
                if (config.encFileData) {
                    fs.setSdmEncryptionOffset(config.encOffset.to3BytesLE())
                    fs.setSdmEncryptionLength(config.encLength.to3BytesLE())
                }
                fs.setSdmMacInputOffset(config.macInputOffset.to3BytesLE())
                fs.setSdmMacOffset(config.macOffset.to3BytesLE())
                if (config.ctrLimitEnabled)
                    fs.setSdmReadCounterLimit(config.ctrLimit.to3BytesLE())
            }

            tag.changeFileSettings(0x02, fs)

            val summary = buildString {
                appendLine("✅ Configuración SDM aplicada")
                appendLine("FileOption: 0x%02X".format(config.buildFileOptionByte().toInt() and 0xFF))
                appendLine("AccessRights: ${ar.toHexString()}")
                appendLine("SDMOptions: 0x%02X".format(config.buildSdmOptionsByte().toInt() and 0xFF))
                append("SDMAccessRights: ${config.buildSdmAccessRightsBytes().toHexString()}")
            }
            OperationResult.ok(summary, summary)
        }.getOrElse { e ->
            Log.e(TAG, "applySdmConfig: ${e.javaClass.name}: ${e.message}", e)
            OperationResult(false, "Error: ${e.message}", null)
        }

    // ─── Cambiar clave ────────────────────────────────────────────────────

    fun changeKey(
        keyNumToChange: Int,
        newKeyBytes: ByteArray,
        keyVersion: Int,
        authKeyNum: Int
    ): OperationResult<Unit> = runCatching {
        ensureConnected()
        selectApp()
        tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
        tag.changeKey(keyNumToChange, newKeyBytes, keyStore[keyNumToChange], keyVersion.toByte())
        keyStore[keyNumToChange] = newKeyBytes
        OperationResult.ok("Clave $keyNumToChange cambiada.\nNueva: ${newKeyBytes.toHexString()}")
    }.getOrElse { e ->
        Log.e(TAG, "changeKey: ${e.javaClass.name}: ${e.message}", e)
        OperationResult.fail("Error: ${e.message}")
    }

    // ─── Random ID ────────────────────────────────────────────────────────

    fun enableRandomId(authKeyNum: Int): OperationResult<Unit> = runCatching {
        ensureConnected()
        selectApp()
        tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
        tag.setPICCConfiguration(true)
        OperationResult.ok(
            "Random ID habilitado.\n⚠️ OPERACIÓN IRREVERSIBLE.\n" +
            "ATQA cambia de 0x0344 → 0x0304."
        )
    }.getOrElse { e ->
        OperationResult.fail("Error: ${e.message}")
    }

    // ─── Obtener UID real ─────────────────────────────────────────────────

    fun getRealUid(authKeyNum: Int): OperationResult<String> = runCatching {
        ensureConnected()
        selectApp()
        tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
        val uid = tag.cardUID.toHexString()
        OperationResult.ok("UID real: $uid", uid)
    }.getOrElse { e ->
        OperationResult(false, "Error: ${e.message}", null)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun buildKeyData(keyIndex: Int): KeyData = buildKeyData(keyStore[keyIndex])

    private fun buildKeyData(keyBytes: ByteArray): KeyData =
        KeyData().apply { key = SecretKeySpec(keyBytes, "AES") }

    private fun buildUriPayload(url: String): ByteArray {
        val prefixes = listOf(
            "https://www." to 0x04,
            "http://www."  to 0x02,
            "https://"     to 0x03,
            "http://"      to 0x01
        )
        for ((prefix, code) in prefixes) {
            if (url.startsWith(prefix)) {
                val rest = url.removePrefix(prefix).toByteArray(Charsets.US_ASCII)
                return byteArrayOf(code.toByte()) + rest
            }
        }
        return byteArrayOf(0x00) + url.toByteArray(Charsets.US_ASCII)
    }
}

enum class NdefType { URL, TEXT, RAW_HEX }