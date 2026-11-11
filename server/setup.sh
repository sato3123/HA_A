#!/bin/bash
# ホームパネル（iPad 版）サーバーのセットアップ。LXC コンテナ（Debian 12）の中で root で実行する。
#   curl -fsSL https://raw.githubusercontent.com/sato3123/HA_A/main/server/setup.sh | bash
# リポジトリを非公開にしたあとは（TOKEN は GitHub の読み取り専用トークン）：
#   T=github_pat_xxx; curl -fsSL -H "Authorization: Bearer $T" -H "Accept: application/vnd.github.raw" \
#     https://api.github.com/repos/sato3123/HA_A/contents/server/setup.sh | GITHUB_TOKEN=$T bash
#
# 役割：
#   - iPad 版を https://SELF_IP/ で配信する（Caddy の自前の証明書）
#   - Home Assistant（HA_IP:8123）への通信を同じアドレスで中継する
#     → iPad から見ると1つの https のサイトになり、家の中だけでマイクが使える
#   - GitHub の最新リリースを5分おきに確認して自動で更新する
# スクリプト全体を { } で囲む：curl | bash で実行したとき、最後まで読み込んでから動かす（途中のコマンドが続きを読んでしまわないように）
{
set -euo pipefail

# セットアップの版。Caddy・サービス・コマンドなど、セットアップで入れるものを変えたら1つ上げる。
# 自動更新（homepanel-update）が、新しい版の setup.sh を見つけると、自分でこのセットアップを実行し直す
SETUP_VERSION=6
# セットアップが2つ同時に動かないようにする（自動の更新も、セットアップの最中はやり直しを始めない）
exec 8>/run/homepanel-setup.lock
flock -w 1800 8 || { echo "ほかのセットアップが終わりません"; exit 1; }

# 前回のセットアップで使った値（Home Assistant の IP など）。環境変数で指定しなければ、これを使う
if [ -f /etc/homepanel/setup.env ]; then
  while IFS='=' read -r k v; do
    case "$k" in HA_IP|HA_PORT|SELF_IP|REPO) [ -z "${!k:-}" ] && printf -v "$k" '%s' "$v" ;; esac
  done < /etc/homepanel/setup.env
fi
HA_IP="${HA_IP:-192.168.1.45}"
HA_PORT="${HA_PORT:-8123}"
SELF_IP="${SELF_IP:-192.168.1.46}"
REPO="${REPO:-sato3123/HA_A}"
WEB=/srv/homepanel
CA_DIR=/srv/homepanel-ca

echo "== 必要なソフトを入れています"
export DEBIAN_FRONTEND=noninteractive
# ソフトを入れたあとに、ほかのサービスを勝手に再起動させない（needrestart は一覧だけにする）
export NEEDRESTART_MODE=l NEEDRESTART_SUSPEND=1
# もう全部入っていれば、入れ直さない（自動でセットアップし直すときに速く終わるように）
if command -v caddy curl jq unzip rsync python3 ffmpeg flock pdftotext >/dev/null 2>&1 && dpkg -s python3-venv tzdata ca-certificates >/dev/null 2>&1 \
   && [ -x /opt/timetree/bin/timetree-exporter ]; then
  echo "（必要なソフトはもう入っています）"
else
  apt-get update -qq
  apt-get install -y -qq caddy curl jq unzip rsync ca-certificates python3 tzdata util-linux >/dev/null
  # カメラの記録・動きの分析に使う（入らなくても、ほかの機能は動く）
  apt-get install -y -qq --no-install-recommends ffmpeg >/dev/null || echo "注意：ffmpeg を入れられませんでした（カメラの記録は使えません）"
  # 電力会社の燃料費調整単価のお知らせ（PDF）を読む（入らなくても、ほかの機能は動く）
  apt-get install -y -qq --no-install-recommends poppler-utils >/dev/null || echo "注意：pdftotext を入れられませんでした（燃料費調整額は手で入れてください）"
  # TimeTree の予定を取り出す道具（非公式の timetree-exporter）
  apt-get install -y -qq python3-venv >/dev/null
  [ -x /opt/timetree/bin/python ] || python3 -m venv /opt/timetree
  /opt/timetree/bin/pip install -q -U timetree-exporter >/dev/null || echo "注意：timetree-exporter を入れられませんでした（TimeTree の取り込みは使えません）"
