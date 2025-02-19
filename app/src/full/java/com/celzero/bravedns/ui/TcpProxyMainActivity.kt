package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityTcpProxyBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.WireGuardManager
import com.celzero.bravedns.service.WireGuardManager.SEC_WARP_ID
import com.celzero.bravedns.service.WireGuardManager.WARP_ID
import com.celzero.bravedns.service.WireGuardManager.isWarpWorking
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class TcpProxyMainActivity : AppCompatActivity(R.layout.activity_tcp_proxy) {
    private val b by viewBinding(ActivityTcpProxyBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        displayTcpProxyStatus()
        observeTcpProxyApps()
        displayWarpStatus()
    }

    private fun displayTcpProxyStatus() {
        val tcpProxies = TcpProxyHelper.getActiveTcpProxy()
        if (tcpProxies == null || !tcpProxies.isActive) {
            b.tcpProxyStatus.text = "Not active" // getString(R.string.tcp_proxy_description)
            showTcpErrorLayout()
            b.tcpProxySwitch.isChecked = false
            return
        }

        Log.i(
            LoggerConstants.LOG_TAG_UI,
            "displayTcpProxyUi: ${tcpProxies.name}, ${tcpProxies.url}"
        )
        b.tcpProxySwitch.isChecked = true
        b.tcpProxyStatus.text = "Active" // getString(R.string.tcp_proxy_description_active)
    }

    private fun showTcpErrorLayout() {
        b.tcpErrorRl.visibility = View.VISIBLE
        b.tcpErrorTv.text = "Something went wrong" // getString(R.string.tcp_proxy_error_desc)
    }

    private fun observeTcpProxyApps() {
        mappingViewModel.getAppCountById(ProxyManager.ID_TCP_BASE).observe(this) { apps ->
            if (apps == null || apps == 0) {
                b.tcpProxyAddApps.text =
                    "Add / Remove apps" // getString(R.string.tcp_proxy_add_apps)
                return@observe
            }
            b.tcpProxyAddApps.text =
                "Add / Remove apps ($apps added)" // getString(R.string.tcp_proxy_add_apps_count,
            // apps.size)
        }
    }

    private fun displayWarpStatus() {
        val config = WireGuardManager.getWarpConfig()
        if (config == null) {
            b.warpStatus.text = "Fetch from server" // getString(R.string.tcp_proxy_description)
            b.warpSwitch.isChecked = false
            return
        }
        if (WireGuardManager.getConfigFilesById(WARP_ID)?.isActive == true) {
            b.warpStatus.text = "Active" // getString(R.string.tcp_proxy_description_active)
            b.warpSwitch.isChecked = true
        } else {
            b.warpStatus.text = "Not active" // getString(R.string.tcp_proxy_description)
            b.warpSwitch.isChecked = false
        }
    }

    private fun setupClickListeners() {
        b.tcpProxySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && WireGuardManager.isConfigActive(ProxyManager.ID_WG_BASE + WARP_ID)) {
                b.tcpProxySwitch.isChecked = false
                Utilities.showToastUiCentered(
                    this,
                    "Warp is active. Please disable it first.",
                    Toast.LENGTH_SHORT
                )
                return@setOnCheckedChangeListener
            }

            val apps = ProxyManager.isAnyAppSelected(ProxyManager.ID_TCP_BASE)
            if (!apps) {
                Utilities.showToastUiCentered(
                    this,
                    "Please add at least one app to enable Rethink Proxy.",
                    Toast.LENGTH_SHORT
                )
                b.warpSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!checked) {
                io { TcpProxyHelper.disable() }
                b.tcpProxyDesc.text = getString(R.string.settings_https_desc)
                return@setOnCheckedChangeListener
            }

            if (appConfig.getBraveMode().isDnsMode()) {
                b.tcpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableTcpProxy()) {
                val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.settings_https_disabled_error, s),
                    Toast.LENGTH_SHORT
                )
                b.tcpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            enableTcpProxy()
        }

        b.enableUdpRelay.setOnCheckedChangeListener { _, b ->
            if (b) {
                io {
                    val alreadyDownloaded = WireGuardManager.isSecWarpAvailable()
                    if (alreadyDownloaded) {
                        val cf =
                            WireGuardManager.getConfigFilesById(SEC_WARP_ID)
                                ?: return@io
                        WireGuardManager.enableConfig(cf)
                    } else {
                        createConfigOrShowErrorLayout()
                    }
                }
            } else {

                io {
                    val cf =
                        WireGuardManager.getConfigFilesById(SEC_WARP_ID)
                            ?: return@io
                    WireGuardManager.disableConfig(cf)
                }
            }
        }

        b.warpSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && TcpProxyHelper.isTcpProxyEnabled()) {
                Utilities.showToastUiCentered(
                    this,
                    "Please disable TCP Proxy to enable WARP",
                    Toast.LENGTH_SHORT
                )
                b.warpSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            val apps = ProxyManager.isAnyAppSelected(ProxyManager.ID_WG_BASE + WARP_ID)
            if (!apps) {
                Utilities.showToastUiCentered(
                    this,
                    "Please add at least one app to enable WARP",
                    Toast.LENGTH_SHORT
                )
                b.warpSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            val configFiles =
                WireGuardManager.getConfigFilesById(WARP_ID) ?: return@setOnCheckedChangeListener
            if (checked) {
                WireGuardManager.enableConfig(configFiles)
                b.warpStatus.text = "Active" // getString(R.string.tcp_proxy_description_active)
            } else {
                WireGuardManager.disableConfig(configFiles)
                b.warpStatus.text = "Not active" // getString(R.string.tcp_proxy_description)
            }
        }

        b.tcpProxyAddApps.setOnClickListener { openAppsDialog() }

        b.warpTopRl.setOnClickListener { launchConfigDetail() }
    }

    private fun openAppsDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val proxyId = ProxyManager.ID_TCP_BASE
        val proxyName = ProxyManager.TCP_PROXY_NAME
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }

    private fun launchConfigDetail() {
        val intent = Intent(this, WgConfigDetailActivity::class.java)
        intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, WARP_ID)
        startActivity(intent)
    }

    private suspend fun createConfigOrShowErrorLayout() {
        val works = isWarpWorking()
        if (works) {
            fetchWarpConfigFromServer()
        } else {
            showConfigCreationError()
        }
    }

    private suspend fun fetchWarpConfigFromServer() {
        val config = WireGuardManager.getNewWarpConfig(SEC_WARP_ID)
        Log.i(LoggerConstants.LOG_TAG_PROXY, "new config from server: ${config?.getName()}")
        if (config == null) {
            showConfigCreationError()
            return
        }
    }

    private suspend fun showConfigCreationError() {
        uiCtx {
            Toast.makeText(this, getString(R.string.new_warp_error_toast), Toast.LENGTH_LONG).show()
            b.enableUdpRelay.isChecked = false
        }
    }

    private fun enableTcpProxy() {
        io { TcpProxyHelper.enable() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
