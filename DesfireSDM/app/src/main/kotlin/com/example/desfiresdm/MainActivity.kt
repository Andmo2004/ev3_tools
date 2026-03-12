package com.example.desfiresdm

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.nxp.nfclib.CardType
import com.nxp.nfclib.KeyType
import com.nxp.nfclib.NxpNfcLib
import com.nxp.nfclib.defaultimpl.KeyData
import com.nxp.nfclib.desfire.DESFireEV3File
import com.nxp.nfclib.desfire.DESFireFactory
import com.nxp.nfclib.desfire.DESFireFile
import com.nxp.nfclib.desfire.EV1ApplicationKeySettings
import com.nxp.nfclib.desfire.IDESFireEV1
import com.nxp.nfclib.desfire.IDESFireEV2
import com.nxp.nfclib.desfire.IDESFireEV3
import com.nxp.nfclib.ndef.NdefMessageWrapper
import com.nxp.nfclib.ndef.NdefRecordWrapper
import com.nxp.nfclib.utils.Utilities
import java.security.Key
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DesfireSDM"
        private val DEFAULT_AES_KEY = ByteArray(16) { 0x00 }
        private val DEFAULT_DES_KEY = ByteArray(16) { 0x00 }
        private val NDEF_APP_AID = byteArrayOf(0x00, 0x00, 0x01)
        private const val NDEF_FILE_NO = 2
        private const val ENC_PICC_LEN = 32
        private const val SDMMAC_LEN   = 16
        private const val PACKAGE_KEY =
            "pmHc+9ACbu+JGvPgXl3kCq5pL/dV8Bi6iPQPCMl2YLcW7Rp2+Tku5j63LWzlp0wx0Kc2YBjWNG1zSmEmk+EmLnZheF610JYNEejpLmmIZzZSuWKeMJeoADt9gBFTOgzAOt4XCYUTodRVJze6Kc2fimCwoBXJsBTICdf2F5yvhDhKhklvZF61Mf9s+TLDWRN0Uqf1wE6K0CyRD0rBlmSzxsgAfRA1nqwmKmUIH1GeKPX360v8z6QkXpDm/ajDwdXQJK2CZQT4uNDzX9GVAAInCQiAFLIjaSds4SEQoJBdx/0vRzGssHmVa+jGCVB6bOT8Td7Qs68v5cUq6WDCNSAuEQ=="
    }

    private var nfcAdapter: NfcAdapter? = null
    private var libInstance: NxpNfcLib? = null
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var etUrl: EditText
    private lateinit var cbUidMirror: CheckBox
    private lateinit var cbCtrMirror: CheckBox
    private lateinit var cbEncUid: CheckBox
    private lateinit var cbSdmMac: CheckBox
    private lateinit var scrollLog: ScrollView
    private var pendingAction = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvLog       = findViewById(R.id.tvLog)
        tvStatus    = findViewById(R.id.tvStatus)
        statusDot   = findViewById(R.id.statusDot)
        etUrl       = findViewById(R.id.etUrl)
        cbUidMirror = findViewById(R.id.cbUidMirror)
        cbCtrMirror = findViewById(R.id.cbCtrMirror)
        cbEncUid    = findViewById(R.id.cbEncUid)
        cbSdmMac    = findViewById(R.id.cbSdmMac)
        scrollLog   = findViewById(R.id.scrollLog)
        etUrl.setText("https://example.com/?e=${"0".repeat(ENC_PICC_LEN)}&m=${"0".repeat(SDMMAC_LEN)}")
        initTapLinx()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) showDialog("Error", "Este dispositivo no tiene NFC.")
        findViewById<Button>(R.id.btnWrite).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty() || !url.startsWith("http")) { toast("URL inválida"); return@setOnClickListener }
            pendingAction = "write"
            setStatus("Acerque el tag DESFire EV3...", "#FFA726")
            log("\n▶ ESCRITURA activada\nURL: $url")
        }
        findViewById<Button>(R.id.btnRead).setOnClickListener {
            pendingAction = "read"
            setStatus("Acerque el tag DESFire EV3...", "#FFA726")
            log("\n▶ LECTURA activada")
        }
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
        libInstance?.registerActivity(this, PACKAGE_KEY)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            log("\n📡 Tag detectado!")
            setStatus("Procesando...", "#03DAC6")
            Thread { handleTag(tag) }.start()
        }
    }

    private fun initTapLinx() {
        try {
            libInstance = NxpNfcLib.getInstance()
            libInstance?.registerActivity(this, PACKAGE_KEY)
            log("[TapLinx] Inicializado")
        } catch (e: Exception) { log("[TapLinx] Error: ${e.message}") }
    }

    private fun enableForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
    }

    private fun handleTag(tag: Tag) {
        try {
            val cardType = libInstance?.getCardType(tag) ?: run { uiLog("❌ Tipo desconocido"); return }
            uiLog("Tipo: ${cardType.name}")
            when (cardType) {
                CardType.DESFireEV3, CardType.DESFireEV3C -> {
                    val ev3 = DESFireFactory.getInstance().getDESFireEV3(libInstance?.customModules)
                    ev3.reader.connect()
                    if (pendingAction == "write") writeNdefWithSdm(ev3) else readTag(ev3)
                }
                CardType.DESFireEV2 -> {
                    val ev2 = DESFireFactory.getInstance().getDESFireEV2(libInstance?.customModules)
                    ev2.reader.connect()
                    readTagEV2(ev2)
                }
                else -> uiLog("⚠️ Tag no soportado: ${cardType.name}")
            }
        } catch (e: Exception) { uiLog("❌ Error: ${e.message}"); Log.e(TAG, "handleTag", e) }
    }

    private fun writeNdefWithSdm(ev3: IDESFireEV3) {
        try {
            ev3.reader.setTimeout(5000)
            uiLog("UID: ${Utilities.dumpBytes(ev3.uid)}")
            uiLog("Memoria: ${ev3.totalMemory} bytes")

            // 1. Select PICC
            uiLog("\n[1/6] Seleccionando PICC...")
            ev3.selectApplication(0)
            uiLog("✓ PICC seleccionado")

            // 2. Auth PICC
            uiLog("[2/6] Autenticando PICC...")
            try {
                ev3.authenticate(0, IDESFireEV1.AuthType.Native, KeyType.THREEDES, buildDesKeyData(DEFAULT_DES_KEY))
                uiLog("✓ Auth 2KTDES")
            } catch (e: Exception) {
                uiLog("⚠️ 2KTDES falló, probando AES...")
                try {
                    ev3.authenticateEV2First(0, buildAesKeyData(DEFAULT_AES_KEY), null)
                    uiLog("✓ Auth AES EV2First")
                } catch (e2: Exception) { uiLog("⚠️ Auth PICC falló, continuando...") }
            }

            // 3. Crear/verificar app NDEF
            uiLog("[3/6] App NDEF...")
            val existingApps = try { ev3.applicationIDs } catch (e: Exception) { intArrayOf() }
            val aidInt = byteArrayToInt(NDEF_APP_AID)
            if (!existingApps.any { it == aidInt }) {
                ev3.createApplication(NDEF_APP_AID,
                    EV1ApplicationKeySettings.Builder()
                        .setAppKeySettingsChangeable(true)
                        .setAppMasterKeyChangeable(true)
                        .setAuthenticationRequiredForFileManagement(false)
                        .setAuthenticationRequiredForDirectoryConfigurationData(false)
                        .setKeyTypeOfApplicationKeys(KeyType.AES128)
                        .build())
                uiLog("✓ App NDEF creada")
            } else { uiLog("ℹ️ App ya existe") }

            // 4. Select app + auth
            uiLog("[4/6] Seleccionando app NDEF...")
            // Borrar app si ya existe para empezar limpio
            try {
                ev3.selectApplication(0)
                ev3.authenticate(0, IDESFireEV1.AuthType.Native, KeyType.THREEDES, buildDesKeyData(DEFAULT_DES_KEY))
                ev3.deleteApplication(NDEF_APP_AID)
                uiLog("App anterior borrada")
                // Recrear app
                ev3.createApplication(NDEF_APP_AID,
                    EV1ApplicationKeySettings.Builder()
                        .setAppKeySettingsChangeable(true)
                        .setAppMasterKeyChangeable(true)
                        .setAuthenticationRequiredForFileManagement(false)
                        .setAuthenticationRequiredForDirectoryConfigurationData(false)
                        .setKeyTypeOfApplicationKeys(KeyType.AES128)
                        .build())
                uiLog("App recreada")
            } catch (e: Exception) { uiLog("deleteApp: ${e.message}") }
            ev3.selectApplication(NDEF_APP_AID)
            try {
                ev3.authenticateEV2First(0, buildAesKeyData(DEFAULT_AES_KEY), null)
                uiLog("✓ Auth EV2First")
            } catch (e: Exception) {
                try {
                    ev3.authenticate(0, IDESFireEV1.AuthType.Native, KeyType.AES128, buildAesKeyData(DEFAULT_AES_KEY))
                    uiLog("✓ Auth AES nativa")
                } catch (e2: Exception) { uiLog("⚠️ Auth app falló") }
            }

            // 5. Crear archivo + escribir NDEF
            uiLog("[5/6] Archivo NDEF...")
            val urlTemplate = buildSdmUrl(etUrl.text.toString().trim())
            val ndefMsg = buildNdefMessage(urlTemplate)
            val ndefBytes = ndefMsg.toByteArray()
            val fileSize = run {
                val needed = ndefBytes.size + 2  // +2 for NLEN prefix
                val padded = ((needed + 31) / 32) * 32  // round up to multiple of 32
                maxOf(256, padded)
            }

            uiLog("fileSize calculado: $fileSize bytes")
            val existingFiles = try { ev3.getFileIDs() } catch (e: Exception) { ByteArray(0) }
            uiLog("archivos existentes: ${existingFiles.map { it.toInt() }}")
            if (!existingFiles.any { it.toInt() == NDEF_FILE_NO }) {
                ev3.createFile(NDEF_FILE_NO,
                    DESFireEV3File.StdEV3DataFileSettings(
                        IDESFireEV1.CommunicationType.Plain,
                        0x00.toByte(),  // commMode
                        0xEE.toByte(),  // RW=free(E), Change=free(E)
                        0xEE.toByte(),  // Read=free(E), Write=free(E)
                        0x00.toByte(),  // RFU
                        fileSize))
                uiLog("✓ Archivo creado ($fileSize bytes)")
            } else { uiLog("ℹ️ Archivo ya existe") }

            uiLog("Escribiendo NDEF: $urlTemplate")
            ev3.writeNDEF(ndefMsg)
            uiLog("✓ NDEF escrito")

            // 6. Activar SDM
            uiLog("[6/6] Activando SDM...")
            enableSdm(ev3, urlTemplate, ndefBytes, fileSize)

            uiLog("\n✅ COMPLETADO — Tag listo con NDEF + SDM")
            uiSetStatus("✅ Escritura exitosa!", "#4CAF50")

        } catch (e: Exception) {
            uiLog("\n❌ Error: ${e.message}")
            Log.e(TAG, "write", e)
            uiSetStatus("❌ Error", "#F44336")
        } finally { safeClose(ev3) }
    }

    private fun enableSdm(ev3: IDESFireEV3, urlTemplate: String, ndefBytes: ByteArray, fileSize: Int) {
        try {
            ev3.selectApplication(NDEF_APP_AID)
            ev3.authenticateEV2First(0, buildAesKeyData(DEFAULT_AES_KEY), null)

            val fileSettings = DESFireEV3File.StdEV3DataFileSettings(
                IDESFireEV1.CommunicationType.Plain,
                0x00.toByte(), 0x00.toByte(), 0x0E.toByte(), 0x00.toByte(),
                fileSize
            )
            fileSettings.setSDMEnabled(true)
            if (cbCtrMirror.isChecked) fileSettings.setSDMReadCounterEnabled(true)
            if (cbEncUid.isChecked)    fileSettings.setSDMEncryptFileDataEnabled(true)

            ev3.changeDESFireEV3FileSettings(NDEF_FILE_NO, fileSettings)
            uiLog("✓ SDM activado (changeDESFireEV3FileSettings)")

        } catch (e: Exception) {
            uiLog("⚠️ changeDESFireEV3FileSettings falló: ${e.message}")
            uiLog("Intentando APDU raw...")
            enableSdmRawApdu(ev3, urlTemplate, ndefBytes)
        }
    }

    private fun enableSdmRawApdu(ev3: IDESFireEV3, urlTemplate: String, ndefBytes: ByteArray) {
        try {
            ev3.selectApplication(NDEF_APP_AID)
            ev3.authenticateEV2First(0, buildAesKeyData(DEFAULT_AES_KEY), null)

            val eOff = computeParamOffset(urlTemplate, ndefBytes, "e=")
            val mOff = computeParamOffset(urlTemplate, ndefBytes, "m=")
            var sdmOpts = 0x01
            if (cbUidMirror.isChecked) sdmOpts = sdmOpts or 0x80
            if (cbCtrMirror.isChecked) sdmOpts = sdmOpts or 0x40
            if (cbEncUid.isChecked)    sdmOpts = sdmOpts or 0x10

            val pl = mutableListOf<Byte>().apply {
                add(NDEF_FILE_NO.toByte())
                add(0x40.toByte()); add(0x00.toByte()); add(0xE0.toByte())
                add(sdmOpts.toByte())
                add(0xFF.toByte()); add(0xE0.toByte())
                if (cbEncUid.isChecked)  addAll(intTo3LE(eOff).toList())
                if (cbSdmMac.isChecked) { addAll(intTo3LE(mOff).toList()); addAll(intTo3LE(mOff).toList()) }
            }.toByteArray()

            val apdu = ByteArray(5 + pl.size).also {
                it[0]=0x90.toByte(); it[1]=0x5F.toByte(); it[2]=0x00; it[3]=0x00; it[4]=pl.size.toByte()
                pl.copyInto(it, 5)
            }
            uiLog("APDU: ${Utilities.dumpBytes(apdu)}")
            val resp = ev3.reader.transceive(apdu)
            uiLog("RESP: ${Utilities.dumpBytes(resp)}")
            val ok = resp.size >= 2 && (resp.last() == 0x00.toByte() || resp[resp.size-1] == 0x00.toByte())
            uiLog(if (ok) "✓ SDM activado (APDU raw)" else "⚠️ Respuesta inesperada")
        } catch (e: Exception) { uiLog("❌ APDU raw falló: ${e.message}") }
    }

    private fun readTag(ev3: IDESFireEV3) {
        try {
            ev3.reader.setTimeout(3000)
            uiLog("UID: ${Utilities.dumpBytes(ev3.uid)}")
            uiLog("Tipo: ${ev3.type.tagName}")
            uiLog("Memoria: ${ev3.totalMemory} bytes")
            try {
                ev3.selectApplication(NDEF_APP_AID)
                ev3.authenticateEV2First(0, buildAesKeyData(DEFAULT_AES_KEY), null)
                val ndef = ev3.readNDEF()
                if (ndef != null) {
                    val raw = ndef.toByteArray()
                    uiLog("NDEF: ${Utilities.dumpBytes(raw)}")
                    val uPos = findBytes(raw, byteArrayOf(0x55))
                    if (uPos >= 0 && uPos + 1 < raw.size) {
                        val pre = when (raw[uPos+1].toInt() and 0xFF) {
                            0x01->"http://www."; 0x02->"https://www."; 0x03->"http://"; 0x04->"https://"; else->""
                        }
                        uiLog("URL: $pre${String(raw.copyOfRange(uPos+2, raw.size), Charsets.US_ASCII).trimEnd('\u0000')}")
                    }
                } else { uiLog("Sin NDEF") }
            } catch (e: Exception) { uiLog("NDEF: ${e.message}") }
            uiSetStatus("✅ Lectura OK", "#4CAF50")
        } catch (e: Exception) { uiLog("❌ ${e.message}"); uiSetStatus("❌ Error lectura", "#F44336") }
        finally { safeClose(ev3) }
    }

    private fun readTagEV2(ev2: IDESFireEV2) {
        try { uiLog("UID: ${Utilities.dumpBytes(ev2.uid)}"); uiLog("⚠️ EV2 — SDM solo en EV3") }
        catch (e: Exception) { uiLog("Error: ${e.message}") }
        finally { safeClose(ev2) }
    }

    // helpers
    private fun buildSdmUrl(base: String): String {
        val picc = "0".repeat(ENC_PICC_LEN); val mac = "0".repeat(SDMMAC_LEN)
        return if (base.contains("e=") && base.contains("m=")) base
        else "${base}${if (base.contains("?")) "&" else "?"}e=${picc}&m=${mac}"
    }

    private fun buildNdefMessage(url: String) = NdefMessageWrapper(
        NdefRecordWrapper(NdefRecordWrapper.TNF_ABSOLUTE_URI,
            url.toByteArray(Charsets.US_ASCII), ByteArray(0), ByteArray(0)))

    private fun computeParamOffset(url: String, ndefBytes: ByteArray, param: String): Int {
        val valuePos = url.indexOf(param).let { if (it < 0) return 20 else it + param.length }
        val uPos = findBytes(ndefBytes, byteArrayOf(0x55))
        if (uPos < 0) return 2 + 8 + valuePos
        val prefixLen = when (ndefBytes[uPos+1].toInt() and 0xFF) {
            0x01->11; 0x02->12; 0x03->7; 0x04->8; else->0 }
        return 2 + uPos + 2 + (valuePos - prefixLen)
    }

    private fun buildAesKeyData(bytes: ByteArray) = KeyData().also { it.key = SecretKeySpec(bytes, "AES") as Key }
    private fun buildDesKeyData(bytes: ByteArray) = KeyData().also { it.key = SecretKeySpec(bytes, "DESede") as Key }
    private fun byteArrayToInt(b: ByteArray) = b.foldIndexed(0) { i, acc, byte -> acc or ((byte.toInt() and 0xFF) shl (8*i)) }
    private fun intTo3LE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte())
    private fun findBytes(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size-n.size) { for (j in n.indices) if (h[i+j]!=n[j]) continue@outer; return i }; return -1
    }
    private fun safeClose(card: Any) = try { card.javaClass.getMethod("getReader").invoke(card)?.let { r -> r.javaClass.getMethod("close").invoke(r) } } catch (e: Exception) {}
    private fun log(msg: String) = mainHandler.post { tvLog.append("$msg\n"); scrollLog.post { scrollLog.fullScroll(android.view.View.FOCUS_DOWN) } }
    private fun uiLog(msg: String) = log(msg)
    private fun setStatus(msg: String, color: String) = mainHandler.post { tvStatus.text = msg; statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color)) }
    private fun uiSetStatus(msg: String, color: String) = setStatus(msg, color)
    private fun toast(msg: String) = mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun showDialog(t: String, m: String) = AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show()
}
