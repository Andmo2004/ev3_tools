package com.nxp.ntag424tool

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nxp.ntag424tool.databinding.FragmentSdmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SdmFragment : Fragment() {

    private var _binding: FragmentSdmBinding? = null
    private val binding get() = _binding!!

    private var manager: Ntag424Manager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSdmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        applyDefaults()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: Ntag424Manager) {
        manager = mgr
    }

    // ─── Configuración inicial (valores del ejemplo AN12196) ──────────────

    private fun applyDefaults() = with(binding) {
        spinnerSdmMetaRead.setSelection(2)  // Clave 2
        spinnerSdmFileRead.setSelection(1)  // Clave 1
        spinnerSdmCtrRet.setSelection(1)    // Clave 1
        spinnerReadKey.setSelection(5)      // Libre (E)
        spinnerRwKey.setSelection(0)
        spinnerChangeKey.setSelection(0)
        spinnerWriteKey.setSelection(0)

        etUidOffset.setText("32")
        etCtrOffset.setText("46")
        etPiccEncOffset.setText("32")
        etEncOffset.setText("68")
        etEncLength.setText("32")
        etMacInputOffset.setText("67")
        etMacOffset.setText("67")

        cbEncPiccData.isChecked   = true
        cbAsciiEncoding.isChecked = true
        cbCounterMirror.isChecked = true

        // Deshabilitar mirrors individuales si encPiccData está activo
        cbUidMirror.isEnabled     = false
        cbCounterMirror.isEnabled = false
    }

    // ─── Listeners ────────────────────────────────────────────────────────

    private fun setupListeners() = with(binding) {
        // Mostrar/ocultar campo límite de contador
        cbCtrLimit.setOnCheckedChangeListener { _, checked ->
            layoutCtrLimit.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // EncPiccData implica que UID/CTR mirror no son configurables por separado
        cbEncPiccData.setOnCheckedChangeListener { _, checked ->
            cbUidMirror.isChecked     = false
            cbUidMirror.isEnabled     = !checked
            cbCounterMirror.isChecked = false
            cbCounterMirror.isEnabled = !checked
        }

        btnApplySdm.setOnClickListener { applyConfig() }
    }

    // ─── Leer config desde UI y aplicar ──────────────────────────────────

    private fun applyConfig() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)

        val config = buildConfigFromUi()
        val authKey = binding.spinnerAuthKeySdm.selectedItemPosition

        showStatus("Aplicando configuración SDM…", true)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.applySdmConfig(config, authKey) }
            showStatus(result.message, result.success)
        }
    }

    private fun buildConfigFromUi(): SdmConfig = with(binding) {
        SdmConfig(
            sdmEnabled   = switchSdmEnabled.isChecked,
            commMode     = when (spinnerCommMode.selectedItemPosition) {
                1 -> CommMode.MAC; 2 -> CommMode.FULL; else -> CommMode.PLAIN
            },

            uidMirror      = cbUidMirror.isChecked,
            counterMirror  = cbCounterMirror.isChecked,
            encPiccData    = cbEncPiccData.isChecked,
            encFileData    = cbEncFileData.isChecked,
            ttMirror       = cbTtMirror.isChecked,
            asciiEncoding  = cbAsciiEncoding.isChecked,
            ctrLimitEnabled = cbCtrLimit.isChecked,
            ctrLimit       = etCtrLimit.text.toString().trim()
                .ifEmpty { "FFFFFF" }
                .runCatching { toInt(16) }.getOrDefault(0xFFFFFF),

            sdmMetaReadKey = spinnerSdmMetaRead.selectedItemPosition.toKeyAccess(),
            sdmFileReadKey = spinnerSdmFileRead.selectedItemPosition.toKeyAccess(),
            sdmCtrRetKey   = spinnerSdmCtrRet.selectedItemPosition.toKeyAccess(),

            rwKey     = spinnerRwKey.selectedItemPosition.toKeyAccess(),
            changeKey = spinnerChangeKey.selectedItemPosition.toKeyAccess(),
            readKey   = spinnerReadKey.selectedItemPosition.toKeyAccess(),
            writeKey  = spinnerWriteKey.selectedItemPosition.toKeyAccess(),

            uidOffset      = etUidOffset.text.toString().toIntOrDefault(32),
            ctrOffset      = etCtrOffset.text.toString().toIntOrDefault(46),
            piccEncOffset  = etPiccEncOffset.text.toString().toIntOrDefault(32),
            encOffset      = etEncOffset.text.toString().toIntOrDefault(68),
            encLength      = etEncLength.text.toString().toIntOrDefault(32),
            macInputOffset = etMacInputOffset.text.toString().toIntOrDefault(67),
            macOffset      = etMacOffset.text.toString().toIntOrDefault(67)
        )
    }

    private fun String.toIntOrDefault(default: Int) =
        trim().toIntOrNull() ?: default

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvSdmStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
