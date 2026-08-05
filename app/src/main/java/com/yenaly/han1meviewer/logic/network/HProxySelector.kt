package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.ech.EchProxyManager
import okhttp3.internal.proxy.NullProxySelector
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 受 [EhViewer_CN_SXJ 中 EhProxySelector](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/blob/BiLi_PC_Gamer/app/src/main/java/com/hippo/ehviewer/EhProxySelector.java)
 * 的启发，Han1meViewer 也将使用 [HProxySelector] 来实现代理功能。
 *
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/10/07 007 17:32
 */
// #issue-15: 添加系统代理功能
class HProxySelector : ProxySelector() {

    private var delegation: ProxySelector? = null
    private val alternative: ProxySelector = getDefault() ?: NullProxySelector

    init {
        updateProxy()
    }

    companion object {
        const val TYPE_DIRECT = 0
        const val TYPE_SYSTEM = 1
        const val TYPE_HTTP = 2
        const val TYPE_SOCKS = 3

        /** ECH 未就绪时 select() 阻塞等待的最长时间。 */
        private const val WAIT_ECH_TIMEOUT_MS = 10_000L

        private val ipv4Regex =
            Regex("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$")

        fun validateIp(ip: String): Boolean {
            return ipv4Regex.matches(ip)
        }

        fun validatePort(port: Int): Boolean {
            return port in 0..65535
        }

        // #issue-39: 代理沒有應用到 WebView 上，只能通過此種方式來全局代理。
        fun rebuildNetwork() {
            val properties = System.getProperties()
            // WebView uses these system properties. While ECH is ready, it must
            // use the same loopback CONNECT proxy as the OkHttp clients.
            val echPort = EchProxyManager.port
            if (echPort > 0) {
                properties["proxySet"] = true.toString()
                properties["proxyHost"] = "127.0.0.1"
                properties["proxyPort"] = echPort.toString()
                return
            }
            when (Preferences.proxyType) {
                TYPE_HTTP, TYPE_SOCKS -> {
                    properties["proxySet"] = true.toString()
                    properties["proxyHost"] = Preferences.proxyIp
                    properties["proxyPort"] = Preferences.proxyPort.toString()
                }

                else -> {
                    properties["proxySet"] = false.toString()
                    properties["proxyHost"] = ""
                    properties["proxyPort"] = ""
                }
            }
        }
    }

    private fun updateProxy() {
        delegation = when (Preferences.proxyType) {
            TYPE_DIRECT -> NullProxySelector
            TYPE_SYSTEM -> alternative
            TYPE_HTTP, TYPE_SOCKS -> null
            else -> NullProxySelector
        }
    }

    override fun select(uri: URI?): MutableList<Proxy> {
        // 所有网络连接强制走 ECH：ECH 未就绪时阻塞等待，就绪后返回本机代理。
        val echPort = waitForEchPort()
        if (echPort > 0 && uri?.host != null) {
            return mutableListOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", echPort))
            )
        }

        val type = Preferences.proxyType
        if (type == TYPE_HTTP || type == TYPE_SOCKS) {
            val ip = Preferences.proxyIp
            val port = Preferences.proxyPort
            if (ip.isNotBlank() && port != -1) {
                val inetAddress = InetAddress.getByName(ip)
                val socketAddress = InetSocketAddress(inetAddress, port)
                return mutableListOf(
                    Proxy(
                        if (type == TYPE_HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                        socketAddress
                    )
                )
            }
        }

        return delegation?.select(uri) ?: alternative.select(uri)
    }

    /** 阻塞等待 ECH 代理就绪，最多 [WAIT_ECH_TIMEOUT_MS]，避免代理未启动时请求直连被墙。 */
    private fun waitForEchPort(): Int {
        val deadline = System.currentTimeMillis() + WAIT_ECH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val port = EchProxyManager.port
            if (port > 0) return port
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        return EchProxyManager.port
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegation?.select(uri)
    }
}