fi
# 時刻は日本時間に
ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && echo "Asia/Tokyo" > /etc/timezone

echo "== 更新用スクリプトを作っています"
mkdir -p "$WEB" "$CA_DIR" /etc/homepanel /srv/homepanel-android
# リポジトリを非公開にしたときは、読み取り専用のトークン（GitHub の fine-grained token）をここに置く
#   GITHUB_TOKEN=github_pat_xxx bash setup.sh   または   homepanel-github-token
if [ -n "${GITHUB_TOKEN:-}" ]; then
  (umask 077; printf '%s' "$GITHUB_TOKEN" > /etc/homepanel/github_token)
fi
echo "$REPO" > /etc/homepanel/repo
cat > /usr/local/bin/homepanel-github-token <<'TOKEOF'
#!/bin/bash
# 非公開のリポジトリから更新を取るための、GitHub のトークンを登録する（root だけが読めるファイルに保存）
set -euo pipefail
read -rsp "GitHub のトークン（github_pat_…。入力は表示されません。空で削除）: " T; echo
if [ -z "$T" ]; then rm -f /etc/homepanel/github_token; echo "削除しました"; exit 0; fi
(umask 077; printf '%s' "$T" > /etc/homepanel/github_token)
REPO=$(cat /etc/homepanel/repo)
if curl -fsS -o /dev/null -H "Authorization: Bearer $T" "https://api.github.com/repos/$REPO"; then echo "OK：$REPO を読めました"; else echo "注意：$REPO を読めません（トークンの権限・期限を確認してください）"; fi
TOKEOF
chmod 700 /usr/local/bin/homepanel-github-token

cat > /usr/local/bin/homepanel-update.new <<'UPDEOF'
#!/bin/bash
# GitHub の最新リリースから iPad 版・Android 版・サーバーのプログラムを取ってきて、変わっていれば入れ替える
# リポジトリが非公開のときは /etc/homepanel/github_token のトークンで読む
set -euo pipefail
# 同時に2つ動かないようにする（タイマーと手動、セットアップの中から呼ぶときなど）
exec 9>/run/homepanel-update.lock
# ほかの更新（5分おきのタイマーなど）が動いていれば、終わるまで待つ
flock -w 900 9 || { echo "ほかの更新が15分たっても終わりません"; exit 0; }
REPO=$(cat /etc/homepanel/repo)
WEB=/srv/homepanel
APK_DIR=/srv/homepanel-android
mkdir -p "$APK_DIR"
TOKEN=$(cat /etc/homepanel/github_token 2>/dev/null || true)
AUTH=()
[ -n "$TOKEN" ] && AUTH=(-H "Authorization: Bearer $TOKEN")
# トークンの期限が切れた・消されたときは、トークンなしで読む（リポジトリが公開なら、そのまま更新できる）
if [ -n "$TOKEN" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 --retry 2 "${AUTH[@]}" "https://api.github.com/repos/$REPO")" = "401" ]; then
  echo "注意：GitHub のトークンが使えません（期限切れなど）。トークンなしで更新を確認します"
  TOKEN=""; AUTH=()
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# リポジトリのファイルを1つ取る（main ブランチ）
ghfile() {
  if [ -n "$TOKEN" ]; then
    curl -fsSL --connect-timeout 20 --max-time 600 --retry 3 --retry-delay 5 --retry-all-errors "${AUTH[@]}" -H "Accept: application/vnd.github.raw" "https://api.github.com/repos/$REPO/contents/$1?ref=main" -o "$2"
  else
    curl -fsSL --connect-timeout 20 --max-time 600 --retry 3 --retry-delay 5 --retry-all-errors "https://raw.githubusercontent.com/$REPO/main/$1" -o "$2"
  fi
}
# 最新リリースの添付ファイルを1つ取る（名前か、名前の末尾で）
ghasset() {
  local url
  if [ -n "$TOKEN" ]; then
    url=$(jq -r --arg n "$1" '[.assets[] | select(.name==$n or (.name|endswith($n)))][0].url // empty' "$TMP/release.json")
    [ -n "$url" ] && curl -fsSL --connect-timeout 20 --max-time 600 --retry 3 --retry-delay 5 --retry-all-errors "${AUTH[@]}" -H "Accept: application/octet-stream" "$url" -o "$2"
  else
    url=$(jq -r --arg n "$1" '[.assets[] | select(.name==$n or (.name|endswith($n)))][0].browser_download_url // empty' "$TMP/release.json")
    [ -n "$url" ] && curl -fsSL --connect-timeout 20 --max-time 600 --retry 3 --retry-delay 5 --retry-all-errors "$url" -o "$2"
  fi
}

