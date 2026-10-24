package top.lcs.v6assist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions

class V6App : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        filesDir.mkdirs()
        cacheDir.mkdirs()
        val work = (getExternalFilesDir(null) ?: filesDir)
        work.mkdirs()
        try {
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = filesDir.path
                    it.workingPath = work.path
                    it.tempPath = cacheDir.path
                    it.logMaxLines = 1000
                    it.debug = true
                },
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Libbox.setup failed", e)
        }
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_STATE, getString(R.string.notif_channel_state), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val TAG = "V6App"
        const val CH_STATE = "v6-state"
        const val CH_ALERT = "v6-alert"
        const val NOTIF_ID = 10808

        lateinit var app: V6App
            private set
    }
}
