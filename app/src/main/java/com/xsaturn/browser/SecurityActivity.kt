package com.xsaturn.browser

import android.net.Uri
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xsaturn.browser.adblock.AdBlocker
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.databinding.ActivitySecurityBinding
import com.xsaturn.browser.databinding.ItemStatRowBinding
import com.xsaturn.browser.databinding.ItemSwitchBinding
import com.xsaturn.browser.security.BlockCategory
import com.xsaturn.browser.security.ListUpdater
import com.xsaturn.browser.security.SecurityStats
import com.xsaturn.browser.security.ThreatEngine
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KMutableProperty0

/** لوحة X SATURN SECURITY: تحكم كامل بالحماية + إحصائيات حجب حقيقية. */
class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var settings: SettingsRepository
    private lateinit var blocker: AdBlocker
    private lateinit var threats: ThreatEngine
    private lateinit var stats: SecurityStats
    private val nf: NumberFormat = NumberFormat.getInstance()

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importList(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as XSaturnApp
        settings = app.settings
        blocker = app.blocker
        threats = app.threats
        stats = app.stats

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swProtection.isChecked = settings.protectionEnabled
        binding.swProtection.setOnCheckedChangeListener { _, checked ->
            settings.protectionEnabled = checked
            setResult(RESULT_OK)
            refreshStatus()
        }

        val t = binding.togglesContainer
        addToggle(t, R.string.sw_strict, R.string.sw_strict_desc, settings::strictMode)
        addToggle(t, R.string.sw_popups, R.string.sw_popups_desc, settings::blockPopups)
        addToggle(t, R.string.sw_redirects, R.string.sw_redirects_desc, settings::blockRedirects)
        addToggle(t, R.string.sw_confirm_nav, R.string.sw_confirm_nav_desc, settings::confirmNavigation)
        addToggle(t, R.string.sw_trackers, R.string.sw_trackers_desc, settings::blockTrackers)
        addToggle(t, R.string.sw_hide, R.string.sw_hide_desc, settings::hideElements)
        addToggle(t, R.string.sw_overlays, R.string.sw_overlays_desc, settings::blockOverlays)
        addToggle(t, R.string.sw_malware, R.string.sw_malware_desc, settings::blockMalware)
        addToggle(t, R.string.sw_phishing, R.string.sw_phishing_desc, settings::blockPhishing)
        addToggle(t, R.string.sw_adult, R.string.sw_adult_desc, settings::blockAdult)
        addToggle(t, R.string.sw_safe, R.string.sw_safe_desc, settings::safeBrowsing)
        addToggle(
            binding.autoUpdateContainer, R.string.sw_auto_update,
            R.string.sw_auto_update_desc, settings::autoUpdateLists
        )

        binding.btnUpdateLists.setOnClickListener { updateLists() }
        binding.btnImport.setOnClickListener { importPicker.launch(arrayOf("*/*")) }
        binding.btnClearImported.setOnClickListener {
            Thread {
                blocker.clearImported()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    toast(R.string.imported_cleared)
                    refreshLists()
                    setResult(RESULT_OK)
                }
            }.start()
        }
        binding.btnAllowed.setOnClickListener { showAllowedSitesDialog() }
        binding.btnResetStats.setOnClickListener {
            stats.reset()
            refreshStats()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        refreshLists()
    }

    private fun addToggle(
        container: LinearLayout,
        title: Int,
        desc: Int,
        prop: KMutableProperty0<Boolean>
    ) {
        val row = ItemSwitchBinding.inflate(layoutInflater, container, false)
        row.sw.setText(title)
        row.desc.setText(desc)
        row.sw.isChecked = prop.get()
        row.sw.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prop.set(checked)
            setResult(RESULT_OK)
        }
        container.addView(row.root)
    }

    private fun refreshStatus() {
        binding.statusText.setText(if (settings.protectionEnabled) R.string.security_on else R.string.security_off)
    }

    // ------------------------------------------------------------ الإحصائيات

    private fun labelOf(c: BlockCategory): Int = when (c) {
        BlockCategory.AD -> R.string.cat_ad
        BlockCategory.TRACKER -> R.string.cat_tracker
        BlockCategory.POPUP -> R.string.cat_popup
        BlockCategory.REDIRECT -> R.string.cat_redirect
        BlockCategory.HIDDEN -> R.string.cat_hidden
        BlockCategory.MALWARE -> R.string.cat_malware
        BlockCategory.PHISHING -> R.string.cat_phishing
        BlockCategory.ADULT -> R.string.cat_adult
    }

    private fun refreshStats() {
        val s = stats.snapshot()
        binding.totalNumber.text = nf.format(s.total)
        binding.todayNumber.text = nf.format(s.today)

        binding.categoryContainer.removeAllViews()
        val maxV = (s.byCategory.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        for ((cat, value) in s.byCategory) {
            val row = ItemStatRowBinding.inflate(layoutInflater, binding.categoryContainer, false)
            row.label.setText(labelOf(cat))
            row.count.text = nf.format(value)
            row.bar.setProgressCompat((value * 100L / maxV).toInt(), false)
            binding.categoryContainer.addView(row.root)
        }

        binding.weekBars.setData(s.last7Days)

        binding.topDomains.text = if (s.topDomains.isEmpty()) {
            getString(R.string.none_yet)
        } else {
            s.topDomains.joinToString("\n") { it.first + "   ×" + nf.format(it.second) }
        }

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.recentList.text = if (s.recent.isEmpty()) {
            getString(R.string.none_yet)
        } else {
            s.recent.take(15).joinToString("\n") {
                fmt.format(Date(it.time)) + "  " + getString(labelOf(it.category)) + "  " + it.host
            }
        }
    }

    private fun refreshLists() {
        val a = blocker.stats
        val c = threats.counts
        binding.listsInfo.text = getString(
            R.string.lists_info,
            nf.format(a.adDomains), nf.format(a.trackerDomains), nf.format(a.patterns),
            nf.format(a.cosmeticRules), nf.format(c.malware), nf.format(c.phishing), nf.format(c.adult)
        )
        val ts = settings.listsUpdatedAt
        val status = if (ts == 0L) {
            getString(R.string.lists_never)
        } else {
            getString(R.string.lists_updated, DateFormat.getDateTimeInstance().format(Date(ts)))
        }
        val detail = settings.listsStatus
        binding.listsStatus.text = if (detail.isEmpty()) status else status + "\n" + detail
    }

    // ------------------------------------------------------------ تحديث القوائم

    private fun updateLists() {
        binding.btnUpdateLists.isEnabled = false
        binding.btnUpdateLists.setText(R.string.updating)
        Thread {
            val results = ListUpdater(this, settings).updateAll()
            if (results.any { it.ok }) {
                blocker.load()
                threats.load()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.btnUpdateLists.isEnabled = true
                binding.btnUpdateLists.setText(R.string.update_now)
                Toast.makeText(
                    this, getString(R.string.update_done, results.count { it.ok }, results.size),
                    Toast.LENGTH_LONG
                ).show()
                refreshLists()
                setResult(RESULT_OK)
            }
        }.start()
    }

    // ------------------------------------------------------------ استيراد القوائم

    private fun importList(uri: Uri) {
        Thread {
            val out = blocker.newImportFile()
            var ok = false
            try {
                var total = 0L
                val input = contentResolver.openInputStream(uri) ?: throw IOException("no stream")
                input.use { src ->
                    out.outputStream().use { dst ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMPORT_BYTES) throw IOException("file too large")
                            dst.write(buf, 0, n)
                        }
                    }
                }
                blocker.load()
                ok = true
            } catch (e: Exception) {
                out.delete()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (ok) R.string.import_done else R.string.import_failed)
                if (ok) {
                    refreshLists()
                    setResult(RESULT_OK)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ المواقع المستثناة

    private fun showAllowedSitesDialog() {
        val sites = settings.allowedSites.sorted()
        if (sites.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.allowed_sites)
                .setMessage(R.string.allowed_sites_empty)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val checked = BooleanArray(sites.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.allowed_sites_remove_title)
            .setMultiChoiceItems(sites.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.remove) { _, _ ->
                val toRemove = sites.filterIndexed { i, _ -> checked[i] }
                if (toRemove.isNotEmpty()) {
                    settings.removeAllowedSites(toRemove)
                    setResult(RESULT_OK)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MAX_IMPORT_BYTES = 25L * 1024 * 1024
    }
}
