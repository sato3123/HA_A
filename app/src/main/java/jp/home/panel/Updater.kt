package jp.home.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String, val code: Int, val apkUrl: String, val notes: String, val auth: String? = null, val from: String = "GitHub",
    /** APK の SHA-256（16進・小文字）。メタデータに書かれていれば、ダウンロード後に照らし合わせる */
    val sha256: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class NeedPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * 新しい APK があればダウンロード → インストール画面を開く。
 * 1. 家のサーバー（http://サーバー/android/）を先に見る。Home Assistant にログインしたアプリだけが取れる
 *    （リポジトリを非公開にしても、家族のタブレットは家の中で更新できる）
 * 2. 届かなければ GitHub の最新リリース（公開のときだけ）
 */
class Updater(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 新しいバージョンがあれば返す。最新なら null。server：家のサーバー（http://…）、token：Home Assistant のトークン */
    suspend fun check(server: String? = null, token: String? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        var lanError: Exception? = null
        if (!server.isNullOrBlank() && token != null) {
            try {
                return@withContext checkLan(server.trimEnd('/'), token)
            } catch (e: Exception) {
                lanError = e
            }
        }
        try {
            checkGitHub()
        } catch (e: IOException) {
            // GitHub が非公開などで読めないときは、家のサーバーの理由を見せる
            throw lanError?.let { IOException("家のサーバーから更新を確認できませんでした（${it.message}）") } ?: e
        }
    }

    private fun checkLan(server: String, token: String): UpdateInfo? {
        val req = Request.Builder().url("$server/android/latest.json").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val j = JSONObject(r.body?.string().orEmpty())
            val tag = j.optString("tag")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: throw IOException("版がわかりません")
            if (code <= BuildConfig.VERSION_CODE) return null
            return UpdateInfo(tag.removePrefix("v"), code, "$server/android/HomePanel.apk", j.optString("notes").trim(), auth = token, from = "家のサーバー",
                sha256 = j.optString("sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) })
        }
    }

    private fun checkGitHub(): UpdateInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return http.newCall(req).execute().use { r ->
            if (r.code == 404) {
                throw IOException("更新情報が見つかりません（リポジトリが非公開か、まだリリースがありません）")
            }
            if (!r.isSuccessful) throw IOException("更新を確認できませんでした (HTTP ${r.code})")
            val j = JSONObject(r.body?.string().orEmpty())
            val tag = j.optString("tag_name") // 例: v1.0.12
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@use null
            val assets = j.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@use null
            if (code <= BuildConfig.VERSION_CODE) return@use null
            UpdateInfo(
                version = tag.removePrefix("v"),
                code = code,
                apkUrl = apk.getString("browser_download_url"),
                notes = j.optString("body").trim(),
                // GitHub はリリースの添付ファイルに "digest": "sha256:…" を付ける
                sha256 = apk.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            )
        }
    }

    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "HomePanel-${info.version}.apk")
        val first = info.apkUrl.toHttpUrlOrNull() ?: throw IOException("更新のアドレスが正しくありません")
        // 家のサーバーから：決まったアドレス（/android/HomePanel.apk）だけ。よそへの転送（リダイレクト）は受け付けない
        // GitHub から：https で、GitHub のサーバーへの転送だけ受け付ける
        val lan = info.auth != null
        if (lan && first.encodedPath != "/android/HomePanel.apk") throw IOException("更新のアドレスが正しくありません")
        if (!lan && !(first.isHttps && githubHost(first.host))) throw IOException("更新のアドレスが正しくありません")
        var url = first
        var resp: okhttp3.Response? = null
        for (hop in 0..5) {
            val req = Request.Builder().url(url).apply { if (lan && hop == 0) info.auth?.let { header("Authorization", "Bearer $it") } }.build()
            val r = http.newCall(req).execute()
            if (!r.isRedirect) { resp = r; break }
            r.close()
            val next = r.header("Location")?.let { url.resolve(it) } ?: throw IOException("ダウンロードに失敗しました（転送先がありません）")
            if (lan || !(next.isHttps && githubHost(next.host))) throw IOException("思わぬ場所（${next.host}）へ転送されたため、ダウンロードをやめました")
            url = next
        }
        (resp ?: throw IOException("転送が多すぎます")).use { r ->
            if (!r.isSuccessful) throw IOException("ダウンロードに失敗しました (HTTP ${r.code})")
            val body = r.body ?: throw IOException("ダウンロードに失敗しました")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
        }
        try {
            verifyApk(out, info)
        } catch (e: IOException) {
            out.delete()
            throw e
        }
        out
    }

    private fun githubHost(h: String) = h == "github.com" || h == "api.github.com" || h.endsWith(".githubusercontent.com")

    /**
     * インストールする前に、ダウンロードした APK を確かめる。
     * ・メタデータに SHA-256 があれば一致すること
     * ・パッケージ名が同じで、版が新しいこと
     * ・署名（証明書）が、いまインストールされているアプリと同じこと（別の人が作った APK を入れない）
     */
    private fun verifyApk(file: File, info: UpdateInfo) {
        info.sha256?.let { want ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != want) throw IOException("ダウンロードした APK が壊れているか、すり替えられています（SHA-256 が合いません）")
        }
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: throw IOException("ダウンロードした APK を読めません")
        if (apk.packageName != context.packageName) throw IOException("別のアプリの APK です")
        if (apk.longVersionCode <= BuildConfig.VERSION_CODE) throw IOException("新しい版の APK ではありません")
        val mine = pm.getPackageInfo(context.packageName, flags)
        fun certs(p: android.content.pm.PackageInfo): Set<String> {
            val si = p.signingInfo ?: return emptySet()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return sigs.orEmpty().map { sig ->
                java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val a = certs(apk); val m = certs(mine)
        // 鍵の入れ替え（ローテーション）を考えて、いまの署名がどちらかの履歴に入っていればよい
        if (a.isEmpty() || m.isEmpty() || (a intersect m).isEmpty()) throw IOException("APK の署名がいまのアプリと違うため、インストールしません")
    }

    /** 「このアプリからのインストールを許可」がオンか */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
