#!/bin/bash
# ホームパネル（iPad 版）サーバーのセットアップ。LXC コンテナ（Debian 12）の中で root で実行する。
#   curl -fsSL https://raw.githubusercontent.com/sato3123/HA_A/main/server/setup.sh | bash
#
# 役割：
#   - iPad 版を https://SELF_IP/ で配信する（Caddy の自前の証明書）
#   - Home Assistant（HA_IP:8123）への通信を同じアドレスで中継する
#     → iPad から見ると1つの https のサイトになり、家の中だけでマイクが使える
#   - GitHub の最新リリースを5分おきに確認して自動で更新する
set -euo pipefail

HA_IP="${HA_IP:-192.168.1.45}"
HA_PORT="${HA_PORT:-8123}"
SELF_IP="${SELF_IP:-192.168.1.46}"
REPO="${REPO:-sato3123/HA_A}"
WEB=/srv/homepanel
CA_DIR=/srv/homepanel-ca

echo "== 必要なソフトを入れています"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq caddy curl jq unzip rsync ca-certificates python3 tzdata >/dev/null
# 時刻は日本時間に
ln -sf /usr/share/zoneinfo/Asia/Tokyo /etc/localtime && echo "Asia/Tokyo" > /etc/timezone
# カメラの記録・動きの分析に使う（入らなくても、ほかの機能は動く）
apt-get install -y -qq --no-install-recommends ffmpeg >/dev/null || echo "注意：ffmpeg を入れられませんでした（カメラの記録は使えません）"
# TimeTree の予定を取り出す道具（非公式の timetree-exporter）
apt-get install -y -qq python3-venv >/dev/null
[ -x /opt/timetree/bin/python ] || python3 -m venv /opt/timetree
/opt/timetree/bin/pip install -q -U timetree-exporter >/dev/null || echo "注意：timetree-exporter を入れられませんでした（TimeTree の取り込みは使えません）"

echo "== 更新用スクリプトを作っています"
mkdir -p "$WEB" "$CA_DIR"
cat > /usr/local/bin/homepanel-update <<EOF
#!/bin/bash
# GitHub の最新リリースから iPad 版を取ってきて、変わっていれば入れ替える
set -euo pipefail
URL=\$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | jq -r '.assets[] | select(.name=="homepanel-web.zip") | .browser_download_url')
[ -n "\$URL" ] || { echo "homepanel-web.zip が見つかりません"; exit 1; }
TMP=\$(mktemp -d)
trap 'rm -rf "\$TMP"' EXIT
curl -fsSL "\$URL" -o "\$TMP/web.zip"
unzip -q "\$TMP/web.zip" -d "\$TMP/new"
if ! cmp -s "\$TMP/new/version.json" "$WEB/version.json"; then
  rsync -a --delete "\$TMP/new/" "$WEB/"
  chmod -R a+rX "$WEB"
  echo "更新しました: \$(cat "$WEB/version.json")"
fi
# 電気料金の単価表（電力会社・プラン）も新しくする
mkdir -p /usr/local/share/homepanel
if curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/tariffs.json" -o "\$TMP/tariffs.json" && jq -e .providers "\$TMP/tariffs.json" >/dev/null; then
  cmp -s "\$TMP/tariffs.json" /usr/local/share/homepanel/tariffs.json || install -m 644 "\$TMP/tariffs.json" /usr/local/share/homepanel/tariffs.json
fi
# TimeTree の取り込みの道具も新しくする（1日1回。TimeTree 側の変更に追いつくため）
if [ -x /opt/timetree/bin/pip ] && [ -z "\$(find /opt/timetree/.updated -mtime -1 2>/dev/null)" ]; then
  /opt/timetree/bin/pip install -q -U timetree-exporter >/dev/null 2>&1 && touch /opt/timetree/.updated || true
fi
if curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-calendar.py" -o "\$TMP/calendar.py" && python3 -m py_compile "\$TMP/calendar.py"; then
  cmp -s "\$TMP/calendar.py" /usr/local/bin/homepanel-calendar || install -m 755 "\$TMP/calendar.py" /usr/local/bin/homepanel-calendar
fi
# カメラの記録のサービスも新しくする（止まっていれば動かす）
if [ -f /etc/systemd/system/homepanel-camera.service ] && ! systemctl is-active -q homepanel-camera.service; then
  systemctl enable --now homepanel-camera.service >/dev/null 2>&1 || true
fi
if curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-camera.py" -o "\$TMP/camera.py" && python3 -m py_compile "\$TMP/camera.py"; then
  if ! cmp -s "\$TMP/camera.py" /usr/local/bin/homepanel-camera; then
    install -m 755 "\$TMP/camera.py" /usr/local/bin/homepanel-camera
    systemctl restart homepanel-camera.service 2>/dev/null || true
    echo "カメラの記録のサービスを更新しました"
  fi
fi
# おひさま沸き上げのサービスも新しくする
if curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-solar.py" -o "\$TMP/solar.py" && python3 -m py_compile "\$TMP/solar.py"; then
  if ! cmp -s "\$TMP/solar.py" /usr/local/bin/homepanel-solar; then
    install -m 755 "\$TMP/solar.py" /usr/local/bin/homepanel-solar
    systemctl restart homepanel-solar.service
    echo "おひさま沸き上げのサービスを更新しました"
  fi
fi
EOF
chmod +x /usr/local/bin/homepanel-update

cat > /etc/systemd/system/homepanel-update.service <<'EOF'
[Unit]
Description=ホームパネル（iPad 版）の更新
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/homepanel-update
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
curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/tariffs.json" -o /usr/local/share/homepanel/tariffs.json && chmod 644 /usr/local/share/homepanel/tariffs.json || echo "注意：電気料金の単価表を取れませんでした"
curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-solar.py" -o /usr/local/bin/homepanel-solar
chmod 755 /usr/local/bin/homepanel-solar
cat > /etc/systemd/system/homepanel-solar.service <<'UNITEOF'
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

[Install]
WantedBy=multi-user.target
UNITEOF

echo "== カメラの記録（2日分）と動きの分析のサービスを用意しています"
curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-camera.py" -o /usr/local/bin/homepanel-camera
chmod 755 /usr/local/bin/homepanel-camera
cat > /etc/systemd/system/homepanel-camera.service <<'UNITEOF'
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

[Install]
WantedBy=multi-user.target
UNITEOF

echo "== TimeTree の予定の取り込み（30分ごと）を用意しています"
curl -fsSL "https://raw.githubusercontent.com/$REPO/main/server/homepanel-calendar.py" -o /usr/local/bin/homepanel-calendar
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
/usr/local/bin/homepanel-update

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

 更新は5分おきに自動で確認されます。
 今すぐ更新するとき： homepanel-update
========================================================
EOF
