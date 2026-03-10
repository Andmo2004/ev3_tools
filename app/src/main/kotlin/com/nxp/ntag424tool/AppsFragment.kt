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
import com.nxp.ntag424tool.databinding.FragmentAppsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private var manager: DesfireManager? = null
    private var selectedAid: ByteArray? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnListApps.setOnClickListener    { listApps() }
        binding.btnCreateApp.setOnClickListener   { showCreateDialog() }
        binding.btnDeleteApp.setOnClickListener   { deleteSelected() }
        binding.btnBrowseFiles.setOnClickListener { browseFiles() }
        binding.btnFormatPicc.setOnClickListener  { confirmFormat() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: DesfireManager) {
        manager = mgr
        listApps()
    }

    private fun listApps() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        showStatus("Listando aplicaciones…", true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.listApplications() }
            if (_binding == null) return@launch
            if (result.success) {
                val apps = result.data ?: emptyList()
                binding.tvAppsList.text = if (apps.isEmpty()) {
                    "No hay aplicaciones (tarjeta vacía)"
                } else {
                    apps.joinToString("\n") { "• AID: ${it.aidHex}" }
                }
                showStatus("${apps.size} aplicaciones encontradas", true)
            } else {
                showStatus(result.message, false)
            }
        }
    }

    private fun showCreateDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_app, null)
        AlertDialog.Builder(requireContext())
            .setTitle("Crear Aplicación")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val aidHex  = dialogView.findViewById<EditText>(R.id.et_aid).text.toString().trim()
                val numKeys = dialogView.findViewById<EditText>(R.id.et_num_keys).text.toString().toIntOrNull() ?: 5
                val authKey = dialogView.findViewById<EditText>(R.id.et_auth_key_app).text.toString().toIntOrNull() ?: 0
                if (aidHex.length != 6) { showStatus("AID debe ser 3 bytes (6 hex chars)", false); return@setPositiveButton }
                val aid = runCatching { aidHex.hexToByteArray() }.getOrElse { showStatus("AID hex inválido", false); return@setPositiveButton }
                val mgr = manager ?: return@setPositiveButton
                showStatus("Creando aplicación…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.createApplication(NewAppConfig(aid, numKeys), authKey) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                    if (r.success) listApps()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSelected() {
        val aid = selectedAid ?: run { showStatus("Selecciona una aplicación primero (escribe AID abajo)", false); return }
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Borrar Aplicación")
            .setMessage("¿Borrar AID ${aid.toHexString()}? Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                showStatus("Borrando…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.deleteApplication(aid, 0) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                    if (r.success) { selectedAid = null; listApps() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun browseFiles() {
        val aidHex = binding.etSelectedAid.text.toString().trim()
        if (aidHex.length != 6) { showStatus("Introduce un AID de 3 bytes (6 chars hex)", false); return }
        val aid = runCatching { aidHex.hexToByteArray() }.getOrElse { showStatus("AID hex inválido", false); return }
        selectedAid = aid
        val act = activity as? MainActivity ?: return
        (act.supportFragmentManager.fragments.firstOrNull { it is FilesFragment } as? FilesFragment)
            ?.loadFilesForAid(aid)
        act.navigateToTab(2)
    }

    private fun confirmFormat() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ FORMATEAR TARJETA")
            .setMessage("Esto borrará TODAS las aplicaciones y datos.\n¿Continuar?")
            .setPositiveButton("FORMATEAR") { _, _ ->
                showStatus("Formateando…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.formatPICC(0) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                    if (r.success) listApps()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvAppsStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
