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
import java.net.UnknownHostException

/** 「ホームネットワーク内からのみ動作」を担保するチェック */
object NetworkGuard {

    /** 問題なければ null、使えない場合はその理由を返す */
    suspend fun check(context: Context, baseUrl: String): String? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return@withContext "ネットワークに接続されていません"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return@withContext "VPN接続中は使えません（家のWi-Fiに直接つないでください）"
        }
        val onLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onLan) return@withContext "家のWi-Fiに接続されていません"

        val host = Uri.parse(baseUrl).host ?: return@withContext "サーバーのURLが正しくありません"
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            return@withContext "サーバー「$host」が見つかりません"
        }
        if (addrs.isEmpty() || !addrs.all(::isLocal)) {
            "サーバーが家の外のアドレスです（このアプリは家の中専用です）"
        } else {
            null
        }
    }

    fun isLocal(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            // IPv6 ユニークローカル fc00::/7
            (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)
}

/** 通信ライブラリ側でも、家の中のアドレス以外には接続させない */
object LocalOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val local = Dns.SYSTEM.lookup(hostname).filter(NetworkGuard::isLocal)
        if (local.isEmpty()) throw UnknownHostException("家の外のアドレスへの接続はブロックされました: $hostname")
        return local
    }
}
