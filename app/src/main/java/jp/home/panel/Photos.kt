package jp.home.panel

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Google フォトの「共有アルバム」のリンク（https://photos.app.goo.gl/… など）から写真の URL を取り出す。
 * 2025年3月以降、公式 API では既存のアルバムを読めなくなったため、共有ページの中身から取り出している（非公式）。
 * Google 側のページの作りが変わると読めなくなる可能性がある。
 */
fun fetchAlbum(http: OkHttpClient, url: String): List<String> {
    val req = Request.Builder()
        .url(url.trim())
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
        .header("Accept-Language", "ja,en;q=0.8")
        .build()
    val html = http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        r.body?.string().orEmpty()
    }
    return extractPhotoUrls(html)
}

/** ページ内の ["https://lh3.googleusercontent.com/pw/…",幅,高さ …] を拾う（アイコン類は除く） */
fun extractPhotoUrls(html: String): List<String> {
    fun find(pattern: String) = Regex(pattern).findAll(html)
        .filter { (it.groupValues[2].toIntOrNull() ?: 0) >= 300 && (it.groupValues[3].toIntOrNull() ?: 0) >= 300 }
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    // 共有アルバムの写真は /pw/ で始まる。見つからなければ形の近いものも探す
    return find("""\["(https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]+)",(\d+),(\d+)""")
        .ifEmpty { find("""\["(https://lh3\.googleusercontent\.com/[A-Za-z0-9_\-]{30,})",(\d+),(\d+)""") }
}

/** 画面の大きさに合わせた写真の URL */
fun photoUrl(base: String, w: Int, h: Int) = "$base=w$w-h$h"
