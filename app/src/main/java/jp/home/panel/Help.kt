@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
            "画面のいちばん上で下に引っぱると、機器の状態・電気・予定などを読み直します。",
            "左上の表示で接続状態がわかります：🏠＝家のWi-Fi、🌏＝外出先（Home Assistant Cloud 経由）。",
            "時刻はすべて日本時間で表示し、1日の切り替わりも日本時間の0時です。",
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
            "「近くの〜」「営業時間」など場所に関わる質問は、⚙ 設定 →「音声と AI」の「自宅の位置」に登録した場所を基準に答えます。",
        ),
    ),
    HelpSection(
        "🗣️ 呼びかけで反応",
        listOf(
            "⚙ 設定 →「音声と AI」の「呼びかけで反応する」をオンにすると、呼びかけるだけで聞き取りを始めます（ピッと鳴ります）。",
            "呼びかけの言葉は Home Assistant の音声アシスタント設定で決めます。",
            "家の中で、この画面を表示している間だけ待ち受けます。",
        ),
    ),
    HelpSection(
        "⚙ 表示する機器を選ぶ",
        listOf(
            "⚙ 設定 →「部屋と機器」の「表示する機器」で、チェックを付けた機器だけをホーム画面に表示します。",
            "名前で絞り込んで探せます。",
        ),
    ),
    HelpSection(
        "📅 予定（TimeTree）",
        listOf(
            "Home Assistant に取り込んだ TimeTree の予定を、ホーム画面に「今日・明日」で表示します。タップすると1週間分を表示します。",
            "音声で「今日の予定は？」「明日の予定」と聞くと読み上げます。",
            "ホームパネルのサーバーが30分ごとに TimeTree から予定を取り出し、Home Assistant の「Remote Calendar」で読み込みます。設定は ⚙ 設定 →「カレンダー（TimeTree）」の手順のとおりです。",
            "TimeTree のメールアドレスとパスワードはサーバーの中にだけ保存し、このアプリには保存しません。",
        ),
    ),
    HelpSection(
        "📡 ECHONET Lite（直接つなぐ）",
        listOf(
            "⚙ 設定 →「ECHONET Lite」をオンにすると、家の Wi-Fi の中の ECHONET Lite 対応機器（エアコン・照明・電動カーテンなど）を探して、Home Assistant を通さずに操作できます。",
            "見つかった機器は、ホーム画面の機器の一覧に一緒に並びます。見つからないときは IP アドレスで追加できます。",
            "スマートメーター・太陽光・蓄電池が見つかると、電気のカードに「いま」の電力を表示します。",
        ),
    ),
    HelpSection(
        "⚡ 電気の収支",
        listOf(
            "Home Assistant の「エネルギー」の設定（買電・売電・太陽光・蓄電池）をもとに、今日の使用量・買電・売電・発電・自給率を表示します。",
            "タップすると、今日・昨日・今月の比較、時間ごとの使用量、よく使っている機器を表示します。",
            "表示されないときは、Home Assistant の「設定 → ダッシュボード → エネルギー」を設定してください。",
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
            "⚙ 設定 →「部屋と機器」で、アプリだけの部屋を追加できます。▲▼ で部屋の並び順を変えられます。",
            "同じ画面の「表示する機器」の各機器の「🏠」ボタンで、その機器の部屋を変えられます。",
        ),
    ),
    HelpSection(
        "☀ おひさま沸き上げ（エコキュート）",
        listOf(
            "太陽光で余った電気で、エコキュートのお湯を沸かします（⚙ 設定 →「おひさま沸き上げ」）。",
            "毎日決まった時刻（初期値20時）に翌日の天気予報を見て、晴れなら夜の沸き上げを昼にずらします。設定後に読み直して確かめ、失敗したら10分ごとに自動でやり直します。「もう一度設定」で手動でもやり直せます。",
            "昼は売電の量をリアルタイムに見て（スマートメーターがあれば15秒ごと）、余っていれば沸き増しを始め、電気を買い始めたら自動に戻します。",
            "サーバーで動かすときは「夜の沸き上げの減らし方」で「夜は止める」を選べます。晴れの予報の夜は沸き上げを止め、昼に余った電気で沸かします（お湯が減りすぎたら止めるのをやめ、昼の終わりの2時間前にまだ少なければ沸かします）。",
            "エネルギーシフトを受け付けない機種では、晴れの予報の夜だけタンクの運転モードを「節約」にして夜の沸き上げを減らし、昼の時間帯のあと「標準」に戻します（残りのお湯が4割より少ないときは減らしません）。",
            "「夜は止める」では、晴れの日の夜に沸かす量（何%まで沸かしたら止めるか）を選べます。リットルでも表示します。下に直近7日の使ったお湯の量が出るので、参考にしてください。",
            "「🩺 対応状況を調べる」で、エコキュートが外から変えられる項目と、夜の沸き上げを減らす方法が分かります。",
            "エコキュートとは ECHONET Lite で直接やりとりします。先に ⚙ 設定 →「ECHONET Lite」でエコキュートを見つけてください。",
            "ホーム画面の電気のカードの下に、残りのお湯・沸き上げの状態と、24時間のお湯のグラフ（残りの量と、1時間ごとに使った量）が出ます。「今すぐ沸き増し」で手動でも沸かせます。",
        ),
    ),
    HelpSection(
        "🏠 家の中は ECHONET Lite、外出先は Home Assistant",
        listOf(
            "⚙ 設定 →「ECHONET Lite」で、機器ごとに「🔗 連携」から Home Assistant の同じ機器を選ぶと（「自動で連携」もあります）、ホーム画面では1つにまとまります。",
            "連携した機器は、家の中では ECHONET Lite で直接、外出先では Home Assistant 経由で操作します。ECHONET Lite で失敗したときは Home Assistant で操作し直します。",
            "ホーム画面の上部の「操作：…」で、自動／いつも ECHONET Lite／いつも Home Assistant を切り替えられます。直接操作している機器には「🏠 直接」と出ます。",
        ),
    ),
    HelpSection(
        "❄ エアコンの自動温度調整",
        listOf(
            "⚙ 設定 →「エアコンの自動温度調整」で、エアコンごとに温度の範囲（下限〜上限）を決めると、範囲を外れたときに自動で冷房・暖房にします。",
            "温度のもとは、エアコンの室温か、部屋に置いた温度計（SwitchBot など）から選べます。",
            "手でエアコンを操作したときは、10分間はその設定を優先します。",
            "動かす場所は「ホームパネルのサーバー（おすすめ）」と「このタブレット」から選べます。サーバーなら、タブレットの電源が切れていても動き続けます（最初に「サーバーに鍵を登録」を押してください）。",
            "ホーム画面の上部の「❄ 自動調整」で、全体をすぐにオン/オフできます。エアコンの名前をタップすると、いまの様子・24時間の温度・自動で行った操作を見られます。",
        ),
    ),
    HelpSection(
        "📍 家からの距離で自動操作",
        listOf(
            "トップの「⏰ 自動化」→ オートメーションの「きっかけ」で「家からの距離」を選ぶと、「家から1km以内に近づいたらエアコンをつける」「5kmより遠くへ離れたら照明を消す」のように作れます。",
            "スマートフォンに Home Assistant アプリを入れて位置を送っている人が対象です。Home Assistant で動くので、このタブレットを切っていても動きます。",
        ),
    ),
    HelpSection(
        "📊 電気の分析",
        listOf(
            "電気のカードの詳細、またはエコキュートの欄の「📊 電気の分析」で、時間ごと・回路ごとの電気の使い方を見られます（今日／昨日／7日間／30日間）。",
            "ホームパネルのサーバーが AiSEG2 から1分ごとに記録しています（⚙ 設定 →「おひさま沸き上げ」で AiSEG2 を登録）。",
            "「日ごと（天気・気温）」では、日ごとの電気と一緒に、その日の天気・最高/最低気温・日照時間・雨・お湯の量を表示します（サーバーに保存）。",
            "「回路の比較」では、選んだ回路（最大6つ）を1つのグラフに重ねて比べられます。",
            "日の切り替わりは日本時間の0時です。",
            "「5分ごと（回路別）」では、その日の5分ごとの電気（5分間の平均の電力）を、回路を選んで重ねて見られます（8日分）。",
            "AiSEG2 の回路にエコキュートが無いときは、エコキュートから直接消費電力を読んで「エコキュート（直接）」として記録します（対応している機種のみ）。⚙ 設定 →「おひさま沸き上げ」の「AiSEG2 の回路の一覧を確かめる」で、読めている回路が分かります。",
        ),
    ),
    HelpSection(
        "💴 電気料金",
        listOf(
            "⚙ 設定 →「電気料金」で電力会社とプランを選ぶと、買った電気に時間帯・季節・休日ごとの単価をかけて電気代を計算します。再エネ賦課金と国の補助も自動で入れます。",
            "契約の大きさ（A・kVA・kW）、燃料費調整額（検針票の値）、売電の単価も入れられます。一覧に無いプランは「その他（単価を自分で入力）」で入れます。",
            "ホーム画面に今日・今月・今月の見込みの電気代と、いまの単価を出します。電気の分析にも日ごと・時間ごとの電気代が出ます。",
            "単価表はサーバーが毎日新しくします。実際の請求額とは、割引や端数の扱いで少しずれることがあります。",
        ),
    ),
    HelpSection(
        "📷 カメラ",
        listOf(
            "Home Assistant に登録したカメラの映像を、ホーム画面のタイルにそのまま動画で表示します（音は出しません）。⚙ 設定 →「カメラ」で、10秒ごとの静止画にも変えられます。",
            "カメラをタップすると、全画面で動画を表示します。「✕ 閉じる」か戻るボタンで閉じます。",
            "全画面の右上の「画質」で、方式を選べます（カメラごとに覚えます）。",
            "・自動：使える中でいちばん遅れの少ない方式　・リアルタイム：遅れ1秒未満（WebRTC）",
            "・高画質：なめらか・数秒の遅れ（HLS）　・簡易：どのカメラでも見られるがコマ数少なめ",
            "選んだ方式で再生できないときは、自動でほかの方式に切り替わります。",
            "⚙ 設定 →「カメラ（直接つなぐ）」で、Home Assistant を通さずにネットワークカメラ（RTSP）を追加できます。画質は「高画質／軽量」から選べます。",
            "「🔍 カメラを探す」を押すと、家のネットワークの中のカメラ（ONVIF 対応・RTSP のポートが開いている機器）を探して、そのまま登録できます。",
            "表示されないときは、⚙ 設定 →「部屋と機器」の「表示する機器」で、カメラにチェックを付けてください。",
        ),
    ),
    HelpSection(
        "🎞 カメラの記録と動きの分析",
        listOf(
            "⚙ 設定 →「カメラ（直接つなぐ）」の「サーバーで記録・分析」でカメラを選ぶと、ホームパネルのサーバーが1分ごとに画像を保存し（2日分）、動きの量を記録します。",
            "カメラの全画面の「🎞 記録」または設定画面の「動きを見る」で、48時間の動きのグラフと、その時刻の画像を見られます。",
            "夜に動きがなくなった時刻を「寝た時刻」、朝に動き始めた時刻を「起きた時刻」として推定します（子ども部屋などで、何時に寝たかの目安になります）。",
            "画像はサーバーの中だけに保存し、2日たつと自動で消します。",
        ),
    ),
    HelpSection(
        "🌡 温度計",
        listOf(
            "Home Assistant に取り込んだ温度計（SwitchBot 温湿度計など）の温度と湿度をホーム画面に表示します。",
            "温度計をタップすると、温度と湿度の変化のグラフを表示します（24時間／3日／7日）。",
            "⚙ 設定 →「温度計」の「✎ 名前」で、部屋の名前などに変えられます。表示しない温度計もここで選べます。",
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
            "⚙ 設定 →「アカウント」で、このアプリが使っているアカウント（普段使い・管理者・サーバーの鍵・AiSEG2・カメラ）と、Home Assistant のユーザーの一覧（管理者ログインが必要）を見られます。パスワードは表示しません。",
        ),
    ),
    HelpSection(
        "📜 操作の記録",
        listOf(
            "⚙ 設定 →「操作の記録」で、だれが・いつ・何を操作したかを見られます（Home Assistant の記録から。24時間／3日／7日）。",
            "人の名前は Home Assistant のユーザーです。自動化やサーバーが動かしたものは「自動」「サーバー」と表示します。",
            "おひさま沸き上げ・エアコンの自動温度調整がサーバーで行った操作も一緒に表示します。",
        ),
    ),
    HelpSection(
        "🎨 配色",
        listOf("⚙ 設定 →「ホーム画面の表示」の「配色」で、11種類の色の組み合わせから選べます（夜に見やすい暗めの配色や、明るい配色もあります）。"),
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
            "呼びかけに反応しない：⚙ 設定 →「音声と AI」の「マイクのテスト」と「音声の動作ログ」で、音が入っているか・どこで止まっているかを確認できます。",
            "回答の読み上げ中でも、マイクボタンを押すと読み上げを止めて次の質問を聞きます。",
            "ログインし直したいとき：⚙ 設定 →「アカウント」→ ログアウト。",
        ),
    ),
)