curl -fsSL --connect-timeout 20 --max-time 600 --retry 3 --retry-delay 5 --retry-all-errors "${AUTH[@]}" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest" -o "$TMP/release.json" \
  || { echo "最新リリースを読めません（リポジトリが非公開なら homepanel-github-token でトークンを登録してください）"; exit 1; }
TAG=$(jq -r .tag_name "$TMP/release.json")

# iPad 版
if ghasset homepanel-web.zip "$TMP/web.zip"; then
  unzip -q "$TMP/web.zip" -d "$TMP/new"
  if ! cmp -s "$TMP/new/version.json" "$WEB/version.json"; then
    rsync -a --delete "$TMP/new/" "$WEB/"
    chmod -R a+rX "$WEB"
    echo "更新しました: $(cat "$WEB/version.json")"
  fi
else
  echo "homepanel-web.zip が見つかりません"
fi
# Android 版：家の中だけで配る（http://このサーバー/android/。Home Assistant にログインしたアプリだけが取れる）
if [ "$(jq -r .tag "$APK_DIR/latest.json" 2>/dev/null || true)" != "$TAG" ] && ghasset .apk "$TMP/app.apk"; then
  # GitHub が添付ファイルに付ける SHA-256（digest）と照らし合わせる。合わなければ配らない
  SHA=$(sha256sum "$TMP/app.apk" | cut -d' ' -f1)
  WANT=$(jq -r '[.assets[] | select(.name|endswith(".apk"))][0].digest // "" | sub("^sha256:"; "")' "$TMP/release.json")
  if [ -n "$WANT" ] && [ "$WANT" != "$SHA" ]; then
    echo "Android 版の APK の SHA-256 が GitHub の記録と合いません。配るのをやめました"
  else
    install -m 644 "$TMP/app.apk" "$APK_DIR/HomePanel.apk"
    jq --arg sha "$SHA" '{tag: .tag_name, name: ([.assets[] | select(.name|endswith(".apk"))][0].name), notes: .body, sha256: $sha}' "$TMP/release.json" > "$TMP/latest.json"
    install -m 644 "$TMP/latest.json" "$APK_DIR/latest.json"
    echo "Android 版を用意しました: $TAG"
  fi
fi
# 電気料金の単価表（電力会社・プラン）も新しくする
mkdir -p /usr/local/share/homepanel
if ghfile server/tariffs.json "$TMP/tariffs.json" && jq -e .providers "$TMP/tariffs.json" >/dev/null; then
  cmp -s "$TMP/tariffs.json" /usr/local/share/homepanel/tariffs.json || install -m 644 "$TMP/tariffs.json" /usr/local/share/homepanel/tariffs.json
fi
# TimeTree の取り込みの道具も新しくする（1日1回。TimeTree 側の変更に追いつくため）
if [ -x /opt/timetree/bin/pip ] && [ -z "$(find /opt/timetree/.updated -mtime -1 2>/dev/null)" ]; then
  timeout 600 /opt/timetree/bin/pip install -q -U timetree-exporter >/dev/null 2>&1 && touch /opt/timetree/.updated || true
fi
if ghfile server/homepanel-calendar.py "$TMP/calendar.py" && python3 -m py_compile "$TMP/calendar.py"; then
  cmp -s "$TMP/calendar.py" /usr/local/bin/homepanel-calendar || install -m 755 "$TMP/calendar.py" /usr/local/bin/homepanel-calendar
fi
# カメラの記録のサービスも新しくする（止まっていれば動かす）
if [ -f /etc/systemd/system/homepanel-camera.service ] && ! systemctl is-active -q homepanel-camera.service; then
  systemctl enable --now homepanel-camera.service >/dev/null 2>&1 || true
fi
if ghfile server/homepanel-camera.py "$TMP/camera.py" && python3 -m py_compile "$TMP/camera.py"; then
  if ! cmp -s "$TMP/camera.py" /usr/local/bin/homepanel-camera; then
    install -m 755 "$TMP/camera.py" /usr/local/bin/homepanel-camera
    systemctl restart homepanel-camera.service 2>/dev/null || true
    echo "カメラの記録のサービスを更新しました"
  fi
