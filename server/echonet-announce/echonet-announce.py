#!/usr/bin/env python3
"""
HEMS Echonet Lite（Home Assistant）に、マルチキャストが届かない ECHONET Lite 機器の存在を知らせる。

Home Assistant の HEMS Echonet Lite は、一斉の呼びかけ（マルチキャスト）に返事をした機器か、
自分から「お知らせ（INF）」を送ってきた機器しか登録しない。Wi-Fi のエアコンなどに呼びかけが
届かない家では、Home Assistant を再起動するたびに機器が「使用不可」になる。

このスクリプトは Proxmox 本体で動き、登録した機器のアドレスから「お知らせ」が来たように見える
小さな電文を、定期的に Home Assistant へ送る。Home Assistant はそれをきっかけに、その機器へ
直接（ユニキャストで）問い合わせて登録する。機器そのものには何も送らない。

設定：/etc/echonet-announce.conf
  HA=192.168.1.45
  DEVICES=192.168.1.7 192.168.1.8
  INTERVAL=120
"""
import socket
import struct
import sys
import time

CONF = "/etc/echonet-announce.conf"
PORT = 3610


def load():
    conf = {"HA": "", "DEVICES": "", "INTERVAL": "120"}
    try:
        with open(CONF, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if "=" in line:
                    k, v = line.split("=", 1)
                    conf[k.strip().upper()] = v.strip().strip('"')
    except FileNotFoundError:
        sys.exit(f"{CONF} がありません")
    ha = conf["HA"]
    devices = [d for d in conf["DEVICES"].replace(",", " ").split() if d]
    if not ha or not devices:
        sys.exit(f"{CONF} に HA と DEVICES を書いてください")
    return ha, devices, max(30, int(conf["INTERVAL"]))


def checksum(data: bytes) -> int:
    if len(data) % 2:
        data += b"\0"
    s = sum(struct.unpack(f"!{len(data) // 2}H", data))
    s = (s >> 16) + (s & 0xFFFF)
    s += s >> 16
    return ~s & 0xFFFF


def packet(src: str, dst: str, payload: bytes) -> bytes:
    udp_len = 8 + len(payload)
    udp = struct.pack("!HHHH", PORT, PORT, udp_len, 0) + payload
    pseudo = socket.inet_aton(src) + socket.inet_aton(dst) + struct.pack("!BBH", 0, socket.IPPROTO_UDP, udp_len)
    csum = checksum(pseudo + udp) or 0xFFFF
    udp = udp[:6] + struct.pack("!H", csum) + udp[8:]
    ip = struct.pack(
        "!BBHHHBBH4s4s", 0x45, 0, 20 + udp_len, 0, 0, 64, socket.IPPROTO_UDP, 0,
        socket.inet_aton(src), socket.inet_aton(dst),
    )
    return ip + udp


# ノードプロファイルの「インスタンスリスト通知（EPC 0xD5）」。中身は 0 件にしておく
# （Home Assistant は中身を使わず、送り元のアドレスへ直接問い合わせて正しい情報を取りにいく）
FRAME = bytes([
    0x10, 0x81, 0x00, 0x00,      # ECHONET Lite / TID
    0x0E, 0xF0, 0x01,            # 送り元：ノードプロファイル
    0x0E, 0xF0, 0x01,            # 宛先：ノードプロファイル
    0x73, 0x01,                  # INF（お知らせ）・プロパティ1個
    0xD5, 0x01, 0x00,            # インスタンスリスト通知：0件
])


def main():
    ha, devices, interval = load()
    s = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_RAW)
    print(f"Home Assistant {ha} に {', '.join(devices)} を {interval} 秒ごとに知らせます", flush=True)
    while True:
        for d in devices:
            try:
                s.sendto(packet(d, ha, FRAME), (ha, 0))
            except OSError as e:
                print(f"{d} の通知を送れません: {e}", flush=True)
            time.sleep(0.5)
        time.sleep(interval)


if __name__ == "__main__":
    main()
