package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class HelpSection(val title: String, val items: List<String>)

private val HELP = listOf(
    HelpSection(
        "🏠 ホーム画面",
        listOf(
            "上部に時計と天気（今日の最高・最低気温と数日分の予報）を表示します。",
            "左上の表示で接続状態がわかります：「家の中」＝家のWi-Fi、「外出先（Cloud）」＝Home Assistant Cloud 経由。",
        ),
    ),
    HelpSection(
        "💡 機器の操作",
        listOf(
            "スイッチ・シーンは、タイルをタップすると切り替え／実行します。",
            "照明・ファンは右上のスイッチでオン/オフ、スライダーで明るさ・風量を変えられます。",
            "エアコンはタイルで設定温度（−／＋）と運転モードを直接変えられます。",
            "タイルの名前の部分をタップすると詳細パネルが開きます（エアコンの風量・風向き、照明の色など）。",
        ),
    ),
    HelpSection(
        "🎤 音声で操作・質問",
        listOf(
            "マイクボタンを押して話しかけます。話し終わると自動で止まります（■ボタンでも止められます）。",
            "例：「リビングの電気をつけて」「エアコンを26度にして」「近くのスーパーの営業時間は？」",
            "質問には AI が答えて読み上げます。5分以内なら「それは何時まで？」のように続けて聞けます。",
            "聞き取った言葉と回答は、画面の下にポップアップで表示されます（しばらくすると自動で閉じます。タップでも閉じます）。",
            "「近くの〜」「営業時間」など場所に関わる質問は、⚙ 設定 →「自宅の位置」に登録した場所を基準に答えます。",
        ),
    ),
    HelpSection(
        "🗣️ 呼びかけで反応",
        listOf(
            "⚙ 設定 →「呼びかけで反応する」をオンにすると、呼びかけるだけで聞き取りを始めます（ピッと鳴ります）。",
            "呼びかけの言葉は Home Assistant の音声アシスタント設定で決めます。",
            "家の中で、この画面を表示している間だけ待ち受けます。",
        ),
    ),
    HelpSection(
        "⚙ 表示する機器を選ぶ",
        listOf(
            "⚙ 設定 →「表示する機器」で、チェックを付けた機器だけをホーム画面に表示します。",
            "名前で絞り込んで探せます。",
        ),
    ),
    HelpSection(
        "📅 予定（TimeTree）",
        listOf(
            "Home Assistant に取り込んだ TimeTree の予定を、ホーム画面に「今日・明日」で表示します。タップすると1週間分を表示します。",
            "音声で「今日の予定は？」「明日の予定」と聞くと読み上げます。",
            "設定は ⚙ 設定 →「カレンダー（TimeTree）」。TimeTree のログイン情報は Home Assistant にだけ登録し、このアプリには保存しません。",
        ),
    ),
    HelpSection(
        "⏱ タイマー",
        listOf(
            "トップの「⏱ タイマー」から、いくつでも同時に設定できます。残り時間はホーム画面の上に表示されます。",
            "音声でも「5分のタイマー」「パスタの3分タイマー」「タイマーの残りは？」「タイマーを止めて」と言えます。",
            "時間になるとアラームが鳴り、画面に「止める」ボタンが出ます。",
        ),
    ),
    HelpSection(
        "🖼 スクリーンセーバー",
        listOf(
            "⚙ 設定 →「スクリーンセーバー」でオンにすると、操作していない時間が続いたときに写真と時計を表示します。",
            "Google フォトのアルバムを「共有」→「リンクを作成」して、そのリンクを貼り付けてください。画面に触れると元に戻ります。",
        ),
    ),
    HelpSection(
        "🚪 部屋ごとに分ける",
        listOf(
            "機器は Home Assistant のエリアごとに分けて表示されます。上の「すべて／リビング／寝室…」で部屋を絞り込めます。",
            "⚙ 設定 →「部屋」で、アプリだけの部屋を追加できます。",
            "⚙ 設定 →「表示する機器」の各機器の「🏠」ボタンで、その機器の部屋を変えられます。",
        ),
    ),
    HelpSection(
        "⏰ スケジュール・オートメーション",
        listOf(
            "トップの「⏰ 自動化」を開きます。",
            "スケジュール：「平日の7:00にエアコンを冷房にする」のように、時刻と曜日で自動実行します。",
            "オートメーション：「人感センサーが反応したら照明をつける」「日の入りの10分前にカーテンを閉める」のように、きっかけに合わせて動かします。時間帯で絞ることもできます。",
            "Home Assistant に保存されるので、タブレットの電源が切れていても実行されます。",
            "一覧のスイッチでオン/オフ、「今すぐ実行」で試せます。",
            "作成・編集には管理者の権限が必要です（下の「アカウント」）。",
        ),
    ),
    HelpSection(
        "👤 アカウント（管理者とその他）",
        listOf(
            "普段は管理者以外のユーザーで使うのがおすすめです（設定を壊される心配がありません）。",
            "自動化を作成・編集するときだけ、⚙ 設定 →「アカウント」で管理者ログインを追加します。",
            "使い終わったら「管理者ログインを解除」できます。",
        ),
    ),
    HelpSection(
        "🎨 配色",
        listOf("⚙ 設定 →「配色」で、11種類の色の組み合わせから選べます（夜に見やすい暗めの配色や、明るい配色もあります）。"),
    ),
    HelpSection(
        "🌐 外出先で使う",
        listOf(
            "⚙ 設定 →「外出先からの接続」に Home Assistant Cloud のリモートURLを登録すると、家の外でも使えます。",
            "家に戻ると自動で家の中の接続に切り替わります。",
        ),
    ),
    HelpSection(
        "⬆️ アプリの更新",
        listOf(
            "新しいバージョンが出ると、ホーム画面の上に「更新する」ボタンが表示されます。",
            "初回だけ「このアプリからのインストールを許可」をオンにする画面が出ます。オンにして戻り、もう一度「更新する」を押してください。",
            "ここの「更新を確認」ボタンでも確認できます。",
        ),
    ),
    HelpSection(
        "❓ 困ったとき",
        listOf(
            "接続できない：家のWi-Fiにつながっているか、Home Assistant が動いているか確認してください。",
            "「マイクを使えません」：Fire の「設定 → Alexa → ハンズフリーモード」をオフにしてください。",
            "「呼びかけ検知が設定されていません」：Home Assistant の音声アシスタントでウェイクワードを設定してください。",
            "呼びかけに反応しない：⚙ 設定 →「音声」の「マイクのテスト」と「音声の動作ログ」で、音が入っているか・どこで止まっているかを確認できます。",
            "回答の読み上げ中でも、マイクボタンを押すと読み上げを止めて次の質問を聞きます。",
            "ログインし直したいとき：⚙ 設定 → ログアウト。",
        ),
    ),
)

