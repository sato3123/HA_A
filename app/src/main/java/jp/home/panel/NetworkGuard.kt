package jp.home.panel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** 接続先。remote = true は Home Assistant Cloud 経由 */
data class Route(val base: String, val remote: Boolean)

class BlockedException(message: String) : Exception(message)

/**
 * 接続先の選び方：
 *  - 家のWi-Fiで家のサーバーに届く → ローカル（http://IP:8123）
 *  - それ以外で外出先用URLが登録済み → Home Assistant Cloud（https://xxxx.ui.nabu.casa）
 *  - どちらも無理 → 使えない理由を表示
 */
object NetworkGuard {

    suspend fun chooseRoute(context: Context, localBase: String, remoteBase: String?): Route =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: throw BlockedException("ネットワークに接続されていません")
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val lan = !vpn && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

            if (lan && isLocalReachable(localBase)) return@withContext Route(localBase, false)
            if (!remoteBase.isNullOrBlank()) return@withContext Route(remoteBase, true)
            throw BlockedException(
                when {
                    vpn -> "VPN接続中は家のサーバーに直接つなげません。"
                    !lan -> "家のWi-Fiに接続されていません。"
                    else -> "家のサーバーに接続できません（家の外にいるか、サーバーが停止しています）。"
                } + "\n外出先でも使うには、設定で Home Assistant Cloud のリモートURLを登録してください。"
            )
        }

    /** 家の中に戻ったかどうか（外出先接続中に定期的に確認する） */
    suspend fun localAvailable(context: Context, localBase: String): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@withContext false
        val lan = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        lan && isLocalReachable(localBase)
    }

    /** ログイン時用：URLが家の中のアドレスか確認。問題なければ null */
    suspend fun checkLocal(context: Context, baseUrl: String): String? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return@withContext "ネットワークに接続されていません"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return@withContext "VPN接続中はログインできません（家のWi-Fiに直接つないでください）"
        }
        val host = Uri.parse(baseUrl).host ?: return@withContext "サーバーのURLが正しくありません"
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            return@withContext "サーバー「$host」が見つかりません"
        }
        if (addrs.isEmpty() || !addrs.all(::isLocal)) {
            "初回ログインは家の中のアドレス（例：http://192.168.1.10:8123）で行ってください"
        } else {
            null
        }
    }

    private fun isLocalReachable(base: String): Boolean {
        val uri = Uri.parse(base)
        val host = uri.host ?: return false
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return false
        if (addrs.isEmpty() || !addrs.all(::isLocal)) return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(addrs[0], port), 2_000) }
            true
        }.getOrDefault(false)
    }

    fun isLocal(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            // IPv6 ユニークローカル fc00::/7
            (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)
}

/**
 * 通信ライブラリ側でも接続先を限定する：
 * 家の中のアドレスと、登録した外出先用URLのホスト以外には接続させない。
 */
class RestrictedDns(private val remoteHost: () -> String?) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val allowedRemote = remoteHost()
        if (allowedRemote != null && hostname.equals(allowedRemote, ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val local = Dns.SYSTEM.lookup(hostname).filter(NetworkGuard::isLocal)
        if (local.isEmpty()) throw UnknownHostException("許可されていない接続先です: $hostname")
        return local
    }
}
