package top.lcs.v6assist

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class V6TileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (V6Service.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

    override fun onClick() {
        if (V6Service.running) {
            V6Service.stop(this)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 推荐 PendingIntent 版本；startActivityAndCollapse(Intent) 已废弃但可用
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refresh()
    }
}
