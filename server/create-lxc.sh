#!/bin/bash
# ホームパネル（iPad 版）用の LXC コンテナを Proxmox 上に作る。
# Proxmox ホストの「シェル」で実行する：
#   bash <(curl -fsSL https://raw.githubusercontent.com/sato3123/HA_A/main/server/create-lxc.sh)
#
# 変えたい場合は、実行前に環境変数で指定できる（例：GATEWAY=192.168.1.254 bash ...）
set -euo pipefail

CTID="${CTID:-146}"                      # コンテナ番号（空いている番号）
IP="${IP:-192.168.1.46}"                 # このコンテナの IP
CIDR="${CIDR:-24}"
GATEWAY="${GATEWAY:-192.168.1.1}"        # ルーターの IP
BRIDGE="${BRIDGE:-vmbr0}"
STORAGE="${STORAGE:-local-lvm}"          # ディスクを置く場所
TEMPLATE_STORAGE="${TEMPLATE_STORAGE:-local}"
HA_IP="${HA_IP:-192.168.1.45}"           # Home Assistant の IP

if pct status "$CTID" >/dev/null 2>&1; then
  echo "コンテナ番号 $CTID はすでに使われています。CTID=147 のように別の番号を指定してください。" >&2
  exit 1
fi

echo "== Debian 12 のテンプレートを用意しています"
pveam update >/dev/null
TEMPLATE=$(pveam available --section system | awk '/debian-12-standard/ {print $2}' | sort -V | tail -1)
if [ -z "$TEMPLATE" ]; then echo "Debian 12 のテンプレートが見つかりません" >&2; exit 1; fi
if ! pveam list "$TEMPLATE_STORAGE" | grep -q "$TEMPLATE"; then
  pveam download "$TEMPLATE_STORAGE" "$TEMPLATE"
fi

echo "== コンテナ $CTID（$IP）を作成しています"
pct create "$CTID" "$TEMPLATE_STORAGE:vztmpl/$TEMPLATE" \
  --hostname homepanel \
  --cores 1 --memory 512 --swap 512 \
  --rootfs "$STORAGE:8" \
  --net0 "name=eth0,bridge=$BRIDGE,ip=$IP/$CIDR,gw=$GATEWAY" \
  --nameserver "$GATEWAY" \
  --unprivileged 1 --features nesting=1 \
  --onboot 1 --start 1

echo "== 起動を待っています"
for i in $(seq 1 30); do
  if pct exec "$CTID" -- ping -c1 -W2 github.com >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "== コンテナの中でセットアップしています"
pct exec "$CTID" -- bash -c "apt-get update -qq && apt-get install -y -qq curl >/dev/null && \
  curl -fsSL https://raw.githubusercontent.com/sato3123/HA_A/main/server/setup.sh | HA_IP=$HA_IP SELF_IP=$IP bash"
