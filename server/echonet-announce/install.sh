#!/bin/bash
# Proxmox 本体で実行：  bash install.sh 192.168.1.45 192.168.1.7 192.168.1.8 ...
set -e
HA=$1; shift || true
[ -n "$HA" ] && [ $# -gt 0 ] || { echo "使い方: bash install.sh <Home AssistantのIP> <機器のIP> [機器のIP...]"; exit 1; }
BASE=https://raw.githubusercontent.com/sato3123/HA_A/main/server/echonet-announce
curl -fsSL $BASE/echonet-announce.py -o /usr/local/bin/echonet-announce.py
curl -fsSL $BASE/echonet-announce.service -o /etc/systemd/system/echonet-announce.service
printf 'HA=%s\nDEVICES=%s\nINTERVAL=120\n' "$HA" "$*" > /etc/echonet-announce.conf
systemctl daemon-reload
systemctl enable --now echonet-announce
sleep 1
systemctl --no-pager status echonet-announce | head -5
echo "設定は /etc/echonet-announce.conf（機器を増やしたら systemctl restart echonet-announce）"
