package jp.home.panel

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MfaRequiredException : Exception("2段階認証のコードを入力してください")
class RefreshRevokedException : Exception("ログイン情報が無効になりました。もう一度ログインしてください")

data class Tokens(val access: String, val refresh: String, val accessExpiresAt: Long)

/**
 * 端末内に暗号化して保存するのは「サーバーURL」と「リフレッシュトークン」だけ。
 * パスワードは保存しない。リフレッシュトークンが生きている限り再ログイン不要。
 */
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String?
        get() = prefs.getString("base_url", null)
        set(v) = prefs.edit().putString("base_url", v).apply()
    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()
    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()
    var accessExpiresAt: Long
        get() = prefs.getLong("access_expires_at", 0L)
        set(v) = prefs.edit().putLong("access_expires_at", v).apply()

    fun save(base: String, t: Tokens) {
        baseUrl = base
        refreshToken = t.refresh
        accessToken = t.access
        accessExpiresAt = t.accessExpiresAt
    }

    /** URLは残し、トークンだけ消す */
    fun clearTokens() {
        prefs.edit().remove("refresh_token").remove("access_token").remove("access_expires_at").apply()
    }
}

/** Home Assistant 標準の認証API（Web画面のログインと同じ仕組み）を直接呼ぶ */
class AuthApi(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    // client_id は HA 自身のURL。リダイレクト先も同じホストなので追加登録は不要。
    private fun clientId(base: String) = "$base/"
    private fun redirectUri(base: String) = "$base/?auth_callback=1"

    suspend fun login(base: String, user: String, pass: String, otp: String?): Tokens =
        withContext(Dispatchers.IO) {
            val start = postJson(
                "$base/auth/login_flow",
                JSONObject()
                    .put("client_id", clientId(base))
                    .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
                    .put("redirect_uri", redirectUri(base)),
            )
            val flowId = start.getString("flow_id")

            var step = postJson(
                "$base/auth/login_flow/$flowId",
                JSONObject().put("client_id", clientId(base)).put("username", user).put("password", pass),
            )
            if (step.optString("type") == "form" && step.optString("step_id") == "mfa") {
                if (otp.isNullOrBlank()) throw MfaRequiredException()
                step = postJson(
                    "$base/auth/login_flow/$flowId",
                    JSONObject().put("client_id", clientId(base)).put("code", otp),
                )
            }
            if (step.optString("type") != "create_entry") {
                val errors = step.optJSONObject("errors")
                throw IOException(
                    when {
                        errors?.optString("base") == "invalid_code" -> "2段階認証のコードが違います"
                        errors?.has("base") == true -> "ユーザー名またはパスワードが違います"
                        else -> "ログインに失敗しました"
                    }
                )
            }

            val tok = postForm(
                "$base/auth/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to step.getString("result"),
                    "client_id" to clientId(base),
                ),
            )
            Tokens(
                access = tok.getString("access_token"),
                refresh = tok.getString("refresh_token"),
                accessExpiresAt = System.currentTimeMillis() + tok.getLong("expires_in") * 1000,
            )
        }

    /** 戻り値: アクセストークンと有効期限 */
    suspend fun refresh(base: String, refreshToken: String): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId(base))
                .build()
            http.newCall(Request.Builder().url("$base/auth/token").post(body).build()).execute().use { resp ->
                // 400 = トークン失効、403 = ユーザー無効化。どちらも再ログインが必要
                if (resp.code == 400 || resp.code == 403) throw RefreshRevokedException()
                if (!resp.isSuccessful) throw IOException("トークン更新に失敗しました (HTTP ${resp.code})")
                val j = JSONObject(resp.body?.string().orEmpty())
                j.getString("access_token") to (System.currentTimeMillis() + j.getLong("expires_in") * 1000)
            }
        }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("サーバーエラー (HTTP ${resp.code})")
            return JSONObject(text)
        }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("トークン取得に失敗しました (HTTP ${resp.code})")
            return JSONObject(text)
        }
    }
}
