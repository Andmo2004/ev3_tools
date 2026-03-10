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
import com.nxp.ntag424tool.databinding.FragmentFilesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private var manager: DesfireManager? = null
    private var currentAid: ByteArray? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnListFiles.setOnClickListener   { listFiles() }
        binding.btnReadFile.setOnClickListener    { readFile() }
        binding.btnWriteFile.setOnClickListener   { writeFile() }
        binding.btnCreateFile.setOnClickListener  { showCreateFileDialog() }
        binding.btnDeleteFile.setOnClickListener  { deleteFile() }
        binding.btnWriteNdef.setOnClickListener   { showNdefDialog() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: DesfireManager) {
        manager = mgr
    }

    fun loadFilesForAid(aid: ByteArray) {
        currentAid = aid
        if (_binding != null) {
            binding.etAidFiles.setText(aid.toHexString())
            listFiles()
        }
    }

    private fun getAid(): ByteArray? {
        val hex = binding.etAidFiles.text.toString().trim()
        if (hex.length != 6) { showStatus("AID debe ser 6 chars hex", false); return null }
        return runCatching { hex.hexToByteArray() }.getOrElse { showStatus("AID inválido", false); null }
    }

    private fun listFiles() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val aid = getAid() ?: return
        val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
        currentAid = aid
        showStatus("Listando archivos…", true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.listFiles(aid, authKey) }
            if (_binding == null) return@launch
            if (result.success) {
                val files = result.data ?: emptyList()
                binding.tvFilesList.text = if (files.isEmpty()) {
                    "No hay archivos en esta aplicación"
                } else {
                    files.joinToString("\n") { f ->
                        "• Archivo #${f.fileNo} | ${f.type} | ${f.commMode} | ${f.size} bytes"
                    }
                }
                showStatus("${files.size} archivos", true)
            } else {
                showStatus(result.message, false)
            }
        }
    }

    private fun readFile() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val aid    = getAid() ?: return
        val fileNo = binding.etFileNo.text.toString().toIntOrNull() ?: 0
        val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
        showStatus("Leyendo archivo $fileNo…", true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.readFile(aid, fileNo, authKey) }
            if (_binding == null) return@launch
            if (result.success) {
                binding.etFileData.setText(result.data ?: "")
                showStatus("Archivo leído", true)
            } else {
                showStatus(result.message, false)
            }
        }
    }

    private fun writeFile() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val aid    = getAid() ?: return
        val fileNo = binding.etFileNo.text.toString().toIntOrNull() ?: 0
        val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
        val dataStr = binding.etFileData.text.toString().trim()
        val data = if (dataStr.matches(Regex("[0-9a-fA-F]+"))) {
            runCatching { dataStr.hexToByteArray() }.getOrElse { dataStr.toByteArray() }
        } else {
            dataStr.toByteArray()
        }
        showStatus("Escribiendo ${data.size} bytes…", true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.writeFile(aid, fileNo, data, authKey) }
            if (_binding == null) return@launch
            showStatus(result.message, result.success)
        }
    }

    private fun showCreateFileDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_file, null)
        AlertDialog.Builder(requireContext())
            .setTitle("Crear Archivo Standard")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val aid    = getAid() ?: return@setPositiveButton
                val fileNo = dialogView.findViewById<EditText>(R.id.et_file_no).text.toString().toIntOrNull() ?: 0
                val size   = dialogView.findViewById<EditText>(R.id.et_file_size).text.toString().toIntOrNull() ?: 256
                val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
                val mgr = manager ?: return@setPositiveButton
                val config = NewFileConfig(fileNo = fileNo, size = size, readKey = 0x0E, writeKey = 0x0E, rwKey = 0x0E, changeKey = 0x00)
                showStatus("Creando archivo…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.createStandardFile(aid, config, authKey) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                    if (r.success) listFiles()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteFile() {
        val mgr = manager ?: return showStatus("Acerca una tarjeta primero", false)
        val aid    = getAid() ?: return
        val fileNo = binding.etFileNo.text.toString().toIntOrNull() ?: 0
        val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Borrar Archivo")
            .setMessage("¿Borrar archivo #$fileNo?")
            .setPositiveButton("Borrar") { _, _ ->
                showStatus("Borrando archivo $fileNo…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.deleteFile(aid, fileNo, authKey) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                    if (r.success) listFiles()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNdefDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_ndef_url, null)
        AlertDialog.Builder(requireContext())
            .setTitle("Escribir URL NDEF")
            .setView(dialogView)
            .setPositiveButton("Escribir") { _, _ ->
                val url = dialogView.findViewById<EditText>(R.id.et_ndef_url).text.toString().trim()
                if (url.isEmpty()) { showStatus("Introduce una URL", false); return@setPositiveButton }
                val mgr = manager ?: return@setPositiveButton
                val authKey = binding.spinnerAuthKeyFiles.selectedItemPosition
                showStatus("Escribiendo NDEF…", true)
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { mgr.setupNdefApplication(url, authKey) }
                    if (_binding == null) return@launch
                    showStatus(r.message, r.success)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStatus(msg: String, success: Boolean) {
        with(binding.tvFilesStatus) {
            show()
            text = msg
            setBackgroundColor(if (success) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
            setTextColor(if (success) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
