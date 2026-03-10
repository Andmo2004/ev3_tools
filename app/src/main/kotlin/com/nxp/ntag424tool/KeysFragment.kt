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
    private var manager: DesfireManager? = null
    private var keyStore: KeyStore? = null

    private val keyFields: List<EditText> get() = with(binding) {
        listOf(etKey0, etKey1, etKey2, etKey3, etKey4)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

    fun onTagConnected(mgr: DesfireManager) {
        manager = mgr
    }

    private fun loadKeysToUi() {
        val ks = keyStore ?: return
        keyFields.forEachIndexed { i, field -> field.setText(ks.getHex(i)) }
    }

    private fun setupListeners() = with(binding) {
        listOf(btnGenKey0, btnGenKey1, btnGenKey2, btnGenKey3, btnGenKey4)
            .forEachIndexed { i, btn ->
                btn.setOnClickListener { keyFields[i].setText(KeyStore.generateRandom().toHexString()) }
            }
        btnSaveKeys.setOnClickListener  { saveKeysLocally() }
        btnChangeKey.setOnClickListener { onChangeKeyClicked() }
    }

    private fun saveKeysLocally() {
        val ks = keyStore ?: return
        val allValid = keyFields.mapIndexed { i, field ->
            val hex = field.text.toString().trim()
            if (ks.setFromHex(i, hex)) { field.error = null; true }
            else { field.error = "32 chars hex requeridos"; false }
        }.all { it }
        if (allValid) { ks.save(); showStatus("✅ Claves guardadas", true) }
    }

    private fun onChangeKeyClicked() {
        val mgr    = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val aidHex = binding.etAidForChange.text.toString().trim()
        val newHex = binding.etNewKey.text.toString().trim()

        if (aidHex.length != 6)       { showStatus("AID debe ser 6 chars hex", false); return }
        if (!newHex.isValidHexKey())   { showStatus("Nueva clave debe ser 32 chars hex", false); return }

        val aid         = runCatching { aidHex.hexToByteArray() }.getOrElse { showStatus("AID inválido", false); return }
        val keyToChange = binding.spinnerKeyToChange.selectedItemPosition
        val authKey     = binding.spinnerAuthForChange.selectedItemPosition
        val version     = binding.etKeyVersion.text.toString().toIntOrNull()?.toByte() ?: 1

        if (keyToChange == 0) {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Cambiar Clave Master")
                .setMessage("Si la pierdes no podrás autenticarte.\n¿Continuar?")
                .setPositiveButton("Continuar") { _, _ -> doChangeKey(mgr, aid, keyToChange, newHex, version, authKey) }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            doChangeKey(mgr, aid, keyToChange, newHex, version, authKey)
        }
    }

    private fun doChangeKey(mgr: DesfireManager, aid: ByteArray, keyToChange: Int, newHex: String, version: Byte, authKey: Int) {
        showStatus("Cambiando clave $keyToChange…", true)
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                mgr.changeKey(aid, keyToChange, newHex.hexToByteArray(), version, authKey)
            }
            if (_binding == null) return@launch
            showStatus(r.message, r.success)
            if (r.success) keyFields.getOrNull(keyToChange)?.setText(newHex)
        }
    }

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvKeysStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
