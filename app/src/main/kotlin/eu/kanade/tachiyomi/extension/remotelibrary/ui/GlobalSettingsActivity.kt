package eu.kanade.tachiyomi.extension.remotelibrary.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.tachiyomi.extension.R
import eu.kanade.tachiyomi.extension.remotelibrary.storage.googledrive.DriveAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlobalSettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var authManager: DriveAuthManager

    private lateinit var tvAccountStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)

        authManager = DriveAuthManager(applicationContext)

        tvAccountStatus = findViewById(R.id.tv_account_status)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)

        findViewById<android.widget.Button>(R.id.btn_close).setOnClickListener { finish() }

        btnConnect.setOnClickListener {
            try {
                authManager.startAuthFlow(this)
            } catch (e: Exception) {
                tvAccountStatus.text = "Error: ${e.message}"
            }
        }

        btnDisconnect.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Disconnect Google Account")
                .setMessage("Revoke access? Your libraries and local data remain, but you'll need to reconnect to browse or read.")
                .setPositiveButton("Disconnect") { _, _ ->
                    authManager.disconnect()
                    updateUI()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DriveAuthManager.REQUEST_CODE_SIGN_IN) {
            scope.launch {
                val success = authManager.handleAuthResult(data)
                withContext(Dispatchers.Main) {
                    if (success) {
                        updateUI()
                    } else {
                        tvAccountStatus.text = "Sign-in failed or was cancelled"
                    }
                }
            }
        }
    }

    private fun updateUI() {
        val authenticated = authManager.isAuthenticated()
        val email = authManager.getAccountEmail()

        tvAccountStatus.text = if (authenticated) {
            "Connected: ${email ?: "Google Account"}"
        } else {
            "Not connected"
        }

        btnConnect.visibility = if (authenticated) View.GONE else View.VISIBLE
        btnDisconnect.visibility = if (authenticated) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
