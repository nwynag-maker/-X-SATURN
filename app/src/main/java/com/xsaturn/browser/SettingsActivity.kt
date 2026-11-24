package com.xsaturn.browser

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.databinding.ActivitySettingsBinding
import com.xsaturn.browser.databinding.ItemSwitchBinding

/** إعدادات المتصفح العامة: محرك البحث والخصوصية. إعدادات الحماية في SecurityActivity. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private lateinit var app: XSaturnApp

    private val securityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) setResult(RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as XSaturnApp
        settings = app.settings

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnOpenSecurity.setOnClickListener {
            securityLauncher.launch(Intent(this, SecurityActivity::class.java))
        }

        val row = ItemSwitchBinding.inflate(layoutInflater, binding.safeSearchContainer, false)
        row.sw.setText(R.string.sw_safe_search)
        row.desc.setText(R.string.sw_safe_search_desc)
        row.sw.isChecked = settings.safeSearch
        row.sw.setOnCheckedChangeListener { _, checked ->
            settings.safeSearch = checked
            setResult(RESULT_OK)
        }
        binding.safeSearchContainer.addView(row.root)

        binding.btnBraveKey.setOnClickListener {
            promptText(R.string.brave_key, R.string.enter_brave_key, settings.braveApiKey) { v ->
                settings.braveApiKey = v
                refreshProviderButtons()
            }
        }
        binding.btnSearx.setOnClickListener {
            promptText(R.string.searx_url, R.string.enter_searx, settings.searxUrl) { v ->
                if (v.isNotEmpty() && !v.startsWith("https://")) {
                    toast(R.string.searx_invalid)
                } else {
                    settings.searxUrl = v
                    refreshProviderButtons()
                }
            }
        }
        binding.btnTestSearch.setOnClickListener { testSearch() }
        binding.btnClearData.setOnClickListener { clearBrowsingData() }

        refreshProviderButtons()
    }

    private fun refreshProviderButtons() {
        val brave = if (settings.braveApiKey.isEmpty()) R.string.value_unset else R.string.value_set
        val searx = if (settings.searxUrl.isEmpty()) R.string.value_unset else R.string.value_set
        binding.btnBraveKey.text = getString(R.string.brave_key) + ": " + getString(brave)
        binding.btnSearx.text = getString(R.string.searx_url) + ": " + getString(searx)
    }

    private fun promptText(title: Int, hint: Int, current: String, onSave: (String) -> Unit) {
        val input = EditText(this)
        input.setText(current)
        input.setHint(hint)
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this)
        frame.setPadding(pad, pad / 2, pad, 0)
        frame.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** يجري بحثاً حقيقياً الآن ويعرض المزوّد الذي أجاب أو سبب الفشل بدقة. */
    private fun testSearch() {
        binding.testResult.setText(R.string.testing)
        binding.btnTestSearch.isEnabled = false
        Thread {
            val msg = try {
                val o = app.search.search("wikipedia", 1, false)
                getString(R.string.test_ok, o.response.provider, o.response.results.size, o.millis / 1000.0)
            } catch (e: Exception) {
                getString(R.string.test_fail, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.testResult.text = msg
                binding.btnTestSearch.isEnabled = true
            }
        }.start()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        WebView(this).apply {
            clearCache(true)
            destroy()
        }
        toast(R.string.clear_data_done)
        setResult(RESULT_OK)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
