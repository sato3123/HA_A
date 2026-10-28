package jp.home.panel

import android.app.Application
import android.content.Context
import java.io.File

/**
 * アプリが止まったときに原因（エラーの内容）を端末に残し、次に起動したときに画面に表示する。
 * パソコンにつながなくても、止まった理由を確認できるようにするため。
 */
class HomePanelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 時刻はすべて日本時間で表示し、1日の切り替わりも日本時間の0:00にする
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("時刻：").append(java.time.LocalDateTime.now()).append('\n')
                    append("バージョン：").append(BuildConfig.VERSION_NAME).append('\n')
                    append("端末：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                        .append("（Android ").append(android.os.Build.VERSION.RELEASE).append("）\n\n")
                    append(error.stackTraceToString().take(6000))
                }
                CrashLog.file(this).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {
    fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")
    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }
}
