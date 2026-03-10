package com.nxp.ntag424tool

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.lifecycleScope
import com.nxp.nfclib.CardType
import com.nxp.nfclib.NxpNfcLib
import com.nxp.nfclib.desfire.DESFireFactory
import com.nxp.nfclib.desfire.IDESFireEV3
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
    private val appsFragment  = AppsFragment()
    private val filesFragment = FilesFragment()
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
            nfcAdapter == null      -> binding.tvNfcStatus.text = "NFC no disponible"
            !nfcAdapter!!.isEnabled -> binding.tvNfcStatus.text = "NFC deshabilitado — actívalo en Ajustes"
            else                    -> binding.tvNfcStatus.text = "Acerca una tarjeta DESFire EV3…"
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
            nxpLib!!.registerActivity(this, BuildConfig.TAPLINX_KEY, packageName)
        }.onFailure { e ->
            Log.e("TapLinX", "License error: ${e.message}")
            binding.tvNfcStatus.text = "License error: ${e.javaClass.simpleName}"
        }
    }

    private fun setupTabs() {
        val adapter = TabsPagerAdapter(supportFragmentManager).apply {
            addFragment(infoFragment,  "INFO")
            addFragment(appsFragment,  "APPS")
            addFragment(filesFragment, "FILES")
            addFragment(keysFragment,  "CLAVES")
        }
        binding.viewPager.adapter = adapter
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }

    override fun onResume() {
        super.onResume()
        nxpLib?.startForeGroundDispatch()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nxpLib?.stopForeGroundDispatch()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isNfc = intent.action in listOf(
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED
        )
        if (isNfc) processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        binding.tvNfcStatus.text = "Procesando tarjeta…"

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val lib = nxpLib ?: run {
                    withContext(Dispatchers.Main) {
                        binding.tvNfcStatus.text = "Error: librería NXP no inicializada"
                    }
                    return@launch
                }

                val cardType = lib.getCardType(intent)
                Log.d("TapLinX", "CardType detected: $cardType")

                val ev3: IDESFireEV3 = DESFireFactory.getInstance()
                    .getDESFireEV3(lib.customModules)

                ev3.reader.connect()
                ev3.reader.setTimeout(5000L)

                val typeName = ev3.type?.tagName ?: "DESFire EV3"
                val manager  = DesfireManager(ev3, keyStore)

                withContext(Dispatchers.Main) {
                    binding.tvNfcStatus.text  = "✅ $typeName detectada"
                    binding.tvTagType.text    = typeName
                    Toast.makeText(this@MainActivity, "Conectado: $typeName", Toast.LENGTH_SHORT).show()
                    notifyFragments(manager)
                }
            }.onFailure { e ->
                Log.e("TapLinX", "processIntent error: ${e.javaClass.name}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvNfcStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun notifyFragments(manager: DesfireManager) {
        infoFragment.onTagConnected(manager)
        appsFragment.onTagConnected(manager)
        filesFragment.onTagConnected(manager)
        keysFragment.onTagConnected(manager)
    }

    fun getKeyStore(): KeyStore = keyStore

    fun navigateToTab(index: Int) {
        binding.viewPager.currentItem = index
    }

    private class TabsPagerAdapter(fm: FragmentManager) :
        FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private val fragments = mutableListOf<Fragment>()
        private val titles    = mutableListOf<String>()

        fun addFragment(f: Fragment, title: String) { fragments += f; titles += title }
        override fun getItem(position: Int)  = fragments[position]
        override fun getCount()              = fragments.size
        override fun getPageTitle(position: Int) = titles[position]
    }
}
