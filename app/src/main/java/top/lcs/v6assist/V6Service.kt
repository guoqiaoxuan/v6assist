package top.lcs.v6assist

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntry
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface

class V6Service : VpnService(), PlatformInterface, CommandServerHandler {

    private var commandServer: CommandServer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var shuttingDown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mainHandler.post { shutdown() }
            return START_NOT_STICKY
        }
        startForeground(V6App.NOTIF_ID, buildNotification("正在启动…"))
        instance = this
        if (!running) {
            running = true
            lastError = null
            startedAt = System.currentTimeMillis()
            val appCtx = applicationContext
            Thread {
                try {
                    startBox(appCtx)
                } catch (e: Throwable) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "startBox failed", e)
                    mainHandler.post { shutdown() }
                }
            }.start()
        }
        return START_STICKY
    }

    private fun startBox(ctx: Context) {
        if (prepare(ctx) != null) throw IllegalStateException("未获得 VPN 授权（请先在系统弹窗里允许）")
        val content = buildConfig(ctx)
        val server = CommandServer(this, this)
        server.start()
        commandServer = server
        server.startOrReloadService(content, OverrideOptions())
        updateNotification("已开启 · 中继 ${Prefs.relayHost(ctx)}:${Prefs.relayPort(ctx)}")
        Log.i(TAG, "box started")
    }

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        running = false
        try {
            fileDescriptor?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "close pfd: ${e.message}")
        }
        fileDescriptor = null
        val server = commandServer
        commandServer = null
        if (server != null) {
            try {
                server.closeService()
            } catch (e: Throwable) {
                Log.w(TAG, "closeService: ${e.message}")
            }
            try {
                server.close()
            } catch (e: Throwable) {
                Log.w(TAG, "close server: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        instance = null
        stopSelf()
        Log.i(TAG, "shutdown done")
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        mainHandler.post { shutdown() }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ---------- 通知 ----------

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, V6Service::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, V6App.CH_STATE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("V6 助手")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "关闭", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(V6App.NOTIF_ID, buildNotification(text))
        } catch (e: Throwable) {
            Log.w(TAG, "updateNotification: ${e.message}")
        }
    }

    // ---------- CommandServerHandler ----------

    override fun serviceStop() {
        mainHandler.post { shutdown() }
    }

    override fun serviceReload() {
        val ctx = applicationContext
        val server = commandServer ?: return
        server.startOrReloadService(buildConfig(ctx), OverrideOptions())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().also {
        it.available = false
        it.enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() {
        throw RuntimeException("debug native crash")
    }

    override fun writeDebugMessage(message: String) {
        Log.d("sing-box", message)
    }

    override fun connectSSHAgent(): Int = -1

    // ---------- PlatformInterface ----------

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun useProcFS(): Boolean = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw IllegalStateException("android: missing vpn permission")
        val builder = Builder()
            .setSession("v6assist")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        var has4 = false
        var has6 = false
        val a4 = options.inet4Address
        while (a4.hasNext()) {
            val p = a4.next()
            builder.addAddress(p.address(), p.prefix())
            has4 = true
        }
        val a6 = options.inet6Address
        while (a6.hasNext()) {
            val p = a6.next()
            builder.addAddress(p.address(), p.prefix())
            has6 = true
        }

        if (options.autoRoute) {
            if (has4) builder.addRoute("0.0.0.0", 0)
            if (has6) builder.addRoute("::", 0)
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                if (dns != null) {
                    while (dns.hasNext()) builder.addDnsServer(dns.next())
                }
            }
            val include = options.includePackage
            while (include.hasNext()) {
                val pkg = include.next()
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: Throwable) {
                    Log.w(TAG, "addAllowedApplication $pkg: ${e.message}")
                }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                val pkg = exclude.next()
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Throwable) {
                    Log.w(TAG, "addDisallowedApplication $pkg: ${e.message}")
                }
            }
        }

        val pfd = builder.establish() ?: throw IllegalStateException("android: tun establish failed")
        fileDescriptor = pfd
        Log.i(TAG, "tun established fd=${pfd.fd} v4=$has4 v6=$has6")
        return pfd.fd
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            if (uid == android.os.Process.INVALID_UID) error("android: connection owner not found")
            val owner = ConnectionOwner()
            owner.userId = uid
            val pkgs = packageManager.getPackagesForUid(uid)
            owner.userName = pkgs?.firstOrNull() ?: ""
            return owner
        }
        return error("android: connection owner requires API 29+")
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = getSystemService(ConnectivityManager::class.java)
        val out = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val all = try {
            JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Throwable) {
            emptyList<JavaNetworkInterface>()
        }
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val name = lp.interfaceName ?: continue
            val jni = all.firstOrNull { it.name == name } ?: continue
            val box = io.nekohasekai.libbox.NetworkInterface()
            box.name = name
            box.index = jni.index
            box.mtu = try {
                jni.mtu
            } catch (e: Throwable) {
                1500
            }
            box.dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress }.iterator())
            box.gateway = StringArray(
                lp.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .iterator(),
            )
            box.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            box.addresses = StringArray(jni.interfaceAddresses.map { it.toPrefix() }.iterator())
            var dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (jni.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (jni.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (jni.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            box.flags = dumpFlags
            box.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            out.add(box)
        }
        return InterfaceArray(out.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun registerMyInterface(name: String?) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("android: no root shell")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = throw UnsupportedOperationException("android: no shell session")

    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("android: no user lookup")

    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("android: no ssh host key")

    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("android: no sftp")

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("android: no bridge")

    // ---------- helpers ----------

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    private class InterfaceArray(private val it: Iterator<io.nekohasekai.libbox.NetworkInterface>) :
        NetworkInterfaceIterator {
        override fun hasNext(): Boolean = it.hasNext()

        override fun next(): io.nekohasekai.libbox.NetworkInterface = it.next()
    }

    class StringArray(private val it: Iterator<String>) : StringIterator {
        override fun len(): Int = 0

        override fun hasNext(): Boolean = it.hasNext()

        override fun next(): String = it.next()
    }

    companion object {
        private const val TAG = "V6Service"
        const val ACTION_STOP = "top.lcs.v6assist.STOP"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null

        @Volatile
        var startedAt: Long = 0L
            private set

        @Volatile
        var instance: V6Service? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, V6Service::class.java))
        }

        fun stop(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.mainHandler.post { svc.shutdown() }
            } else {
                running = false
                ctx.stopService(Intent(ctx, V6Service::class.java))
            }
        }

        /** 从 assets 模板生成 sing-box 配置。 */
        fun buildConfig(ctx: Context): String {
            val tpl = ctx.assets.open("v6config.json").bufferedReader().use { it.readText() }
            val pkgs = mutableListOf<String>()
            if (Prefs.includeBili(ctx)) pkgs.add("tv.danmaku.bili")
            if (Prefs.includeDouyin(ctx)) pkgs.add("com.ss.android.ugc.aweme")
            if (pkgs.isEmpty()) pkgs.add("tv.danmaku.bili")
            val pkgJson = pkgs.joinToString(",") { "\"$it\"" }
            return tpl
                .replace("__RELAY_HOST__", Prefs.relayHost(ctx))
                .replace("__RELAY_PORT__", Prefs.relayPort(ctx).toString())
                .replace("__DNS_PORT__", Prefs.dnsPort(ctx).toString())
                .replace("__INCLUDE_PACKAGES__", "[$pkgJson]")
        }
    }
}
