package com.saworks.pblock.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.saworks.pblock.R
import com.saworks.pblock.TimeChangeReceiver
import com.saworks.pblock.core.Events
import com.saworks.pblock.core.DnsMessage
import com.saworks.pblock.core.DomainMatcher
import com.saworks.pblock.core.IpUdp
import com.saworks.pblock.data.AttemptLog
import com.saworks.pblock.data.Lock
import com.saworks.pblock.data.Prefs
import com.saworks.pblock.data.ProtectionMonitor
import com.saworks.pblock.data.ErrorLog
import com.saworks.pblock.ui.MainActivity
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local DNS filter. Only the virtual DNS server address is routed into the tun interface, so all other
 * traffic goes directly to the network. Blocked names get NXDOMAIN; everything else is forwarded over
 * a protected UDP socket to the upstream resolver and the reply is written back.
 */
class PBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "PBlockVpn"
        const val ACTION_START = "com.saworks.pblock.action.START"
        const val ACTION_STOP = "com.saworks.pblock.action.STOP"
        const val TUN_ADDR = "10.111.222.1"
        const val DNS_ADDR = "10.111.222.53"

        /** Cloudflare for Families (malware + adult filter) as primary upstream, then the network's own DNS. */
        val FAMILY_UPSTREAMS = listOf("1.1.1.3", "1.0.0.3")

        @Volatile var running = false
            private set
        @Volatile private var instance: PBlockVpnService? = null

        /** Live values: the user can toggle these in the system VPN settings while we run. */
        val alwaysOn: Boolean get() = try { instance?.isAlwaysOn == true } catch (_: Exception) { false }
        val lockdown: Boolean get() = try { instance?.isLockdownEnabled == true } catch (_: Exception) { false }

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, PBlockVpnService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                ErrorLog.note(ctx, "vpn.start", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, PBlockVpnService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                ErrorLog.note(ctx, "vpn.stop", e)
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    @Volatile private var active = false
    private val writeLock = Any()
    private val preferredUpstream = AtomicInteger(0)
    @Volatile private var upstreams: List<InetAddress> = emptyList()
    @Volatile private var upstreamsAt = 0L
    @Volatile private var upstreamsFamily = true
    private val main = Handler(Looper.getMainLooper())
    private val timeReceiver = TimeChangeReceiver()
    private val monitor = object : Runnable {
        override fun run() {
            try { ProtectionMonitor.check(this@PBlockVpnService) } catch (e: Exception) { ErrorLog.note(this@PBlockVpnService, "vpn.monitor", e) }
            main.postDelayed(this, 60_000)
        }
    }
    private var monitoring = false

    override fun onCreate() {
        super.onCreate()
        TimeChangeReceiver.register(this, timeReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!Lock.isLocked(this)) {
                Prefs.setVpnWanted(this, false)
                stopVpn()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // ACTION_START, a sticky restart (null intent) or an always-on start by the system
        Prefs.setVpnWanted(this, true)
        startVpn()
        return START_STICKY
    }

    @Synchronized
    private fun startVpn() {
        if (tun != null) return
        val configure = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pfd = try {
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                .setBlocking(false)
                .setMetered(false)
                .setConfigureIntent(configure)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            ErrorLog.note(this, "vpn.establish", e)
            null
        }
        if (pfd == null) { // not prepared (no consent) or another VPN took over
            running = false
            stopSelf()
            return
        }
        tun = pfd
        instance = this
        active = true
        running = true
        pool = ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, ArrayBlockingQueue(256), ThreadPoolExecutor.DiscardPolicy())
        worker = Thread({ loop(pfd.fileDescriptor) }, "pblock-tun").also { it.start() }
        if (!monitoring) { monitoring = true; main.post(monitor) }
        Log.i(TAG, "VPN established (alwaysOn=$isAlwaysOn, lockdown=$isLockdownEnabled)")
    }

    @Synchronized
    private fun stopVpn() {
        active = false
        worker?.let { it.interrupt(); try { it.join(1500) } catch (_: InterruptedException) {} }
        worker = null
        pool?.shutdownNow()
        pool = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        running = false
        if (instance === this) instance = null
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked")
        stopVpn()
        ProtectionMonitor.markDown(this, Events.COMP_VPN)
        if (Lock.isLocked(this)) {
            // During the challenge: come back if the consent is still valid (e.g. a manual "Disconnect").
            main.postDelayed({ if (prepare(this) == null) startVpn() }, 1500)
        } else {
            super.onRevoke()
        }
    }

    override fun onDestroy() {
        val wasRunning = running
        stopVpn()
        if (wasRunning) ProtectionMonitor.markDown(this, Events.COMP_VPN)
        main.removeCallbacks(monitor)
        monitoring = false
        TimeChangeReceiver.unregister(this, timeReceiver)
        super.onDestroy()
    }

    private fun loop(fd: FileDescriptor) {
        val matcher = Blocklist.get(this)
        Log.i(TAG, "blocklist loaded: ${matcher.size} domains")
        val buf = ByteArray(32767)
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLIN.toShort()
        }
        while (active) {
            pollFd.revents = 0
            val ready = try {
                Os.poll(arrayOf(pollFd), 1000)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else break
            }
            if (ready == 0) continue
            val rev = pollFd.revents.toInt()
            if (rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else break
            } catch (e: Exception) {
                break
            }
            if (n > 0) {
                try { handlePacket(fd, buf, n, matcher) } catch (e: Exception) { ErrorLog.note(applicationContext, "vpn.packet", e) }
            }
        }
        Log.i(TAG, "tun loop ended")
    }

    private fun handlePacket(fd: FileDescriptor, buf: ByteArray, n: Int, matcher: DomainMatcher) {
        val p = IpUdp.parse(buf, n) ?: return
        if (p.dstPort != 53) return
        val q = DnsMessage.parseQuery(buf, p.payloadOff, p.payloadLen) ?: return
        val rule = Blocklist.rules(this).match(q.name, matcher)
        if (rule != null) {
            AttemptLog.log(this, AttemptLog.KIND_DNS, rule)
            reply(fd, p, DnsMessage.buildBlockedResponse(buf, p.payloadOff, q))
            return
        }
        val payload = buf.copyOfRange(p.payloadOff, p.payloadOff + p.payloadLen)
        pool?.execute { forward(fd, p, payload, q.name) }
    }

    private fun forward(fd: FileDescriptor, p: IpUdp.Packet, payload: ByteArray, name: String) {
        val servers = upstreamList()
        if (servers.isEmpty()) return
        val first = preferredUpstream.get().coerceIn(0, servers.size - 1)
        for (k in servers.indices) {
            val idx = (first + k) % servers.size
            try {
                DatagramSocket().use { s ->
                    if (!protect(s)) return
                    s.soTimeout = 2500
                    s.send(DatagramPacket(payload, payload.size, servers[idx], 53))
                    val rb = ByteArray(4096)
                    val dp = DatagramPacket(rb, rb.size)
                    s.receive(dp)
                    preferredUpstream.set(idx)
                    if (servers[idx].hostAddress in FAMILY_UPSTREAMS && DnsMessage.isNullRouted(rb, 0, dp.length)) {
                        AttemptLog.log(this, AttemptLog.KIND_DNS_FILTER, name)
                    }
                    reply(fd, p, rb.copyOf(dp.length))
                }
                return
            } catch (_: IOException) {
                // timeout / unreachable: try the next upstream
            }
        }
    }

    private fun reply(fd: FileDescriptor, req: IpUdp.Packet, dns: ByteArray) {
        val pkt = IpUdp.buildUdp(req.dstIp, req.srcIp, req.dstPort, req.srcPort, dns)
        synchronized(writeLock) {
            if (!active) return
            try {
                Os.write(fd, pkt, 0, pkt.size)
            } catch (e: Exception) {
                Log.w(TAG, "tun write failed", e)
            }
        }
    }

    /** Family upstreams first (unless switched off in content-filter mode), then the DNS servers of the underlying (non-VPN) networks. Cached for 60 s. */
    @Suppress("DEPRECATION")
    private fun upstreamList(): List<InetAddress> {
        val now = System.currentTimeMillis()
        val family = Prefs.familyDns(this)
        if (upstreams.isNotEmpty() && now - upstreamsAt < 60_000 && family == upstreamsFamily) return upstreams
        val list = ArrayList<InetAddress>()
        if (family) FAMILY_UPSTREAMS.forEach { list += InetAddress.getByName(it) }
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                cm.getLinkProperties(n)?.dnsServers?.forEach { a ->
                    if (a.hostAddress != DNS_ADDR && a !in list) list += a
                }
            }
        } catch (e: Exception) {
            ErrorLog.note(this, "vpn.upstreams", e)
        }
        if (list.all { it.hostAddress in FAMILY_UPSTREAMS }) list += InetAddress.getByName("8.8.8.8")
        upstreams = list
        upstreamsFamily = family
        upstreamsAt = now
        preferredUpstream.set(0) // retry the family filter first after every refresh
        return list
    }
}
