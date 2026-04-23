package eu.kanade.tachiyomi.extension.remotelibrary.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.extension.R
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryConfig
import eu.kanade.tachiyomi.extension.remotelibrary.library.LibraryRegistry
import eu.kanade.tachiyomi.extension.remotelibrary.storage.ConnectionResult
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveClient
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.GoogleDriveStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.regex.Pattern

class AddLibraryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var verifiedFolderId: String? = null

    private lateinit var etDisplayName: EditText
    private lateinit var etFolderUrl: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnSave: Button
    private lateinit var tvVerifyResult: TextView
    private lateinit var progressVerify: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_library)

        etDisplayName = findViewById(R.id.et_display_name)
        etFolderUrl = findViewById(R.id.et_folder_url)
        btnVerify = findViewById(R.id.btn_verify)
        btnSave = findViewById(R.id.btn_save)
        tvVerifyResult = findViewById(R.id.tv_verify_result)
        progressVerify = findViewById(R.id.progress_verify)

        btnSave.isEnabled = false

        btnVerify.setOnClickListener { verifyFolder() }
        btnSave.setOnClickListener { saveLibrary() }
    }

    private fun verifyFolder() {
        val urlOrId = etFolderUrl.text.toString().trim()
        if (urlOrId.isEmpty()) {
            tvVerifyResult.text = "Please enter a Drive folder URL or ID"
            return
        }

        val folderId = extractFolderId(urlOrId)
        if (folderId == null) {
            tvVerifyResult.text = "Could not extract folder ID from the URL"
            return
        }

        progressVerify.visibility = View.VISIBLE
        btnVerify.isEnabled = false
        tvVerifyResult.text = "Verifying..."
        btnSave.isEnabled = false
        verifiedFolderId = null

        scope.launch {
            try {
                val ctx = applicationContext
                val authManager = DriveAuthManager(ctx)
                val driveClient = GoogleDriveClient(authManager)
                val storage = GoogleDriveStorage(driveClient, folderId)
                val result = storage.testConnection()

                withContext(Dispatchers.Main) {
                    when (result) {
                        is ConnectionResult.Success -> {
                            tvVerifyResult.text =
                                "✓ Connected to \"${result.rootFolderName}\" — ${result.seriesCount} series found"
                            verifiedFolderId = folderId
                            btnSave.isEnabled = etDisplayName.text.isNotEmpty()
                        }
                        is ConnectionResult.Failure -> {
                            tvVerifyResult.text = "✗ ${result.reason}"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvVerifyResult.text = "✗ Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressVerify.visibility = View.GONE
                    btnVerify.isEnabled = true
                }
            }
        }
    }

    private fun saveLibrary() {
        val folderId = verifiedFolderId ?: return
        val displayName = etDisplayName.text.toString().trim()
        if (displayName.isEmpty()) return

        val config = LibraryConfig(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            rootFolderId = folderId,
        )
        // LibraryRegistry uses the ContentProvider — works from both the extension
        // process (here) and Mihon's process (RemoteLibraryFactory).
        LibraryRegistry(applicationContext).add(config)

        // Start the scan immediately so the index is ready before the user restarts Mihon.
        val scanIntent = Intent(this, ScanProgressActivity::class.java).apply {
            putExtra(ScanProgressActivity.EXTRA_LIBRARY_JSON, Json.encodeToString(config))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        startActivity(scanIntent)

        android.widget.Toast.makeText(
            this,
            "\"$displayName\" added. Restart Mihon after the scan finishes to browse it.",
            android.widget.Toast.LENGTH_LONG,
        ).show()

        setResult(RESULT_OK)
        finish()
    }

    private fun extractFolderId(input: String): String? {
        // Direct ID (no slashes, alphanumeric+dash+underscore, 20+ chars)
        if (!input.contains('/') && input.matches(Regex("[a-zA-Z0-9_-]{20,}"))) {
            return input
        }
        val patterns = listOf(
            Pattern.compile("/folders/([a-zA-Z0-9_-]+)"),
            Pattern.compile("[?&]id=([a-zA-Z0-9_-]+)"),
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(input)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