@Composable
fun HelpScreen(vm: MainViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val q = query.trim()
    val shown = if (q.isEmpty()) HELP else HELP.mapNotNull { s ->
        if (s.title.contains(q, ignoreCase = true)) s
        else s.items.filter { it.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { HelpSection(s.title, it) }
    }
    // 目次の後ろに並ぶ見出しの位置（0：見出し、1：検索、2：目次）
    val head = 3
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← 戻る") }
                Text("使い方", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                label = { Text("🔍 言葉で探す（例：エコキュート、カメラ、ログイン）") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (q.isEmpty()) "目次（タップでその項目へ）" else "見つかった項目：${shown.size}件", style = MaterialTheme.typography.titleSmall)
                    if (shown.isEmpty()) Text("「$q」を含む項目はありません", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        shown.forEachIndexed { i, s ->
                            AssistChip(onClick = { scope.launch { list.animateScrollToItem(head + i) } }, label = { Text(s.title) })
                        }
                    }
                }
            }
        }
        itemsIndexed(shown, key = { _, s -> s.title }) { _, s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) { Text("↑ 目次") }
                    }
                    s.items.forEach { Text("・$it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        item { UpdateCard(vm, showWhenIdle = true) }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Button(onClick = onClose) { Text("閉じる") } }
        }
        item { Spacer(Modifier.size(24.dp)) }
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