fi
# おひさま沸き上げのサービスも新しくする
if ghfile server/homepanel-solar.py "$TMP/solar.py" && python3 -m py_compile "$TMP/solar.py"; then
  if ! cmp -s "$TMP/solar.py" /usr/local/bin/homepanel-solar; then
    install -m 755 "$TMP/solar.py" /usr/local/bin/homepanel-solar
    systemctl restart homepanel-solar.service 2>/dev/null || true
    echo "おひさま沸き上げのサービスを更新しました"
  fi
fi
# セットアップ（Caddy・サービス・コマンド）に新しい版があれば、コマンドを入れなくても自分で実行し直す。
# 別のサービスとして動かすので、この更新用スクリプトが書きかえられても困らない。記録は journalctl -u homepanel-selfsetup で見られる
if [ -z "${HOMEPANEL_IN_SETUP:-}" ] && [ ! -e /etc/homepanel/no-auto-setup ] && ghfile server/setup.sh "$TMP/setup.sh" && bash -n "$TMP/setup.sh"; then
  NEW=$(sed -n 's/^SETUP_VERSION=\([0-9]\+\).*/\1/p' "$TMP/setup.sh" | head -1)
  CUR=$(cat /etc/homepanel/setup_version 2>/dev/null || echo 0)
  if [ -n "$NEW" ] && [ "$NEW" -gt "$CUR" ] && ! systemctl is-active -q homepanel-selfsetup.service \
     && flock -n /run/homepanel-setup.lock true; then
    mkdir -p /var/lib/homepanel-setup && chmod 755 /var/lib/homepanel-setup
    install -m 700 "$TMP/setup.sh" /var/lib/homepanel-setup/setup.sh
    systemctl reset-failed homepanel-selfsetup.service 2>/dev/null || true
    systemd-run --unit=homepanel-selfsetup --no-block --collect -p TimeoutStartSec=30min \
      /bin/bash -c 'umask 022; bash /var/lib/homepanel-setup/setup.sh > /var/lib/homepanel-setup/last.log 2>&1; echo "終了コード $?" >> /var/lib/homepanel-setup/last.log'
    echo "セットアップの新しい版（$CUR → $NEW）を見つけたので、実行し直しています（記録：/var/lib/homepanel-setup/last.log）"
  fi
fi
UPDEOF
chmod 755 /usr/local/bin/homepanel-update.new
mv -f /usr/local/bin/homepanel-update.new /usr/local/bin/homepanel-update

cat > /etc/systemd/system/homepanel-update.service <<'EOF'
[Unit]
Description=ホームパネル（iPad 版）の更新
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/homepanel-update
# 万一どこかで止まっても、30分で打ち切って次の確認に進む
TimeoutStartSec=30min
EOF

cat > /etc/systemd/system/homepanel-update.timer <<'EOF'
[Unit]
Description=ホームパネル（iPad 版）を5分おきに更新確認

[Timer]
OnBootSec=1min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOF

# このセットアップの中でリポジトリのファイルを取る（非公開ならトークンで）
ghfile() {
  local T; T=$(cat /etc/homepanel/github_token 2>/dev/null || true)
  # 使えないトークン（期限切れなど）なら、トークンなしで読む
  if [ -n "$T" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -H "Authorization: Bearer $T" "https://api.github.com/repos/$REPO")" = "401" ]; then T=""; fi
  if [ -n "$T" ]; then
    curl -fsSL --connect-timeout 20 --max-time 300 -H "Authorization: Bearer $T" -H "Accept: application/vnd.github.raw" "https://api.github.com/repos/$REPO/contents/$1?ref=main" -o "$2"
  else
    curl -fsSL --connect-timeout 20 --max-time 300 "https://raw.githubusercontent.com/$REPO/main/$1" -o "$2"
  fi
}

