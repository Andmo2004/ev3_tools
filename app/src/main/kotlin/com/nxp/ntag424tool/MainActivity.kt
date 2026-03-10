package com.nxp.ntag424tool

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.lifecycleScope
import com.nxp.nfclib.CardType
import com.nxp.nfclib.NxpNfcLib
import com.nxp.nfclib.desfire.DESFireFactory
import com.nxp.nfclib.desfire.INTAG424DNA
import com.nxp.ntag424tool.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyStore: KeyStore

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var nxpLib: NxpNfcLib? = null

    private val infoFragment  = InfoFragment()
    private val ndefFragment  = NdefFragment()
    private val sdmFragment   = SdmFragment()
    private val keysFragment  = KeysFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keyStore = KeyStore(this)
        initNfc()
        setupTabs()
    }

    private fun initNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> {
                binding.tvNfcStatus.text = "NFC no disponible en este dispositivo"
                toast("NFC no disponible", long = true)
            }
            !nfcAdapter!!.isEnabled -> {
                binding.tvNfcStatus.text = "NFC deshabilitado — actívalo en Ajustes"
            }
            else -> {
                binding.tvNfcStatus.text = "Acerca una tarjeta NTAG 424 DNA…"
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )

        runCatching {
            nxpLib = NxpNfcLib.getInstance()
            nxpLib!!.registerActivity(this, BuildConfig.TAPLINX_KEY)
        }
    }

    private fun setupTabs() {
        val adapter = TabsPagerAdapter(supportFragmentManager).apply {
            addFragment(infoFragment,  "INFO")
            addFragment(ndefFragment,  "NDEF")
            addFragment(sdmFragment,   "SDM")
            addFragment(keysFragment,  "CLAVES")
        }
        binding.viewPager.adapter = adapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isNfcAction = intent.action in listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (isNfcAction) {
            intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)?.let { processTag(it) }
        }
    }

    private fun processTag(androidTag: Tag) {
        binding.tvNfcStatus.text = "Procesando tarjeta…"

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val ntag: INTAG424DNA? = nxpLib?.let { lib ->
                    val type = lib.getCardType(androidTag)
                    if (type == CardType.NTAG424DNATagTamper)
                        DESFireFactory.getInstance().getNTAG424DNATT(lib.customModules) as INTAG424DNA
                    else
                        DESFireFactory.getInstance().getNTAG424DNA(lib.customModules)
                } ?: DESFireFactory.getInstance().getNTAG424DNA(null)

                if (ntag == null) {
                    withContext(Dispatchers.Main) {
                        binding.tvNfcStatus.text = "Tarjeta no compatible (se necesita NTAG 424 DNA)"
                    }
                    return@launch
                }

                ntag.reader.connect()
                val typeName = ntag.type?.tagName ?: "NTAG 424 DNA"
                val manager = Ntag424Manager(ntag, keyStore)

                withContext(Dispatchers.Main) {
                    binding.tvNfcStatus.text = "✅ $typeName detectada"
                    binding.tvTagType.text = typeName
                    Toast.makeText(this@MainActivity, "Conectado: $typeName", Toast.LENGTH_SHORT).show()
                    notifyFragments(manager)
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    binding.tvNfcStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun notifyFragments(manager: Ntag424Manager) {
        infoFragment.onTagConnected(manager)
        ndefFragment.onTagConnected(manager)
        sdmFragment.onTagConnected(manager)
        keysFragment.onTagConnected(manager)
    }

    fun getKeyStore(): KeyStore = keyStore

    private class TabsPagerAdapter(fm: FragmentManager) :
        FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private val fragments = mutableListOf<Fragment>()
        private val titles    = mutableListOf<String>()

        fun addFragment(f: Fragment, title: String) { fragments += f; titles += title }
        override fun getItem(position: Int) = fragments[position]
        override fun getCount() = fragments.size
        override fun getPageTitle(position: Int) = titles[position]
    }
}