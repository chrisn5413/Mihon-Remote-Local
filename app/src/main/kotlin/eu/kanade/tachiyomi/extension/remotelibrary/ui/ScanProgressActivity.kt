package eu.kanade.tachiyomi.extension.remotelibrary.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.extension.R
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexGenerator
import eu.kanade.tachiyomi.extension.remotelibrary.index.IndexManager
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class ScanProgressActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIBRARY_JSON = "library_json"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSeries: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_progress)

        tvTitle = findViewById(R.id.tv_title)
        tvStatus = findViewById(R.id.tv_status)
        tvSeries = findViewById(R.id.tv_series)
        progressBar = findViewById(R.id.progress_bar)
        btnRetry = findViewById(R.id.btn_retry)
        tvError = findViewById(R.id.tv_error)

        val json = Json { ignoreUnknownKeys = true }
        val configJson = intent.getStringExtra(EXTRA_LIBRARY_JSON)
            ?: run { finish(); return }
        val config = json.decodeFromString<LibraryConfig>(configJson)

        tvTitle.text = config.displayName
        btnRetry.setOnClickListener { startScan(config) }

        startScan(config)
    }

    private fun startScan(config: LibraryConfig) {
        showScanning()
        scanJob = scope.launch {
            try {
                val ctx = applicationContext
                val authManager = DriveAuthManager(ctx)
                val okHttpClient = OkHttpClient()
                val driveClient = GoogleDriveClient(authManager, okHttpClient)
                val storage = GoogleDriveStorage(driveClient, config.rootFolderId)
                val generator = IndexGenerator(driveClient, storage)
                val indexManager = IndexManager(config, ctx.filesDir)

                val index = generator.generate(config) { current, total, name ->
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Scanning... ($current / $total series)"
                        tvSeries.text = name
                        if (total > 0) {
                            progressBar.isIndeterminate = false
                            progressBar.max = total
                            progressBar.progress = current
                        }
                    }
                }

                indexManager.save(index)
                withContext(Dispatchers.Main) { finish() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError(e.message ?: "Scan failed") }
            }
        }
    }

    private fun showScanning() {
        tvStatus.text = "Scanning library..."
        tvSeries.text = ""
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        btnRetry.visibility = View.GONE
        tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        tvStatus.text = "Scan failed"
        tvError.text = message
        tvError.visibility = View.VISIBLE
        btnRetry.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (scanJob?.isActive == true) {
            AlertDialog.Builder(this)
                .setTitle("Cancel scan?")
                .setMessage("The scan is still running. Cancel and go back?")
                .setPositiveButton("Cancel scan") { _, _ ->
                    scanJob?.cancel()
                    finish()
                }
                .setNegativeButton("Keep scanning", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
