package com.nxp.ntag424tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nxp.ntag424tool.databinding.FragmentInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

    private var manager: Ntag424Manager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRefresh.setOnClickListener {
            manager?.let { readInfo() } ?: toast("Acerca primero una tarjeta")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTagConnected(mgr: Ntag424Manager) {
        manager = mgr
        if (_binding != null) readInfo()
    }

    private fun readInfo() {
        val mgr = manager ?: return

        lifecycleScope.launch {
            if (_binding == null) return@launch   // ← guardia
            val result = withContext(Dispatchers.IO) { mgr.readCardInfo() }

            if (result.success) {
                val info = result.data ?: return@launch
                with(binding) {
                    tvUid.text        = info.uid
                    tvCardType.text   = info.cardType
                    tvVendor.text     = info.vendor
                    tvHwMajor.text    = info.hwMajor
                    tvHwMinor.text    = info.hwMinor
                    tvSwMajor.text    = info.swMajor
                    tvSwMinor.text    = info.swMinor
                    tvStorage.text    = info.storage
                    tvBatch.text      = info.batchNo
                    tvFileSettings.text = info.fileSettings.ifEmpty { "No disponible" }

                    if (info.isTagTamper) {
                        cardTagtamper.show()
                        tvTtPerm.text = info.ttPermStatus
                        tvTtCurr.text = info.ttCurrStatus
                    } else {
                        cardTagtamper.hide()
                    }
                }
            } else {
                toast(result.message)
            }
        }
    }
}
