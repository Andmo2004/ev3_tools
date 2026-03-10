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
    private var manager: DesfireManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

    fun onTagConnected(mgr: DesfireManager) {
        manager = mgr
        readInfo()
    }

    private fun readInfo() {
        val mgr = manager ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { mgr.readCardInfo() }
            if (_binding == null) return@launch
            if (result.success) {
                val info = result.data ?: return@launch
                with(binding) {
                    tvUid.text      = info.uid
                    tvCardType.text = info.cardType
                    tvVendor.text   = info.vendor
                    tvHwMajor.text  = info.hwMajor
                    tvHwMinor.text  = info.hwMinor
                    tvSwMajor.text  = info.swMajor
                    tvSwMinor.text  = info.swMinor
                    tvStorage.text  = info.storage
                    tvBatch.text    = info.batchNo
                    tvFreeMemory.text = info.freeMemory
                }
            } else {
                toast(result.message)
            }
        }
    }
}