echo "== スクリーンセーバー用の写真サービスを用意しています"
cat > /usr/local/bin/homepanel-photos <<'PYEOF'
#!/usr/bin/env python3
"""Google フォトの「共有アルバム」のリンクから写真の URL 一覧を返す小さなサービス。
iPad のブラウザからは Google のページを直接読めないため、このサーバーが代わりに読む。
GET /photos-api/album?url=<共有リンク>  ->  {"photos": ["https://lh3.googleusercontent.com/pw/...", ...]}
"""
import json, re, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED = ("photos.app.goo.gl", "photos.google.com", "goo.gl")
CACHE = {}
PW = re.compile(r'\["(https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]+)",(\d+),(\d+)')
ANY = re.compile(r'\["(https://lh3\.googleusercontent\.com/[A-Za-z0-9_\-]{30,})",(\d+),(\d+)')

def extract(html):
    def find(rx):
        seen, out = set(), []
        for m in rx.finditer(html):
            if int(m.group(2)) >= 300 and int(m.group(3)) >= 300 and m.group(1) not in seen:
                seen.add(m.group(1)); out.append(m.group(1))
        return out
    return find(PW) or find(ANY)

def album(url):
    host = urllib.parse.urlparse(url).hostname or ""
    if host not in ALLOWED:
        raise ValueError("Google フォトの共有リンク（https://photos.app.goo.gl/…）を指定してください")
    hit = CACHE.get(url)
    if hit and time.time() - hit[0] < 600:
        return hit[1]
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/604.1",
        "Accept-Language": "ja,en;q=0.8",
    })
    with urllib.request.urlopen(req, timeout=20) as r:
        final = urllib.parse.urlparse(r.geturl()).hostname or ""
        if final not in ALLOWED:
            raise ValueError("Google フォト以外のページに移動しました")
        html = r.read().decode("utf-8", "replace")
    photos = extract(html)
    CACHE[url] = (time.time(), photos)
    return photos

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path.rstrip("/") not in ("/photos-api/album", "/album"):
            self.send_response(404); self.end_headers(); return
        q = urllib.parse.parse_qs(u.query)
        try:
            body = {"photos": album((q.get("url") or [""])[0].strip())}
        except Exception as e:
            body = {"error": str(e), "photos": []}
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

ThreadingHTTPServer(("127.0.0.1", 8099), H).serve_forever()
PYEOF
chmod +x /usr/local/bin/homepanel-photos
cat > /etc/systemd/system/homepanel-photos.service <<'UNITEOF'
[Unit]
Description=ホームパネル スクリーンセーバーの写真サービス
After=network-online.target

[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/homepanel-photos
Restart=always
DynamicUser=yes

[Install]
WantedBy=multi-user.target
UNITEOF

echo "== おひさま沸き上げ（エコキュート）のサービスを用意しています"
mkdir -p /usr/local/share/homepanel
ghfile server/tariffs.json /usr/local/share/homepanel/tariffs.json && chmod 644 /usr/local/share/homepanel/tariffs.json || echo "注意：電気料金の単価表を取れませんでした"
ghfile server/homepanel-solar.py /usr/local/bin/homepanel-solar
chmod 755 /usr/local/bin/homepanel-solar
cat > /etc/systemd/system/homepanel-solar.service <<UNITEOF
[Unit]
Description=ホームパネル おひさま沸き上げ（エコキュート）
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/homepanel-solar
Restart=always
RestartSec=10
DynamicUser=yes
StateDirectory=homepanel-solar
Environment=TZ=Asia/Tokyo
Environment=HOMEPANEL_HA_URL=http://$HA_IP:$HA_PORT

[Install]
WantedBy=multi-user.target
UNITEOF

# このサーバーの中から API を呼ぶとき（確認・調べ物）は、ローカルのトークンを付けて呼ぶ（API は全部、認証が必要）
cat > /usr/local/bin/homepanel-api <<'APIEOF'
#!/bin/bash
# 使い方：homepanel-api solar|camera GET|POST パス [JSON]
#   例）homepanel-api solar POST /aiseg-probe
#       homepanel-api solar GET /status
set -euo pipefail
svc=${1:?solar か camera}; method=${2:?GET か POST}; path=${3:?パス（例：/status）}; body=${4:-}
case "$svc" in
  solar) port=8098; dir=/var/lib/homepanel-solar ;;
  camera) port=8097; dir=/var/lib/homepanel-camera ;;
  *) echo "solar か camera を指定してください" >&2; exit 2 ;;
esac
tok=$(cat "$dir/local_token" 2>/dev/null || cat "/var/lib/private/${dir##*/}/local_token")
if [ "$method" = POST ]; then
  [ -n "$body" ] || body='{}'
  curl -sS -X POST -H "Authorization: Bearer $tok" -H "Content-Type: application/json" --data "$body" "http://127.0.0.1:$port$path"
