package com.nxp.ntag424tool

import android.util.Log
import com.nxp.nfclib.KeyType
import com.nxp.nfclib.defaultimpl.KeyData
import com.nxp.nfclib.desfire.DESFireFile
import com.nxp.nfclib.desfire.EV1ApplicationKeySettings
import com.nxp.nfclib.desfire.IDESFireEV1
import com.nxp.nfclib.desfire.IDESFireEV3
import com.nxp.nfclib.ndef.NdefMessageWrapper
import com.nxp.nfclib.ndef.NdefRecordWrapper
import javax.crypto.spec.SecretKeySpec

private const val TAG = "DesfireManager"

class DesfireManager(
    private val tag: IDESFireEV3,
    private val keyStore: KeyStore
) {

    private fun reconnect() {
        runCatching { tag.reader.close() }
        Thread.sleep(50)
        tag.reader.connect()
        tag.reader.setTimeout(5000L)
        Log.d(TAG, "reconnect OK")
    }

    fun readCardInfo(): OperationResult<CardInfo> = runCatching {
        reconnect()
        val details  = tag.cardDetails
        val uid      = details?.uid?.toHexString() ?: "--"
        val vendor   = if ((details?.vendorID ?: 0) == 0x04) "NXP Semiconductors"
                       else "0x%02X".format(details?.vendorID ?: 0)
        val typeName = details?.cardName ?: (tag.type?.tagName ?: "DESFire EV3")
        val free     = "${details?.freeMemory ?: "--"} bytes"
        val total    = "${details?.totalMemory ?: "--"} bytes"
        val hwMajor  = "0x%02X".format(details?.majorVersion ?: 0)
        val hwMinor  = "0x%02X".format(details?.minorVersion ?: 0)
        var swMajor  = "--"; var swMinor = "--"; var batch = "--"
        runCatching {
            val v = tag.version
            if (v != null && v.size >= 14) {
                swMajor = "0x%02X".format(v[10].toInt() and 0xFF)
                swMinor = "0x%02X".format(v[11].toInt() and 0xFF)
                if (v.size >= 21) batch = v.copyOfRange(14, 19).toHexString()
            }
        }
        OperationResult.ok("Tarjeta leída", CardInfo(
            uid = uid, cardType = typeName, vendor = vendor,
            hwMajor = hwMajor, hwMinor = hwMinor,
            swMajor = swMajor, swMinor = swMinor,
            storage = total, batchNo = batch, freeMemory = free
        ))
    }.getOrElse { e ->
        Log.e(TAG, "readCardInfo: ${e.javaClass.name}: ${e.message}", e)
        OperationResult(false, "Error: ${e.message}", null)
    }

    fun listApplications(): OperationResult<List<AppInfo>> = runCatching {
        reconnect()
        tag.selectApplication(0)
        tag.authenticateEV2First(0, buildKeyData(0), null)
        val aids = tag.applicationIDs ?: intArrayOf()
        val apps = aids.map { aidInt ->
            AppInfo(aid = byteArrayOf(
                (aidInt         and 0xFF).toByte(),
                ((aidInt shr 8)  and 0xFF).toByte(),
                ((aidInt shr 16) and 0xFF).toByte()
            ))
        }
        OperationResult.ok("${apps.size} aplicaciones encontradas", apps)
    }.getOrElse { e ->
        Log.e(TAG, "listApplications: ${e.javaClass.name}: ${e.message}", e)
        OperationResult(false, "Error: ${e.message}", null)
    }

    fun createApplication(config: NewAppConfig, piccAuthKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(0)
            tag.authenticateEV2First(piccAuthKey, buildKeyData(piccAuthKey), null)
            val keySettingsByte = 0x0F.toByte()
            val numKeysByte     = (0x80 or (config.numKeys and 0x0F)).toByte()
            val appKeySettings  = EV1ApplicationKeySettings(keySettingsByte, numKeysByte)
            tag.createApplication(config.aid, appKeySettings)
            OperationResult.ok("Aplicación ${config.aid.toHexString()} creada")
        }.getOrElse { e ->
            Log.e(TAG, "createApplication: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun deleteApplication(aid: ByteArray, piccAuthKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(0)
            tag.authenticateEV2First(piccAuthKey, buildKeyData(piccAuthKey), null)
            tag.deleteApplication(aid)
            OperationResult.ok("Aplicación ${aid.toHexString()} borrada")
        }.getOrElse { e ->
            Log.e(TAG, "deleteApplication: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun listFiles(aid: ByteArray, authKey: Int): OperationResult<List<FileInfo>> =
        runCatching {
            reconnect()
            tag.selectApplication(aid)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            val fileIds = tag.fileIDs ?: byteArrayOf()
            val files = fileIds.map { fid ->
                val fidInt = fid.toInt() and 0xFF
                runCatching {
                    val s = tag.getFileSettings(fidInt)
                    FileInfo(
                        fileNo    = fidInt,
                        type      = when (s.type) {
                            DESFireFile.FileType.DataStandard  -> FileType.STANDARD
                            DESFireFile.FileType.DataBackup    -> FileType.BACKUP
                            DESFireFile.FileType.Value         -> FileType.VALUE
                            DESFireFile.FileType.RecordLinear  -> FileType.LINEAR_RECORD
                            DESFireFile.FileType.RecordCyclic  -> FileType.CYCLIC_RECORD
                            else                               -> FileType.STANDARD
                        },
                        commMode  = when (s.comSettings) {
                            IDESFireEV1.CommunicationType.MACed      -> CommMode.MAC
                            IDESFireEV1.CommunicationType.Enciphered -> CommMode.FULL
                            else                                     -> CommMode.PLAIN
                        },
                        readKey   = s.readAccess.toInt()      and 0xFF,
                        writeKey  = s.writeAccess.toInt()     and 0xFF,
                        rwKey     = s.readWriteAccess.toInt() and 0xFF,
                        changeKey = s.changeAccess.toInt()    and 0xFF,
                        size      = runCatching {
                            val m = s.javaClass.methods.firstOrNull {
                                it.name.lowercase().contains("size") && it.parameterCount == 0
                            }
                            (m?.invoke(s) as? Int) ?: 0
                        }.getOrDefault(0)
                    )
                }.getOrElse {
                    FileInfo(fileNo = fidInt, type = FileType.STANDARD, commMode = CommMode.PLAIN)
                }
            }
            OperationResult.ok("${files.size} archivos", files)
        }.getOrElse { e ->
            Log.e(TAG, "listFiles: ${e.javaClass.name}: ${e.message}", e)
            OperationResult(false, "Error: ${e.message}", null)
        }

    fun readFile(aid: ByteArray, fileNo: Int, authKey: Int): OperationResult<String> =
        runCatching {
            reconnect()
            tag.selectApplication(aid)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            val data = tag.readData(fileNo, 0, 0)
            val hex  = data.toHexString()
            val text = runCatching {
                data.toString(Charsets.UTF_8).trimEnd('\u0000').trim()
            }.getOrElse { "" }
            val result = buildString {
                appendLine("Hex: $hex")
                if (text.isNotEmpty() && text.any { it.isLetterOrDigit() })
                    append("UTF-8: $text")
            }
            OperationResult.ok("Archivo leído", result)
        }.getOrElse { e ->
            Log.e(TAG, "readFile: ${e.javaClass.name}: ${e.message}", e)
            OperationResult(false, "Error: ${e.message}", null)
        }

    fun writeFile(aid: ByteArray, fileNo: Int, data: ByteArray, authKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(aid)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            tag.writeData(fileNo, 0, data)
            OperationResult.ok("${data.size} bytes escritos")
        }.getOrElse { e ->
            Log.e(TAG, "writeFile: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun createStandardFile(aid: ByteArray, config: NewFileConfig, authKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(aid)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            val commType = when (config.commMode) {
                CommMode.MAC  -> IDESFireEV1.CommunicationType.MACed
                CommMode.FULL -> IDESFireEV1.CommunicationType.Enciphered
                else          -> IDESFireEV1.CommunicationType.Plain
            }
            val settings = DESFireFile.StdDataFileSettings(
                commType,
                config.rwKey.toByte(),
                config.changeKey.toByte(),
                config.readKey.toByte(),
                config.writeKey.toByte(),
                config.size
            )
            tag.createFile(config.fileNo, settings)
            OperationResult.ok("Archivo ${config.fileNo} creado (${config.size} bytes)")
        }.getOrElse { e ->
            Log.e(TAG, "createStandardFile: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun deleteFile(aid: ByteArray, fileNo: Int, authKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(aid)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            tag.deleteFile(fileNo)
            OperationResult.ok("Archivo $fileNo borrado")
        }.getOrElse { e ->
            Log.e(TAG, "deleteFile: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun changeKey(
        aid: ByteArray, keyNumToChange: Int,
        newKeyBytes: ByteArray, keyVersion: Byte, authKeyNum: Int
    ): OperationResult<Unit> = runCatching {
        reconnect()
        tag.selectApplication(aid)
        tag.authenticateEV2First(authKeyNum, buildKeyData(authKeyNum), null)
        tag.changeKey(keyNumToChange, KeyType.AES128, newKeyBytes, keyStore[keyNumToChange], keyVersion)
        keyStore[keyNumToChange] = newKeyBytes
        OperationResult.ok("Clave $keyNumToChange cambiada")
    }.getOrElse { e ->
        Log.e(TAG, "changeKey: ${e.javaClass.name}: ${e.message}", e)
        OperationResult.fail("Error: ${e.message}")
    }

    fun formatPICC(piccAuthKey: Int): OperationResult<Unit> = runCatching {
        reconnect()
        tag.selectApplication(0)
        tag.authenticateEV2First(piccAuthKey, buildKeyData(piccAuthKey), null)
        tag.format()
        OperationResult.ok("Tarjeta formateada a estado de fábrica")
    }.getOrElse { e ->
        Log.e(TAG, "formatPICC: ${e.javaClass.name}: ${e.message}", e)
        OperationResult.fail("Error: ${e.message}")
    }

    fun setupNdefApplication(url: String, piccAuthKey: Int): OperationResult<Unit> =
        runCatching {
            reconnect()
            tag.selectApplication(0)
            tag.authenticateEV2First(piccAuthKey, buildKeyData(piccAuthKey), null)
            tag.writeNDEF(buildNdefUrlMessage(url))
            OperationResult.ok("URL NDEF escrita: $url")
        }.getOrElse { e ->
            Log.e(TAG, "setupNdefApplication: ${e.javaClass.name}: ${e.message}", e)
            OperationResult.fail("Error: ${e.message}")
        }

    fun readNdef(authKey: Int): OperationResult<String> = runCatching {
        reconnect()
        val msg = runCatching { tag.readNDEF() }.getOrElse {
            tag.selectApplication(0)
            tag.authenticateEV2First(authKey, buildKeyData(authKey), null)
            tag.readNDEF()
        }
        val bytes = msg.toByteArray() ?: return@runCatching OperationResult.ok("(vacío)", "(vacío)")
        val text  = runCatching {
            val s = bytes.toString(Charsets.US_ASCII)
            val i = s.indexOf("http")
            if (i >= 0) "URL: ${s.substring(i)}" else s.trim()
        }.getOrElse { "" }
        OperationResult.ok("NDEF leído", buildString {
            appendLine("Hex: ${bytes.toHexString()}")
            if (text.isNotEmpty()) append(text)
        })
    }.getOrElse { e ->
        Log.e(TAG, "readNdef: ${e.javaClass.name}: ${e.message}", e)
        OperationResult(false, "Error: ${e.message}", null)
    }

    private fun buildKeyData(keyIndex: Int): KeyData =
        KeyData().apply { key = SecretKeySpec(keyStore[keyIndex], "AES") }

    private fun buildNdefUrlMessage(url: String): NdefMessageWrapper {
        val prefixes = listOf(
            "https://www." to 0x04, "http://www." to 0x02,
            "https://"     to 0x03, "http://"     to 0x01
        )
        val payload = run {
            for ((prefix, code) in prefixes) {
                if (url.startsWith(prefix))
                    return@run byteArrayOf(code.toByte()) +
                           url.removePrefix(prefix).toByteArray(Charsets.US_ASCII)
            }
            byteArrayOf(0x00) + url.toByteArray(Charsets.US_ASCII)
        }
        return NdefMessageWrapper(
            NdefRecordWrapper(
                NdefRecordWrapper.TNF_WELL_KNOWN,
                "U".toByteArray(Charsets.US_ASCII),
                ByteArray(0), payload
            )
        )
    }
}
