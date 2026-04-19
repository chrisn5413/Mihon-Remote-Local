package eu.kanade.tachiyomi.extension.cloud.ui

import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.extension.R
import eu.kanade.tachiyomi.extension.cloud.cache.CoverCache
import eu.kanade.tachiyomi.extension.cloud.index.IndexManager
import eu.kanade.tachiyomi.extension.cloud.library.CoverLoadMode
import eu.kanade.tachiyomi.extension.cloud.library.LibraryConfig
import eu.kanade.tachiyomi.extension.cloud.library.LibraryRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibrarySettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIBRARY_ID = "library_id"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val injektApp: Application get() = Injekt.get()

    private lateinit var config: LibraryConfig
    private lateinit var registry: LibraryRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library_settings)

        val ctx = injektApp.applicationContext
        registry = LibraryRegistry(ctx.getSharedPreferences("cloud_extension", MODE_PRIVATE))

        val libraryId = intent.getStringExtra(EXTRA_LIBRARY_ID)
            ?: run { finish(); return }
        config = registry.get(libraryId) ?: run { finish(); return }

        bindViews()
    }

    private fun bindViews() {
        val ctx = injektApp.applicationContext

        // Display name
        val etName = findViewById<EditText>(R.id.et_library_name)
        etName.setText(config.displayName)

        // Drive folder ID (read-only)
        val tvFolderId = findViewById<TextView>(R.id.tv_folder_id)
        tvFolderId.text = config.rootFolderId

        // Last scan time
        val tvLastScan = findViewById<TextView>(R.id.tv_last_scan)
        val indexManager = IndexManager(config, ctx.filesDir)
        tvLastScan.text = indexManager.getLastGeneratedAt() ?: "Never"

        // Cover load mode spinner
        val spinnerCoverMode = findViewById<Spinner>(R.id.spinner_cover_mode)
        val coverModes = CoverLoadMode.values().map { it.name }
        spinnerCoverMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, coverModes)
        spinnerCoverMode.setSelection(config.coverLoadMode.ordinal)

        // Prefetch count
        val etPrefetchCount = findViewById<EditText>(R.id.et_prefetch_count)
        etPrefetchCount.setText(config.prefetchCount.toString())

        // Keep reading cache
        val cbKeepCache = findViewById<CheckBox>(R.id.cb_keep_reading_cache)
        cbKeepCache.isChecked = config.keepReadingCache

        // Cache limit
        val etCacheLimit = findViewById<EditText>(R.id.et_cache_limit_mb)
        etCacheLimit.setText(config.readingCacheLimitMb.toString())

        // Save button
        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            val updated = config.copy(
                displayName = etName.text.toString().trim().ifEmpty { config.displayName },
                coverLoadMode = CoverLoadMode.values()[spinnerCoverMode.selectedItemPosition],
                prefetchCount = etPrefetchCount.text.toString().toIntOrNull() ?: config.prefetchCount,
                keepReadingCache = cbKeepCache.isChecked,
                readingCacheLimitMb = etCacheLimit.text.toString().toIntOrNull() ?: config.readingCacheLimitMb,
            )
            registry.update(updated)
            config = updated
            setResult(RESULT_OK)
        }

        // Rescan
        findViewById<Button>(R.id.btn_rescan).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Rescan Library")
                .setMessage("This will delete the current index and cover cache, then rescan your Drive folder. Continue?")
                .setPositiveButton("Rescan") { _, _ ->
                    indexManager.delete()
                    CoverCache(config.id, ctx.filesDir).clearAll()
                    val scanIntent = Intent(this, ScanProgressActivity::class.java).apply {
                        putExtra(ScanProgressActivity.EXTRA_LIBRARY_JSON, json.encodeToString(config))
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(scanIntent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Clear covers
        findViewById<Button>(R.id.btn_clear_covers).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Cover Cache")
                .setMessage("Delete all cached covers for this library? They will be re-downloaded on demand.")
                .setPositiveButton("Clear") { _, _ ->
                    CoverCache(config.id, ctx.filesDir).clearAll()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Remove library
        findViewById<Button>(R.id.btn_remove_library).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remove Library")
                .setMessage("Remove \"${config.displayName}\"? All local index and cover data will be deleted. Drive files are not affected.")
                .setPositiveButton("Remove") { _, _ ->
                    registry.remove(config.id)
                    val libraryDir = ctx.filesDir.resolve("mihon-cloud/libraries/${config.id}")
                    libraryDir.deleteRecursively()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
