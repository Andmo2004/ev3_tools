package com.nxp.ntag424tool

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nxp.ntag424tool.databinding.FragmentNdefBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NdefFragment : Fragment() {

    private var _binding: FragmentNdefBinding? = null
    private val binding get() = _binding!!

    private var manager: Ntag424Manager? = null

    private val defaultBase = "https://verify.mysite.com/ntag?"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNdefBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            btnTemplatePicc.setOnClickListener {
                etNdefContent.setText(NdefTemplates.piccCmac(defaultBase))
            }
            btnTemplateEnc.setOnClickListener {
                etNdefContent.setText(NdefTemplates.encPiccCmac(defaultBase))
            }
            btnTemplateFull.setOnClickListener {
                etNdefContent.setText(NdefTemplates.fullEnc(defaultBase))
            }
            btnWriteNdef.setOnClickListener { onWriteClicked() }
            btnReadNdef.setOnClickListener  { onReadClicked()  }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: Ntag424Manager) {
        manager = mgr
        // Solo lanzar si la vista está activa
        if (_binding != null) onReadClicked()
    }

    private fun onWriteClicked() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val content = binding.etNdefContent.text.toString().trim()
        if (content.isEmpty()) return showStatus("Introduce el contenido NDEF", false)

        val type = when {
            binding.rbUrl.isChecked  -> NdefType.URL
            binding.rbText.isChecked -> NdefType.TEXT
            else                     -> NdefType.RAW_HEX
        }
        val authKey = binding.spinnerAuthKeyNdef.selectedItemPosition

        showStatus("Escribiendo NDEF…", true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.writeNdef(content, type, authKey) }
            if (_binding == null) return@launch
            showStatus(result.message, result.success)
            if (result.success) onReadClicked()
        }
    }

    private fun onReadClicked() {
        val mgr = manager ?: return
        val authKey = binding.spinnerAuthKeyNdef.selectedItemPosition

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.readNdef(authKey) }
            if (_binding == null) return@launch
            if (result.success) {
                binding.tvCurrentNdef.text = result.data ?: "(vacío)"
            } else {
                binding.tvCurrentNdef.text = "Error: ${result.message}"
            }
        }
    }

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvNdefStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}