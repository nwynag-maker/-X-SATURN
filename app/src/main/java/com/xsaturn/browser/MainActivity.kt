package com.xsaturn.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.xsaturn.browser.browser.BrowserCallbacks
import com.xsaturn.browser.browser.PopupGuard
import com.xsaturn.browser.browser.Tab
import com.xsaturn.browser.browser.UrlUtils
import com.xsaturn.browser.browser.WebViewConfigurator
import com.xsaturn.browser.browser.XChromeClient
import com.xsaturn.browser.browser.XWebViewClient
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.databinding.ActivityMainBinding
import com.xsaturn.browser.databinding.ItemTabBinding
import com.xsaturn.browser.databinding.PopupMenuBinding
import java.text.NumberFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var app: XSaturnApp

    private val tabs = ArrayList<Tab>()
    private lateinit var current: Tab
    private var nextTabId = 1

    private lateinit var mobileUa: String
    private lateinit var desktopUa: String

    private val privacyHeaders = mapOf("Sec-GPC" to "1", "DNT" to "1")

    private val webView: WebView get() = current.webView
    private val client: XWebViewClient get() = current.client
    private val currentUrl: String get() = current.url

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                for (t in tabs) {
                    t.guard.install()
                    WebViewConfigurator.applyDynamic(t.webView, settings)
                }
                refreshShield()
                webView.reload()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as XSaturnApp
        settings = app.settings

        setupUrlBar()
        setupButtons()
        setupBackHandling()

        val saved = savedInstanceState?.getStringArrayList(KEY_TABS)
        if (saved != null && saved.isNotEmpty()) {
            val cur = (savedInstanceState?.getInt(KEY_CURRENT, 0) ?: 0).coerceIn(0, saved.size - 1)
            for (u in saved) {
                val t = createTab()
                t.url = u
                t.needsLoad = true
            }
            switchTo(tabs[cur])
        } else {
            val first = createTab()
            switchTo(first)
            val fromIntent = httpUrlFrom(intent)
            loadUrl(fromIntent ?: UrlUtils.HOME_URL)
        }
        refreshShield()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        httpUrlFrom(intent)?.let { openNewTab(it) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        WebViewConfigurator.applyDynamic(webView, settings)
    }

    override fun onPause() {
        client.flushStats()
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_TABS, ArrayList(tabs.map { it.url }))
        outState.putInt(KEY_CURRENT, tabs.indexOf(current).coerceAtLeast(0))
    }

    override fun onDestroy() {
        for (t in tabs) {
            t.client.release()
            (t.webView.parent as? ViewGroup)?.removeView(t.webView)
            t.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------ التبويبات

    /** يمرّر أحداث تبويب معيّن إلى الواجهة، ولا يعرضها إلا إن كان هو التبويب الظاهر. */
    private inner class TabCallbacks(private val tab: Tab) : BrowserCallbacks {
        private fun visible() = this@MainActivity::current.isInitialized && tab === current

        override fun onPageStarted(url: String) {
            tab.url = url
            tab.loading = true
            tab.errorMessage = null
            if (visible()) uiPageStarted(url)
        }

        override fun onPageFinished(url: String) {
            tab.loading = false
            tab.title = titleFor(tab, url)
            if (visible()) uiPageFinished()
        }

        override fun onUrlChanged(url: String) {
            tab.url = url
            if (visible()) uiUrlChanged(url)
        }

        override fun onProgress(percent: Int) {
            if (visible()) uiProgress(percent)
        }

        override fun onBlockedCountChanged(count: Int) {
            if (visible()) uiBlockedCount(count)
        }

        override fun onRedirectBlocked(url: String) {
            if (visible()) uiRedirectBlocked(url)
        }

        override fun onNotice(message: String) {
            this@MainActivity.onNotice(message)
        }

        override fun onMainFrameError(url: String?, message: String) {
            tab.loading = false
            tab.errorMessage = message
            if (!url.isNullOrEmpty()) tab.url = url
            if (visible()) uiMainFrameError(message)
        }

        override fun onConfirmNavigation(
            url: String,
            fromHost: String?,
            proceed: () -> Unit,
            trustSite: () -> Unit
        ) {
            if (visible()) uiConfirmNavigation(url, proceed, trustSite)
        }

        override fun onOpenSecurity() {
            if (visible()) {
                settingsLauncher.launch(Intent(this@MainActivity, SecurityActivity::class.java))
            }
        }
    }

    private fun titleFor(tab: Tab, url: String): String {
        val host = UrlUtils.hostOf(url)
        if (host == UrlUtils.SEARCH_HOST) return "X SATURN"
        val t = tab.webView.title
        return if (!t.isNullOrBlank() && t != url) t else (host ?: url)
    }

    private fun createTab(): Tab {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        WebViewConfigurator.configure(wv, settings)
        if (!::mobileUa.isInitialized) {
            mobileUa = wv.settings.userAgentString
            desktopUa = mobileUa
                .replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "")
        }
        val tab = Tab(nextTabId++, wv)
        val callbacks = TabCallbacks(tab)
        val g = PopupGuard(this, wv, settings)
        g.install()
        val c = XWebViewClient(
            this, settings, app.blocker, app.threats, app.stats, app.pages, g, callbacks
        )
        tab.guard = g
        tab.client = c
        wv.webViewClient = c
        wv.webChromeClient = XChromeClient(settings, app.blocker, callbacks, c)
        wv.setDownloadListener { _, _, _, _, _ ->
            onNotice(getString(R.string.downloads_unsupported))
        }
        tabs.add(tab)
        updateTabCount()
        return tab
    }

    private fun switchTo(tab: Tab) {
        if (::current.isInitialized && current !== tab && tabs.contains(current)) {
            captureThumb(current)
            current.webView.onPause()
        }
        current = tab
        binding.webContainer.removeAllViews()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        binding.webContainer.addView(tab.webView)
        tab.webView.onResume()

        hideTabs()
        binding.urlInput.clearFocus()
        binding.urlInput.setText(UrlUtils.displayUrl(tab.url))
        updateChromeMode(tab.url)
        applyLoadingUi(tab.loading)

        val err = tab.errorMessage
        if (err != null) {
            uiMainFrameError(err)
        } else {
            binding.errorView.visibility = View.GONE
        }
        uiBlockedCount(tab.client.currentCount())
        refreshShield()
        updateNavButtons()
        updateTabCount()

        if (tab.needsLoad) {
            tab.needsLoad = false
            loadUrl(if (tab.url.isEmpty()) UrlUtils.HOME_URL else tab.url)
        }
    }

    private fun openNewTab(url: String = UrlUtils.HOME_URL) {
        val t = createTab()
        switchTo(t)
        loadUrl(url)
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasCurrent = tab === current
        tab.client.release()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            val t = createTab()
            switchTo(t)
            loadUrl(UrlUtils.HOME_URL)
        } else if (wasCurrent) {
            switchTo(tabs[minOf(idx, tabs.size - 1)])
        }
        updateTabCount()
        if (binding.tabsOverlay.visibility == View.VISIBLE) rebuildTabsGrid()
    }

    private fun updateTabCount() {
        val label = if (tabs.size > 99) ":D" else tabs.size.toString()
        binding.tabCountText.text = label
        binding.homeTabCount.text = label
    }

    /** في الصفحة الرئيسية يظهر شريط علوي مبسّط (التبويبات ⋮ والدرع) بدل شريط العنوان. */
    private fun updateChromeMode(url: String) {
        val path = Uri.parse(url).path ?: ""
        val home = url.isEmpty() || (UrlUtils.hostOf(url) == UrlUtils.SEARCH_HOST &&
            (path == "" || path == "/" || path == "/security" || path == "/proceed"))
        binding.homeBar.visibility = if (home) View.VISIBLE else View.GONE
        binding.topBar.visibility = if (home) View.GONE else View.VISIBLE
    }

    /** يلتقط معاينة مصغّرة لأعلى الصفحة. أي فشل يُتجاهل (تبقى بلا معاينة). */
    private fun captureThumb(tab: Tab) {
        try {
            val w = tab.webView.width
            val h = tab.webView.height
            if (w <= 0 || h <= 0) return
            val tw = 360
            val th = 300
            val scale = tw.toFloat() / w
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.BLACK)
            canvas.scale(scale, scale)
            tab.webView.draw(canvas)
            tab.thumb = bmp
        } catch (e: Throwable) {
            // بلا معاينة
        }
    }

    private fun showTabs() {
        hideKeyboard()
        captureThumb(current)
        rebuildTabsGrid()
        binding.tabsOverlay.visibility = View.VISIBLE
    }

    private fun hideTabs() {
        binding.tabsOverlay.visibility = View.GONE
    }

    private fun rebuildTabsGrid() {
        val grid = binding.tabsGrid
        grid.removeAllViews()
        binding.tabsTitle.text = getString(R.string.tabs_title, tabs.size)

        var row: LinearLayout? = null
        for (i in tabs.indices) {
            val tab = tabs[i]
            if (i % 2 == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                r.layoutDirection = View.LAYOUT_DIRECTION_LTR
                grid.addView(
                    r,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                row = r
            }
            val target = row ?: continue
            val b = ItemTabBinding.inflate(layoutInflater, target, false)
            b.tabTitle.text = tab.title.ifEmpty {
                UrlUtils.hostOf(tab.url) ?: getString(R.string.tab_default_title)
            }
            b.root.isSelected = tab === current
            b.root.clipToOutline = true
            val th = tab.thumb
            if (th != null) b.tabThumb.setImageBitmap(th) else b.tabThumb.setImageDrawable(null)
            b.root.setOnClickListener { switchTo(tab) }
            b.tabClose.setOnClickListener { closeTab(tab) }
            target.addView(b.root)
        }
        if (tabs.size % 2 == 1) {
            val spacer = View(this)
            val lp = LinearLayout.LayoutParams(0, 1, 1f)
            val m = dp(6)
            lp.setMargins(m, m, m, m)
            row?.addView(spacer, lp)
        }
    }

    // ------------------------------------------------------------ إعداد الواجهة

    private fun setupUrlBar() {
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(binding.urlInput.text.toString())
                hideKeyboard()
                true
            } else false
        }
        binding.urlInput.setOnFocusChangeListener { _, hasFocus ->
            binding.urlBar.isSelected = hasFocus
            if (::current.isInitialized) {
                if (hasFocus) {
                    binding.urlInput.setText(UrlUtils.editText(currentUrl))
                    binding.urlInput.selectAll()
                } else {
                    binding.urlInput.setText(UrlUtils.displayUrl(currentUrl))
                }
            }
        }
        binding.shieldButton.setOnClickListener { showShieldDialog() }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        binding.btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        binding.btnHome.setOnClickListener { loadUrl(UrlUtils.HOME_URL) }
        binding.btnLogo.setOnClickListener { loadUrl(UrlUtils.HOME_URL) }
        binding.btnSecurity.setOnClickListener { showShieldDialog() }
        binding.btnMenu.setOnClickListener { showMenu(it) }
        binding.homeMenu.setOnClickListener { showMenu(it) }
        binding.homeTabs.setOnClickListener { binding.btnTabs.performClick() }
        binding.homeShield.setOnClickListener { binding.shieldButton.performClick() }
        binding.btnTabs.setOnClickListener {
            if (binding.tabsOverlay.visibility == View.VISIBLE) hideTabs() else showTabs()
        }
        binding.btnNewTab.setOnClickListener { openNewTab() }
        binding.btnReload.setOnClickListener {
            when {
                current.loading -> {
                    webView.stopLoading()
                    current.loading = false
                    applyLoadingUi(false)
                }
                binding.errorView.visibility == View.VISIBLE && currentUrl.isNotEmpty() -> loadUrl(currentUrl)
                else -> webView.reload()
            }
        }
        binding.retryButton.setOnClickListener {
            if (currentUrl.isNotEmpty()) loadUrl(currentUrl)
        }
        binding.httpButton.setOnClickListener {
            val target = client.pendingHttpUrl ?: return@setOnClickListener
            client.allowHttp(UrlUtils.hostOf(target))
            loadUrl(target)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.tabsOverlay.visibility == View.VISIBLE -> hideTabs()
                    webView.canGoBack() -> webView.goBack()
                    tabs.size > 1 -> closeTab(current)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------ التحميل

    private fun httpUrlFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val scheme = data.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") data.toString() else null
    }

    private fun loadUrl(raw: String) {
        binding.errorView.visibility = View.GONE
        current.errorMessage = null
        webView.loadUrl(UrlUtils.resolveInput(raw), privacyHeaders)
        webView.requestFocus()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        binding.urlInput.clearFocus()
        if (::current.isInitialized) webView.requestFocus()
    }

    private fun applyLoadingUi(value: Boolean) {
        binding.btnReload.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_reload)
        binding.btnReload.contentDescription = getString(if (value) R.string.cd_stop else R.string.cd_reload)
        if (!value) binding.progress.visibility = View.INVISIBLE
    }

    private fun updateNavButtons() {
        binding.btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        binding.btnForward.alpha = if (webView.canGoForward()) 1f else 0.35f
        binding.navHomePill.isSelected = UrlUtils.hostOf(currentUrl) == UrlUtils.SEARCH_HOST
    }

    // ------------------------------------------------------------ القائمة (⋮)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showMenu(anchor: View) {
        val m = PopupMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(m.root, dp(290), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(8).toFloat()

        m.menuBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        m.menuForward.alpha = if (webView.canGoForward()) 1f else 0.35f
        m.menuReload.setImageResource(if (current.loading) R.drawable.ic_stop else R.drawable.ic_reload)
        m.menuDesktopCheck.visibility = if (current.desktop) View.VISIBLE else View.INVISIBLE

        m.menuBack.setOnClickListener {
            popup.dismiss()
            if (webView.canGoBack()) webView.goBack()
        }
        m.menuForward.setOnClickListener {
            popup.dismiss()
            if (webView.canGoForward()) webView.goForward()
        }
        m.menuReload.setOnClickListener {
            popup.dismiss()
            binding.btnReload.performClick()
        }
        m.menuShare.setOnClickListener {
            popup.dismiss()
            shareCurrentUrl()
        }
        m.menuNewTab.setOnClickListener {
            popup.dismiss()
            openNewTab()
        }
        m.menuHome.setOnClickListener {
            popup.dismiss()
            loadUrl(UrlUtils.HOME_URL)
        }
        m.menuShield.setOnClickListener {
            popup.dismiss()
            showShieldDialog()
        }
        m.menuSecurity.setOnClickListener {
            popup.dismiss()
            settingsLauncher.launch(Intent(this, SecurityActivity::class.java))
        }
        m.menuDesktop.setOnClickListener {
            popup.dismiss()
            setDesktopMode(!current.desktop)
        }
        m.menuCopy.setOnClickListener {
            popup.dismiss()
            copyCurrentUrl()
        }
        m.menuSettings.setOnClickListener {
            popup.dismiss()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private fun setDesktopMode(on: Boolean) {
        current.desktop = on
        webView.settings.userAgentString = if (on) desktopUa else mobileUa
        webView.reload()
    }

    private fun shareCurrentUrl() {
        if (currentUrl.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
        }
        startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
    }

    private fun copyCurrentUrl() {
        if (currentUrl.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
        onNotice(getString(R.string.link_copied))
    }

    // ------------------------------------------------------------ الدرع

    private fun refreshShield() {
        val active = settings.isProtectionActive(UrlUtils.hostOf(currentUrl))
        val color = ContextCompat.getColor(this, if (active) R.color.xs_red else R.color.xs_muted)
        binding.shieldIcon.setColorFilter(color)
        binding.navShieldIcon.setColorFilter(color)
        binding.shieldCount.setTextColor(color)
    }

    private fun formatCount(n: Int): String = if (n > 99) "99+" else n.toString()

    private fun showShieldDialog() {
        val host = UrlUtils.hostOf(currentUrl)
        val nf = NumberFormat.getInstance()
        val globalOn = settings.protectionEnabled
        val siteAllowed = settings.isSiteAllowed(host)

        val state = when {
            !globalOn -> R.string.shield_state_global_off
            siteAllowed -> R.string.shield_state_site_off
            else -> R.string.shield_state_on
        }
        val message = buildString {
            append(getString(state))
            append("\n\n")
            append(getString(R.string.shield_blocked_page, nf.format(client.currentCount())))
            append("\n")
            append(getString(R.string.shield_blocked_total, nf.format(app.stats.total())))
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shield_title)
            .setMessage(message)
            .setPositiveButton(R.string.open_security) { _, _ ->
                settingsLauncher.launch(Intent(this, SecurityActivity::class.java))
            }
            .setNegativeButton(R.string.close, null)

        if (globalOn && host != null) {
            val label = if (siteAllowed) R.string.shield_enable_site else R.string.shield_disable_site
            builder.setNeutralButton(label) { _, _ ->
                settings.setSiteAllowed(host, !siteAllowed)
                for (t in tabs) t.guard.install()
                refreshShield()
                webView.reload()
            }
        }
        builder.show()
    }

    // ------------------------------------------------------------ تحديثات الواجهة (للتبويب الظاهر فقط)

    private fun uiPageStarted(url: String) {
        binding.errorView.visibility = View.GONE
        applyLoadingUi(true)
        binding.progress.visibility = View.VISIBLE
        binding.progress.setProgressCompat(5, false)
        if (!binding.urlInput.hasFocus()) binding.urlInput.setText(UrlUtils.displayUrl(url))
        updateChromeMode(url)
        refreshShield()
        updateNavButtons()
    }

    private fun uiPageFinished() {
        applyLoadingUi(false)
        updateNavButtons()
    }

    private fun uiUrlChanged(url: String) {
        if (!binding.urlInput.hasFocus()) binding.urlInput.setText(UrlUtils.displayUrl(url))
        updateChromeMode(url)
        refreshShield()
        updateNavButtons()
    }

    private fun uiProgress(percent: Int) {
        if (!current.loading) return
        binding.progress.setProgressCompat(percent, true)
        if (percent >= 100) binding.progress.visibility = View.INVISIBLE
    }

    private fun uiBlockedCount(count: Int) {
        if (count > 0) {
            binding.shieldCount.text = formatCount(count)
            binding.shieldCount.visibility = View.VISIBLE
        } else {
            binding.shieldCount.visibility = View.GONE
        }
    }

    private fun uiRedirectBlocked(url: String) {
        val host = UrlUtils.hostOf(url) ?: url
        Snackbar.make(binding.root, getString(R.string.redirect_blocked, host), Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomBar)
            .setAction(R.string.allow) { webView.loadUrl(url) }
            .show()
    }

    private fun uiMainFrameError(message: String) {
        applyLoadingUi(false)
        binding.errorText.text = message
        binding.httpButton.visibility = if (client.pendingHttpUrl != null) View.VISIBLE else View.GONE
        binding.errorView.visibility = View.VISIBLE
    }

    private var navDialog: androidx.appcompat.app.AlertDialog? = null

    private fun uiConfirmNavigation(url: String, proceed: () -> Unit, trustSite: () -> Unit) {
        if (isFinishing || navDialog?.isShowing == true) return
        val shown = if (url.length > 140) url.take(140) + "…" else url
        navDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nav_confirm_title)
            .setMessage(getString(R.string.nav_confirm_message, shown))
            .setPositiveButton(R.string.nav_go) { _, _ -> proceed() }
            .setNegativeButton(R.string.nav_stay, null)
            .setNeutralButton(R.string.nav_go_trust) { _, _ ->
                trustSite()
                proceed()
            }
            .show()
    }

    private fun onNotice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_TABS = "tab_urls"
        const val KEY_CURRENT = "tab_current"
    }
}