else
  curl -sS -H "Authorization: Bearer $tok" "http://127.0.0.1:$port$path"
fi
echo
APIEOF
chmod 700 /usr/local/bin/homepanel-api

echo "== カメラの記録（2日分）と動きの分析のサービスを用意しています"
ghfile server/homepanel-camera.py /usr/local/bin/homepanel-camera
chmod 755 /usr/local/bin/homepanel-camera
cat > /etc/systemd/system/homepanel-camera.service <<UNITEOF
[Unit]
Description=ホームパネル カメラの記録と動きの分析
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/homepanel-camera
Restart=always
RestartSec=10
DynamicUser=yes
StateDirectory=homepanel-camera
Environment=TZ=Asia/Tokyo
Environment=HOMEPANEL_HA_URL=http://$HA_IP:$HA_PORT

[Install]
WantedBy=multi-user.target
UNITEOF

echo "== TimeTree の予定の取り込み（30分ごと）を用意しています"
ghfile server/homepanel-calendar.py /usr/local/bin/homepanel-calendar
chmod 755 /usr/local/bin/homepanel-calendar
mkdir -p /var/lib/homepanel-calendar && chmod 755 /var/lib/homepanel-calendar
cat > /etc/systemd/system/homepanel-calendar.service <<UNITEOF
[Unit]
Description=ホームパネル TimeTree の予定の書き出し
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /usr/local/bin/homepanel-calendar run
Environment=TZ=Asia/Tokyo
Environment=SELF_IP=$SELF_IP
TimeoutStartSec=1800
UNITEOF
cat > /etc/systemd/system/homepanel-calendar.timer <<'UNITEOF'
[Unit]
Description=ホームパネル TimeTree の予定の書き出し（30分ごと）

[Timer]
OnBootSec=2min
OnUnitActiveSec=30min
RandomizedDelaySec=60

[Install]
WantedBy=timers.target
UNITEOF

echo "== iPad 版をダウンロードしています"
# セットアップの途中なので、更新用スクリプトにセットアップのやり直しはさせない
HOMEPANEL_IN_SETUP=1 /usr/local/bin/homepanel-update

echo "== Caddy（https と中継）を設定しています"
cat > /etc/caddy/Caddyfile <<EOF
{
	# 自前の証明書をこのコンテナのシステムには登録しない（iPad 側に入れる）
	skip_install_trust
}

# http で来たら：証明書の配布ページ以外は https へ
http://$SELF_IP {
	# おひさま沸き上げの状態・設定（Android 版から。家の中のネットワークからだけ）
	handle /solar-api/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		reverse_proxy 127.0.0.1:8098
	}
	# カメラの記録・動きの分析（家の中のネットワークからだけ）
	handle /camera-api/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		reverse_proxy 127.0.0.1:8097
	}
	# TimeTree の予定（Home Assistant の Remote Calendar が読む。家の中のネットワークからだけ）
	handle_path /calendar/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		root * /var/lib/homepanel-calendar
		@other not path *.ics *.json
		respond @other 404
		header Cache-Control "no-cache"
		file_server
	}
	# Android 版のアプリ（家の中だけ。Home Assistant にログインしたアプリだけが取れる）
	handle_path /android/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		forward_auth 127.0.0.1:8098 {
			uri /auth-check
		}
		root * /srv/homepanel-android
		@other not path /latest.json /HomePanel.apk
		respond @other 404
		header Cache-Control "no-cache"
		file_server
	}
	handle /ca.crt {
		root * $CA_DIR
		header Content-Type application/x-x509-ca-cert
		file_server
	}
	handle {
		redir https://$SELF_IP{uri}
	}
}

