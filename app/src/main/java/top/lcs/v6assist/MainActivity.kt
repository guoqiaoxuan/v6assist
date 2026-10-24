package top.lcs.v6assist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var switch: Switch
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var hintView: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var biliBox: CheckBox
    private lateinit var douyinBox: CheckBox

    private var pollJob: Job? = null
    private var lastTotal = -1L
    private var lastConn = 0
    private var pendingStart = false
    private var pendingUntil = 0L

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            doStart()
        } else {
            pendingStart = false
            switch.isChecked = false
            hint("没有授予 VPN 权限，隧道未开启")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadState()
        startPolling()
    }

    override fun onResume() {
        super.onResume()
        checkMutex()
        refreshStatus()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(Color.parseColor("#EAF3FF"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "B 站 / 抖音优先走 IPv6（经 PC 中继）"
                textSize = 13f
                setTextColor(Color.parseColor("#8FA6C0"))
                setPadding(0, dp(4), 0, dp(18))
            },
        )

        switch = Switch(this).apply {
            text = "总开关"
            textSize = 17f
            setTextColor(Color.parseColor("#EAF3FF"))
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                if (isChecked) requestStart() else doStop()
            }
        }
        root.addView(switch)

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#C7D6E8"))
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(statusView)

        statsView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9FE8B0"))
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(statsView)

        hintView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFB86B"))
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(hintView)

        root.addView(sectionTitle("生效的应用"))
        biliBox = CheckBox(this).apply {
            text = "哔哩哔哩 (tv.danmaku.bili)"
            setTextColor(Color.parseColor("#C7D6E8"))
        }
        root.addView(biliBox)
        douyinBox = CheckBox(this).apply {
            text = "抖音 (com.ss.android.ugc.aweme)"
            setTextColor(Color.parseColor("#C7D6E8"))
        }
        root.addView(douyinBox)

        root.addView(sectionTitle("PC 中继地址"))
        hostInput = EditText(this).apply {
            hint = "192.168.137.1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.parseColor("#EAF3FF"))
        }
        root.addView(hostInput)
        portInput = EditText(this).apply {
            hint = "10808"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#EAF3FF"))
        }
        root.addView(portInput)

        root.addView(
            Button(this).apply {
                text = "保存并重启隧道"
                setOnClickListener {
                    saveState()
                    hint("设置已保存")
                    if (V6Service.running) {
                        V6Service.stop(this@MainActivity)
                        Toast.makeText(this@MainActivity, "正在按新设置重连…", Toast.LENGTH_SHORT).show()
                        switch.postDelayed({ if (switch.isChecked) requestStart() }, 1500)
                    }
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "一键关闭并恢复"
                setOnClickListener {
                    saveState()
                    biliBox.isChecked = true
                    doStop()
                    switch.isChecked = false
                    Prefs.resetStat(this@MainActivity)
                    hint("隧道已关闭，路由与通知已清理")
                    refreshStatus()
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "关闭电池优化（华为需手动放行后台）"
                setOnClickListener {
                    runCatching {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }.onFailure {
                        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            },
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1218"))
            addView(root)
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#6F88A6"))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun hint(msg: String) {
        hintView.text = msg
    }

    // ---------- state ----------

    private fun loadState() {
        hostInput.setText(Prefs.relayHost(this))
        portInput.setText(Prefs.relayPort(this).toString())
        biliBox.isChecked = Prefs.includeBili(this)
        douyinBox.isChecked = Prefs.includeDouyin(this)
        switch.isChecked = V6Service.running
    }

    private fun saveState() {
        val host = hostInput.text.toString().trim().ifEmpty { "192.168.137.1" }
        val port = portInput.text.toString().trim().toIntOrNull() ?: 10808
        Prefs.setRelayHost(this, host)
        Prefs.setRelayPort(this, port)
        Prefs.setIncludeBili(this, biliBox.isChecked)
        Prefs.setIncludeDouyin(this, douyinBox.isChecked)
    }

    private fun checkMutex() {
        if (V6Service.running) return
        val cm = getSystemService(ConnectivityManager::class.java)
        var otherVpn = false
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                otherVpn = true
                break
            }
        }
        if (otherVpn) {
            switch.isEnabled = false
            hint("检测到其他 VPN（如 Clash）正在运行。手机同一时刻只能有一个 VPN，请先关掉它再来开这里。")
        } else {
            switch.isEnabled = true
            if (hintView.text.isNullOrBlank()) hint("")
        }
    }

    // ---------- start / stop ----------

    private fun requestStart() {
        saveState()
        val host = Prefs.relayHost(this)
        val port = Prefs.relayPort(this)
        pendingStart = true
        pendingUntil = System.currentTimeMillis() + 15000
        switch.isEnabled = false
        hint("正在检测中继 $host:$port …")
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RelayProbe.tcpOk(host, port, 800)
            }
            switch.isEnabled = true
            if (!ok) {
                pendingStart = false
                switch.isChecked = false
                hint("连不上中继 $host:$port。请确认 PC 已开启中继与热点，且手机在同一网络。")
                return@launch
            }
            hint("")
            val prep = VpnService.prepare(this@MainActivity)
            if (prep != null) {
                vpnPermission.launch(prep)
            } else {
                doStart()
            }
        }
    }

    private fun doStart() {
        hint("正在启动隧道…")
        pendingStart = true
        pendingUntil = System.currentTimeMillis() + 15000
        V6Service.start(this)
        switch.postDelayed({ refreshStatus() }, 800)
    }

    private fun doStop() {
        pendingStart = false
        V6Service.stop(this)
        lastTotal = -1L
        lastConn = 0
        switch.postDelayed({ refreshStatus() }, 600)
    }

    // ---------- 状态与统计 ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                // 网络请求必须离开主线程，否则每秒都会卡住 UI（NetworkOnMainThreadException / ANR）
                val snap = if (V6Service.running) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BoxStats.snapshot() }
                } else null
                refreshStatus(snap)
                sampleStats(snap)
                delay(2000)
            }
        }
    }

    private fun sampleStats(snap: BoxStats.Snap?) {
        if (!V6Service.running) {
            lastTotal = -1L
            lastConn = 0
            return
        }
        if (snap == null) return
        if (lastTotal >= 0 && snap.totalBytes > lastTotal) {
            Prefs.addStat(this, snap.totalBytes - lastTotal, snap.v6Ratio)
        }
        lastTotal = snap.totalBytes
        if (snap.active > lastConn) Prefs.bumpConn(this, (snap.active - lastConn).toLong())
        lastConn = snap.active
    }

    private fun refreshStatus(snap: BoxStats.Snap? = null) {
        val st = Prefs.readStat(this)
        val running = V6Service.running

        if (running) {
            pendingStart = false
        } else if (pendingStart && System.currentTimeMillis() > pendingUntil) {
            pendingStart = false
            switch.isChecked = false
            hint(V6Service.lastError?.let { "启动失败：$it" } ?: "启动超时：中继或隧道内核没有就绪。")
        }
        if (!pendingStart) switch.isChecked = running
        if (running) {
            val secs = if (V6Service.startedAt > 0) (System.currentTimeMillis() - V6Service.startedAt) / 1000 else 0
            statusView.text = "状态：运行中 · 已运行 ${fmtDuration(secs)}\n" +
                "中继：${Prefs.relayHost(this)}:${Prefs.relayPort(this)}"
        } else {
            statusView.text = "状态：" + (if (pendingStart) "正在启动…" else "已停止（系统完全按默认联网）") + "\n" +
                "中继：${Prefs.relayHost(this)}:${Prefs.relayPort(this)}"
        }
        V6Service.lastError?.let { hint("上次错误：$it") }

        val own = if (running) snap else null
        val totalMb = st.total / 1024.0 / 1024.0
        val v6Mb = st.v6 / 1024.0 / 1024.0
        val ratio = if (st.total > 0) st.v6 * 100.0 / st.total else 0.0
        statsView.text = buildString {
            append("今日经中继：%.1f MB（≈ 省下校园网 v4 计费）\n".format(totalMb))
            append("其中 IPv6（含经中继）：%.1f MB（%.1f%%）\n".format(v6Mb, ratio))
            append("新增连接：${st.conn} 个")
            if (own != null) {
                append(" · 活动 ${own.active}（v6 ${own.v6Active}）")
            } else if (running) {
                val why = BoxStats.lastError
                append("\n（统计接口未就绪" + (if (why != null) "：$why" else "") + "）")
            }
        }
    }

    private fun fmtDuration(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "${h}h${m}m" else "${m}m${s}s"
    }
}
