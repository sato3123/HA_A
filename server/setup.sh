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
apt-get install -y -qq caddy curl jq unzip rsync ca-certificates >/dev/null

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

 更新は5分おきに自動で確認されます。
 今すぐ更新するとき： homepanel-update
========================================================
EOF
