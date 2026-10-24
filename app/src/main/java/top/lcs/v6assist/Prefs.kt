package top.lcs.v6assist

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.LocalDate

/** 轻量持久化：中继地址、生效 App、按日统计。 */
object Prefs {
    private const val NAME = "v6assist"
    private const val K_HOST = "relay_host"
    private const val K_PORT = "relay_port"
    private const val K_DNS_PORT = "dns_port"
    private const val K_BILI = "include_bili"
    private const val K_DOUYIN = "include_douyin"
    private const val K_DAY = "stat_day"
    private const val K_TOTAL = "stat_total"
    private const val K_V6 = "stat_v6"
    private const val K_CONN = "stat_conn"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun relayHost(c: Context): String = sp(c).getString(K_HOST, "192.168.137.1") ?: "192.168.137.1"

    fun setRelayHost(c: Context, v: String) {
        sp(c).edit().putString(K_HOST, v.trim()).apply()
    }

    fun relayPort(c: Context): Int = sp(c).getInt(K_PORT, 10808)

    fun setRelayPort(c: Context, v: Int) {
        sp(c).edit().putInt(K_PORT, v).apply()
    }

    fun dnsPort(c: Context): Int = sp(c).getInt(K_DNS_PORT, 5354)

    fun setDnsPort(c: Context, v: Int) {
        sp(c).edit().putInt(K_DNS_PORT, v).apply()
    }

    fun includeBili(c: Context): Boolean = sp(c).getBoolean(K_BILI, true)

    fun setIncludeBili(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_BILI, v).apply()
    }

    fun includeDouyin(c: Context): Boolean = sp(c).getBoolean(K_DOUYIN, true)

    fun setIncludeDouyin(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_DOUYIN, v).apply()
    }

    /** 每天第一次访问时清零。 */
    fun ensureToday(c: Context) {
        val s = sp(c)
        val today = LocalDate.now().toString()
        if (s.getString(K_DAY, "") != today) {
            s.edit()
                .putString(K_DAY, today)
                .putLong(K_TOTAL, 0L)
                .putLong(K_V6, 0L)
                .putLong(K_CONN, 0L)
                .apply()
        }
    }

    fun addStat(c: Context, totalDelta: Long, v6Ratio: Double) {
        if (totalDelta <= 0L) return
        ensureToday(c)
        val s = sp(c)
        val total = s.getLong(K_TOTAL, 0L) + totalDelta
        val v6 = s.getLong(K_V6, 0L) + (totalDelta * v6Ratio).toLong()
        s.edit().putLong(K_TOTAL, total).putLong(K_V6, v6).apply()
    }

    fun bumpConn(c: Context, n: Long) {
        if (n <= 0L) return
        ensureToday(c)
        val s = sp(c)
        s.edit().putLong(K_CONN, s.getLong(K_CONN, 0L) + n).apply()
    }

    data class Stat(val total: Long, val v6: Long, val conn: Long)

    fun readStat(c: Context): Stat {
        ensureToday(c)
        val s = sp(c)
        return Stat(s.getLong(K_TOTAL, 0L), s.getLong(K_V6, 0L), s.getLong(K_CONN, 0L))
    }

    fun resetStat(c: Context) {
        sp(c).edit().putLong(K_TOTAL, 0L).putLong(K_V6, 0L).putLong(K_CONN, 0L).apply()
    }
}

/** 从 sing-box 自带 clash_api 取会话统计。 */
object BoxStats {
    private const val BASE = "http://127.0.0.1:9090"

    data class Snap(
        val totalBytes: Long,
        val v6Bytes: Long,
        val v4Bytes: Long,
        val active: Int,
        val v6Active: Int,
    ) {
        val v6Ratio: Double
            get() {
                val sum = v6Bytes + v4Bytes
                return if (sum <= 0L) 0.0 else v6Bytes.toDouble() / sum
            }
    }

    fun snapshot(): Snap? = try {
        val body = httpGet("$BASE/connections") ?: return null
        val root = JSONObject(body)
        val declared = root.optLong("downloadTotal") + root.optLong("uploadTotal")
        var v6 = 0L
        var v4 = 0L
        var v6n = 0
        val arr = root.optJSONArray("connections")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val meta = c.optJSONObject("metadata")
                val ip = meta?.optString("destinationIP") ?: ""
                val bytes = c.optLong("download") + c.optLong("upload")
                if (ip.contains(':')) {
                    v6 += bytes
                    v6n++
                } else {
                    v4 += bytes
                }
            }
        }
        Snap(if (declared > 0) declared else v6 + v4, v6, v4, arr?.length() ?: 0, v6n)
    } catch (e: Throwable) {
        null
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/** 中继连通性探测（TCP 握手）。 */
object RelayProbe {
    fun tcpOk(host: String, port: Int, timeoutMs: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Throwable) {
        false
    }
}
