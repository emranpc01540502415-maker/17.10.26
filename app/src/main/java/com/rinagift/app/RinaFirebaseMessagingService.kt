package com.rinagift.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Receives push notifications sent from the Admin Panel (via
 * admin/includes/fcm.php on the server) and shows them in the
 * phone's notification bar — including when the app is closed.
 */
class RinaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "rina_notifications"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save locally so MainActivity can also read it, and report
        // it to the server right away.
        val prefs = getSharedPreferences("rina_prefs", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        val deviceId = prefs.getString("device_id", null) ?: return
        val adminBaseUrl = getString(R.string.admin_base_url).trimEnd('/')

        Thread {
            try {
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("fcm_token", token)
                }
                val conn = URL("$adminBaseUrl/api/register_device.php").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.inputStream.close()
                conn.disconnect()
            } catch (_: Exception) {
                // best-effort; will retry next app open via MainActivity
            }
        }.start()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: getString(R.string.app_name)
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        val clickUrl = remoteMessage.data["click_url"] ?: ""
        val imageUrl = remoteMessage.notification?.imageUrl?.toString()

        showNotification(title, body, clickUrl, imageUrl)
    }

    private fun showNotification(title: String, body: String, clickUrl: String, imageUrl: String?) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (clickUrl.isNotBlank()) putExtra("open_url", clickUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Try to load and attach a big-picture image (best-effort, ignored on failure)
        if (!imageUrl.isNullOrBlank()) {
            try {
                val bitmap = BitmapFactory.decodeStream(URL(imageUrl).openStream())
                builder.setLargeIcon(bitmap)
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
            } catch (_: Exception) {
                // no image, plain text notification is fine
            }
        }

        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, builder.build())
    }
}
