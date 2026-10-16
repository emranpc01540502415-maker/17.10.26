package com.rinagift.app

import android.app.Application
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class RinaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupCrashReporting()
        captureInstallReferrerOnce()
    }

    // ---------------------------------------------------------
    // Crash reporting: catches any uncaught exception anywhere in
    // the app, reports it to the Admin Panel, then lets the app
    // crash normally (so the user isn't stuck in a broken state).
    // ---------------------------------------------------------
    private fun setupCrashReporting() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val prefs = getSharedPreferences("rina_prefs", MODE_PRIVATE)
                val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString()
                val adminBaseUrl = getString(R.string.admin_base_url).trimEnd('/')

                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("app_version", BuildConfig.VERSION_CODE.toString())
                    put("error_message", throwable.message ?: throwable.toString())
                    put("stack_trace", sw.toString().take(4000))
                }
                // Best-effort, short timeout — must not delay the crash/exit.
                val conn = URL("$adminBaseUrl/api/report_crash.php").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode // triggers the request
                conn.disconnect()
            } catch (_: Exception) {
                // never let crash reporting itself throw
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // ---------------------------------------------------------
    // Install Referrer: captures which ad/campaign/link caused
    // this install (Google Play only), feeding the "install_source"
    // shown in Admin Panel analytics.
    // ---------------------------------------------------------
    private fun captureInstallReferrerOnce() {
        val prefs = getSharedPreferences("rina_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("referrer_captured", false)) return

        try {
            val referrerClient = InstallReferrerClient.newBuilder(this).build()
            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try {
                            val response = referrerClient.installReferrer
                            val referrerUrl = response.installReferrer
                            prefs.edit()
                                .putString("install_referrer", referrerUrl)
                                .putBoolean("referrer_captured", true)
                                .apply()
                        } catch (_: Exception) {
                        }
                    }
                    referrerClient.endConnection()
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // will just retry on next app launch since flag not set
                }
            })
        } catch (_: Exception) {
            // Play Services not available (e.g. sideloaded on a non-Play device) — fine, skip.
        }
    }
}
