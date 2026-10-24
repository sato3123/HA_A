# ホームパネル（Fire HD 8 用 Home Assistant 操作アプリ）

Proxmox 上の Home Assistant OS（HAOS）を操作する専用 Android アプリです。

| 条件 | 実現方法 |
|---|---|
| Home Assistant の機器操作 | WebSocket API で状態をリアルタイム表示、タップでオン/オフ |
| Proxmox 上のサーバー | HAOS の VM（`http://IP:8123`）に直接接続 |
| 家の中だけで動作・ログインは1回 | Wi-Fi 接続・VPN なし・接続先がプライベートIPの場合のみ動作。初回ログイン後はリフレッシュトークンで自動接続（パスワードは保存しない） |
| 音声操作 | マイク音声を HA の Assist（Whisper → 会話 → Piper）に送信して実行・読み上げ |
| Google Assistant 連携 | HA 側で設定（下記）。スマホや Nest スピーカーから同じ機器を操作 |
| Fire HD 8 で動作 | Google サービス不要。Fire OS 7（Android 9）以上で動作 |

## 1. Home Assistant 側の準備

1. **音声アシスタント**：アドオンで Whisper（言語 `ja`）と Piper を入れ、「設定 → 音声アシスタント」で日本語のアシスタントを作って **優先（既定）** に設定します。アプリは既定のアシスタントを使います。
2. **タブレット用ユーザー**：「設定 → ユーザー」で管理者でないユーザーを作ります。
3. **固定IP**：HAOS の VM のIPアドレスをルーターで固定しておきます。

## 2. Google Assistant 連携（HA 側の設定）

Fire OS では Google Assistant は動かないので、**スマホ・Google Nest スピーカーから** HA の機器を操作する形になります。アプリ自体は家の中専用のままです。

**A. Home Assistant Cloud（Nabu Casa・有料）— おすすめ**
- ポート開放や証明書の準備が不要です。
- HA の「設定 → Home Assistant Cloud」でサインインし、Google Assistant を有効にします。
- 「設定 → 音声アシスタント → 公開」で、Google に公開する機器を選びます。
- スマホの Google Home アプリで「デバイスを追加 → Google と連携 → Home Assistant Cloud by NABU CASA」を選びます。

**B. 手動設定（無料）**
- HA を外部から HTTPS で見えるようにする必要があります（例：Cloudflare Tunnel アドオン）。
- Google 側の開発者コンソールでプロジェクトを作り、`google_assistant` 統合を設定します。
- 手順はたびたび変わるので、公式ドキュメントに従ってください：https://www.home-assistant.io/integrations/google_assistant/

どちらの場合も、このアプリは外部URLを使いません。家の中のIPアドレスにだけ接続します。

## 3. APK のビルド

1. PC に Android Studio（最新版）を入れ、このフォルダを「Open」で開きます。
2. 同期が終わったら「Build → Build App Bundle(s) / APK(s) → Build APK(s)」を実行します。
3. `app/build/outputs/apk/debug/app-debug.apk` ができます。

コマンドラインの場合は `./gradlew assembleDebug` でビルドできます（Android SDK が必要です）。

## 4. Fire HD 8 へのインストール

**方法1：ADB（おすすめ）**
1. Fire の「設定 → デバイスオプション → シリアル番号」を7回タップして、開発者オプションを出します。
2. 「開発者オプション → ADBを有効にする」をオンにし、USBでPCとつなぎます。
3. `adb install -r app-debug.apk` を実行します。

**方法2：ファイルを直接コピー**
1. APK を Fire にコピーします。
2. 「設定 → セキュリティとプライバシー → 提供元不明のアプリ」で、ファイルアプリを許可します。
3. コピーした APK を開いてインストールします。

## 5. 初回起動

1. URL（例：`192.168.1.10` と入れると `http://192.168.1.10:8123` に補完）、ユーザー名、パスワードを入力してログインします。
2. 2段階認証を使っている場合は、コード入力欄が出ます。
3. マイクの権限を許可します。

これ以降は起動するだけで自動接続します。ログインし直しが必要になるのは、次の場合だけです。
- アプリでログアウトしたとき
- HA のプロフィール画面でこの端末のトークンを削除したとき

## 動作のしくみ

- `NetworkGuard.kt`：Wi-Fi/有線接続か、VPN でないか、接続先がプライベートIPかを確認します。満たさないときは操作画面を出しません。
- `LocalOnlyDns`：通信ライブラリ側でも、家の外のアドレスへの接続をブロックします。
- `Auth.kt`：HA の login_flow → token API で認証します。リフレッシュトークンは暗号化して保存します。
- `HaClient.kt`：WebSocket で状態の取得・購読とサービス呼び出しを行います。
- `VoiceAssistant.kt`：16kHz/16bit PCM を `assist_pipeline/run` に送り、TTS の音声を再生します。
- 表示対象：照明・スイッチ・ファン・カーテン・エアコン・input_boolean・シーン・スクリプト（`MainViewModel.kt` の `SUPPORTED_DOMAINS` で変更できます）。

## 注意

- ログインを何度も失敗すると、HA の設定によってはタブレットのIPがブロックされます（`ip_bans.yaml`）。
- Fire OS が省電力でアプリを止めることがあります。壁掛けで常時使う場合は、Fire の画面スリープ設定を長めにしてください（アプリ表示中は画面が消えません）。
- アプリのアイコンは未設定です（Android 標準アイコンになります）。
