package com.nxp.ntag424tool

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nxp.ntag424tool.databinding.FragmentKeysBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeysFragment : Fragment() {

    private var _binding: FragmentKeysBinding? = null
    private val binding get() = _binding!!

    private var manager: Ntag424Manager? = null
    private var keyStore: KeyStore? = null

    // Shorthand para los 5 campos de clave
    private val keyFields: List<EditText> get() = with(binding) {
        listOf(etKey0, etKey1, etKey2, etKey3, etKey4)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        keyStore = (activity as? MainActivity)?.getKeyStore()
        loadKeysToUi()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: Ntag424Manager) {
        manager = mgr
    }

    // ─── UI setup ─────────────────────────────────────────────────────────

    private fun loadKeysToUi() {
        val ks = keyStore ?: return
        keyFields.forEachIndexed { i, field -> field.setText(ks.getHex(i)) }
    }

    private fun setupListeners() = with(binding) {
        // Botones de clave aleatoria
        listOf(btnGenKey0, btnGenKey1, btnGenKey2, btnGenKey3, btnGenKey4)
            .forEachIndexed { i, btn ->
                btn.setOnClickListener {
                    keyFields[i].setText(KeyStore.generateRandom().toHexString())
                }
            }

        btnSaveKeys.setOnClickListener        { saveKeysLocally()  }
        btnChangeKey.setOnClickListener       { onChangeKeyClicked() }
        btnEnableRandomId.setOnClickListener  { onEnableRandomIdClicked() }
        btnGetUid.setOnClickListener          { onGetUidClicked() }
    }

    // ─── Guardar claves localmente ────────────────────────────────────────

    private fun saveKeysLocally() {
        val ks = keyStore ?: return
        val allValid = keyFields.mapIndexed { i, field ->
            val hex = field.text.toString().trim()
            if (ks.setFromHex(i, hex)) {
                field.error = null; true
            } else {
                field.error = "Debe ser 32 chars hex"; false
            }
        }.all { it }

        if (allValid) {
            ks.save()
            showStatus("✅ Claves guardadas localmente", true)
        }
    }

    // ─── Cambiar clave en tarjeta ─────────────────────────────────────────

    private fun onChangeKeyClicked() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val newHex = binding.etNewKey.text.toString().trim()

        if (!newHex.isValidHexKey()) {
            showStatus("La nueva clave debe tener 32 caracteres hex", false); return
        }

        val keyToChange = binding.spinnerKeyToChange.selectedItemPosition
        val authKey     = binding.spinnerAuthForChange.selectedItemPosition
        val version     = binding.etKeyVersion.text.toString().toIntOrNull() ?: 1

        if (keyToChange == 0) {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Cambiar Clave Master")
                .setMessage(
                    "Cambiar la Clave 0 es peligroso.\n" +
                    "Si la pierdes no podrás autenticarte.\n\n¿Continuar?"
                )
                .setPositiveButton("Continuar") { _, _ ->
                    doChangeKey(mgr, keyToChange, newHex, version, authKey)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            doChangeKey(mgr, keyToChange, newHex, version, authKey)
        }
    }

    private fun doChangeKey(
        mgr: Ntag424Manager,
        keyToChange: Int,
        newHex: String,
        version: Int,
        authKey: Int
    ) {
        showStatus("Cambiando clave $keyToChange…", true)
        val newBytes = newHex.hexToByteArray()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                mgr.changeKey(keyToChange, newBytes, version, authKey)
            }
            showStatus(result.message, result.success)
            if (result.success && keyToChange < keyFields.size) {
                keyFields[keyToChange].setText(newHex)
            }
        }
    }

    // ─── Random ID ────────────────────────────────────────────────────────

    private fun onEnableRandomIdClicked() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)

        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ OPERACIÓN IRREVERSIBLE")
            .setMessage(
                "Habilitar Random ID es PERMANENTE.\n\n" +
                "• El tag responderá con IDs de 4 bytes aleatorios\n" +
                "• ATQA cambia de 0x0344 → 0x0304\n" +
                "• No se puede deshacer\n\n" +
                "¿Continuar?"
            )
            .setPositiveButton("SÍ, ACTIVAR") { _, _ ->
                showStatus("Activando Random ID…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { mgr.enableRandomId(0) }
                    showStatus(result.message, result.success)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─── Get real UID ─────────────────────────────────────────────────────

    private fun onGetUidClicked() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.getRealUid(0) }
            showStatus(result.message, result.success)
        }
    }

    // ─── Status helper ────────────────────────────────────────────────────

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvKeysStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