https://$SELF_IP {
	tls internal

	# トップは iPad 版へ
	redir / /homepanel/ 302
	redir /homepanel /homepanel/ 302

	# iPad 版のファイル
	handle_path /homepanel/* {
		root * $WEB
		header Cache-Control "no-cache"
		# 画面に紛れ込んだスクリプトを動かさない（スクリプトはこのサーバーのファイルだけ）。ほかのサイトに URL を渡さない
		header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: http: https:; media-src 'self' blob: http: https:; connect-src 'self' http: https: ws: wss:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
		header Referrer-Policy "no-referrer"
		header X-Content-Type-Options "nosniff"
		file_server
	}

	# おひさま沸き上げの状態・設定（家の中のネットワークからだけ）
	handle /solar-api/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		reverse_proxy 127.0.0.1:8098
	}
	# カメラの記録・動きの分析（家の中のネットワークからだけ）
	handle /camera-api/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		reverse_proxy 127.0.0.1:8097
	}

	# TimeTree の予定（Home Assistant の Remote Calendar が読む。家の中のネットワークからだけ）
	handle_path /calendar/* {
		@outside not remote_ip private_ranges
		respond @outside 403
		root * /var/lib/homepanel-calendar
		@other not path *.ics *.json
		respond @other 404
		header Cache-Control "no-cache"
		file_server
	}
	# スクリーンセーバーの写真一覧（このサーバーの中のサービス）
	handle /photos-api/* {
		reverse_proxy 127.0.0.1:8099
	}

	# それ以外（ログイン画面・API・WebSocket・読み上げ音声など）は Home Assistant へ中継
	handle {
		reverse_proxy $HA_IP:$HA_PORT {
			# Home Assistant 側の設定（trusted_proxies）を不要にするため、転送元の情報は付けない
			header_up -X-Forwarded-For
			header_up -X-Forwarded-Proto
			header_up -X-Forwarded-Host
		}
	}
}
EOF

systemctl enable --now caddy >/dev/null 2>&1 || true
systemctl restart caddy
systemctl daemon-reload
systemctl enable --now homepanel-update.timer >/dev/null
systemctl enable --now homepanel-photos.service >/dev/null
systemctl restart homepanel-photos.service
systemctl enable --now homepanel-solar.service >/dev/null
systemctl restart homepanel-solar.service
systemctl enable --now homepanel-camera.service >/dev/null
systemctl restart homepanel-camera.service
systemctl enable --now homepanel-calendar.timer >/dev/null

echo "== 証明書を用意しています"
ROOT=/var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt
for i in $(seq 1 30); do
  [ -f "$ROOT" ] && break
  # 証明書は最初の https 接続のときに作られる
  curl -sk "https://$SELF_IP/homepanel/" -o /dev/null || true
  sleep 1
done
if [ -f "$ROOT" ]; then
  cp "$ROOT" "$CA_DIR/ca.crt"
  chmod a+r "$CA_DIR/ca.crt"
else
  echo "証明書がまだ作られていません。少し待ってから「cp $ROOT $CA_DIR/ca.crt」を実行してください。" >&2
fi

echo "== Home Assistant に届くか確認しています"
if curl -fsS -o /dev/null "http://$HA_IP:$HA_PORT/manifest.json"; then
  echo "OK：Home Assistant（$HA_IP:$HA_PORT）に届きました"
else
  echo "注意：Home Assistant（$HA_IP:$HA_PORT）に届きません。IP とポートを確認してください。" >&2
fi

# 次に自動でセットアップし直すときのために、使った値と版を残す
mkdir -p /etc/homepanel
printf 'HA_IP=%s\nHA_PORT=%s\nSELF_IP=%s\nREPO=%s\n' "$HA_IP" "$HA_PORT" "$SELF_IP" "$REPO" > /etc/homepanel/setup.env
echo "$SETUP_VERSION" > /etc/homepanel/setup_version

cat <<EOF

========================================================
 セットアップが終わりました

 1. iPad の Safari で http://$SELF_IP/ca.crt を開き、
    証明書（プロファイル）をダウンロードしてインストールしてください。
    「設定 → 一般 → 情報 → 証明書信頼設定」で
    「Caddy Local Authority」をオンにします。

 2. iPad の Safari で https://$SELF_IP/ を開くと、ホームパネルが表示されます。
    共有ボタン →「ホーム画面に追加」でアプリのように使えます。

 3. おひさま沸き上げ（エコキュート）は、このサーバーで動きます。
    Android 版の「⚙ 設定 → おひさま沸き上げ」で「サーバーで動かす」を選ぶと、
    エコキュート・スマートメーター・自宅の位置がサーバーに送られます。

 更新は5分おきに自動で確認されます（セットアップに新しい版があれば、それも自動で実行し直します）。
 今すぐ更新するとき： homepanel-update
========================================================
EOF
} < /dev/null