@Composable
fun HelpScreen(vm: MainViewModel, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← 戻る") }
            Text("使い方", style = MaterialTheme.typography.titleLarge)
        }
        HELP.forEach { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    s.items.forEach { Text("・$it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        UpdateCard(vm, showWhenIdle = true)
        Button(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("閉じる") }
        Spacer(Modifier.size(24.dp))
    }
}

/**
 * 更新の案内。showWhenIdle = false（ホーム画面）のときは、新しいバージョンがあるときだけ出す。
 */
@Composable
fun UpdateCard(vm: MainViewModel, showWhenIdle: Boolean) {
    val state by vm.update.collectAsState()
    val visible = showWhenIdle || state is UpdateState.Available || state is UpdateState.NeedPermission ||
        state is UpdateState.Downloading || state is UpdateState.Installing ||
        (state is UpdateState.Failed && (state as UpdateState.Failed).info != null)
    if (!visible) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                UpdateState.Idle -> {
                    Text("アプリのバージョン：${vm.versionName}", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.checkUpdate() }) { Text("更新を確認") }
                }
                UpdateState.Checking -> Text("更新を確認しています…")
                UpdateState.UpToDate -> {
                    Text("最新のバージョンです（${vm.versionName}）", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.checkUpdate() }) { Text("もう一度確認") }
                }
                is UpdateState.Available -> {
                    Text("⬆️ 新しいバージョン ${s.info.version} があります（今は ${vm.versionName}）", style = MaterialTheme.typography.titleMedium)
                    if (s.info.notes.isNotBlank()) {
                        Text(s.info.notes.lines().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = vm::installUpdate) { Text("更新する") }
                }
                is UpdateState.NeedPermission -> {
                    Text(
                        "開いた画面で「このアプリからのインストールを許可」をオンにして戻り、もう一度「更新する」を押してください。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = vm::installUpdate) { Text("更新する") }
                }
                is UpdateState.Downloading -> {
                    Text("ダウンロードしています… ${(s.progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> {
                    Text("インストール画面で「インストール」を押してください。終わるとアプリが再起動します。")
                    TextButton(onClick = vm::installUpdate) { Text("インストール画面をもう一度開く") }
                }
                is UpdateState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.info != null) {
                        Button(onClick = vm::installUpdate) { Text("もう一度試す") }
                    } else {
                        OutlinedButton(onClick = { vm.checkUpdate() }) { Text("もう一度確認") }
                    }
                }
            }
        }
    }
}
