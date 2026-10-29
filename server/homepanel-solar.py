#!/usr/bin/env python3
"""
おひさま沸き上げ（エコキュート）を、ホームパネルのサーバー（Proxmox の LXC・192.168.1.46）で動かす。
スマートフォンやタブレットが無くても、毎日の設定と昼の沸き増しを続ける。

・毎日決まった時刻（初期値 20 時）に翌日の天気予報（Open-Meteo。キー不要）を見て、
  晴れならエコキュートの「エネルギーシフト」に参加させ、夜の沸き上げを昼にずらす。晴れでなければ参加をやめる。
  1項目ずつ設定して読み直し、失敗したら 10 分ごとにやり直す。朝 7 時にも設定が残っているか確かめる。
・昼の時間帯はスマートメーター（ECHONET Lite）の瞬時電力を 15 秒ごとに読み、
  売電が続けば沸き増し（B0=0x42）、電気を買い始めたら自動（B0=0x41）に戻す。
・エコキュートとスマートメーターとは ECHONET Lite（UDP 3610）で直接やりとりする。
・画面（Android 版・iPad 版）からは /solar-api/ で状態を見たり設定を変えたりする（家の中のネットワークからだけ）。

標準ライブラリだけで動く。設定：/var/lib/homepanel-solar/config.json　状態：/var/lib/homepanel-solar/state.json
"""
import ipaddress
import json
import os
import socket
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 時刻はすべて日本時間（1日の切り替わりも日本時間の 0:00）
os.environ["TZ"] = "Asia/Tokyo"
time.tzset()

DIR = os.environ.get("SOLAR_DIR", "/var/lib/homepanel-solar")
CONF_PATH = os.path.join(DIR, "config.json")
STATE_PATH = os.path.join(DIR, "state.json")
ENERGY_PATH = os.path.join(DIR, "energy.json")
PORT = 3610
MCAST = "224.0.23.0"
API_PORT = int(os.environ.get("SOLAR_API_PORT", "8098"))

DEFAULT_CONF = {
    "enabled": False,
    "eco_ip": "", "eco_eoj": "026B01",      # エコキュート
    "meter_ip": "", "meter_eoj": "028801",  # スマートメーター（0288）または分電盤の電力計測（0287）
    "start_hour": 10, "end_hour": 15,       # 昼に沸かす時間帯
    "threshold_w": 1200,                    # 沸き増しを始める売電（W）
    "stop_buy_w": 300,                      # 沸き増し中、この量を超えて買い始めたら戻す（W）
    "plan_hour": 20,                        # 毎日の設定をする時刻
    "shift": True,                          # 晴れの日は夜の沸き上げを昼にずらす
    # 夜の沸き上げの減らし方：auto（エネルギーシフト→タンクの運転モード）/ stop（このサーバーが夜の沸き上げを手動で止める）
    "night_method": "auto",
    "stop_min_pct": 50,                     # 夜に止めるのは、残りのお湯がこれ以上のとき（%）
    "safety_hour": 4, "safety_pct": 30,     # 夜中に残りがこれより減ったら、止めるのをやめて夜に沸かす
    "ensure_pct": 60,                       # 昼の時間帯の終わる2時間前に、残りがこれより少なければ売電が無くても沸かす
    "lat": None, "lon": None,               # 天気予報の場所（Home Assistant の自宅の位置を画面から送る）
    # AiSEG2（Panasonic の HEMS）から、いまの発電・消費を読む（スマートメーターの代わり。こちらが優先）
    "aiseg_url": "", "aiseg_user": "aiseg", "aiseg_password": "",
    "night_heat_pct": 50,                   # 夜止め：夜はこの残量（%）まで沸かしたら止める
    "ha_url": "http://192.168.1.45:8123", "ha_token": "",   # エアコンの自動温度調整で使う Home Assistant
    "climate_enabled": False,               # 自動温度調整の全体のオン/オフ
    "climate_rules": [],                    # [{entity, enabled, min, max, source, cool, heat, off}]
    # 電気料金：電力会社のプラン（tariffs.json の plan id、または custom）と契約の大きさ（A・kVA・kW）
    "tariff_plan": "", "tariff_contract": 30,
    "tariff_fuel": 0.0,                     # 燃料費調整額（円/kWh。毎月の検針票の値。マイナスもある）
    "tariff_sell": 0.0,                     # 売電の単価（円/kWh）
    "tariff_custom": {},                    # 自分で入力：{basic, day_rate, night_rate, night_start, night_end}
    "tariff_discounts": [],                 # 使う割引（プランの discounts の id。例：電化割引）
    "power_devices": [],                    # 消費電力を直接読む機器（エアコンなど）：[{ip, eoj, name}]
}
SECRET_CONF = {"aiseg_password", "ha_token"}

EPC_NAME = {0xB0: "沸き上げ自動設定", 0xC0: "昼間沸き増し許可", 0xC7: "エネルギーシフト参加", 0xC9: "エネルギーシフト回数", 0xCA: "昼間沸き上げシフト時刻",
            0xB6: "タンク運転モード"}
# 調べるだけの項目（設定はしない）
DIAG_EPCS = [0xB0, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xC0, 0xC7, 0xC8, 0xC9, 0xCA, 0xE1, 0xE2]
DIAG_NAME = {**EPC_NAME, **{0xB2: "沸き上げ中の状態", 0xB3: "沸き上げ湯温", 0xB4: "手動沸き上げ停止日数", 0xB5: "手動沸き上げ停止（時間）",
                              0xC8: "沸き上げ開始の基準時刻", 0xE1: "残りのお湯の量", 0xE2: "タンクの容量"}}
TANK_MODE = {0x41: "標準", 0x42: "節約", 0x43: "多め"}
SAFE_SET = set(EPC_NAME)

lock = threading.RLock()


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, data):
    os.makedirs(DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


conf = dict(DEFAULT_CONF, **load_json(CONF_PATH, {}))
state = dict({"plan": None, "log": [], "status": "準備中", "history": [], "eco": {}, "by_us": False}, **load_json(STATE_PATH, {}))
state["history"] = []  # グラフは起動しなおすと空から


def log(msg):
    line = datetime.now().strftime("%m/%d %H:%M") + "　" + msg
    print(line, flush=True)
    with lock:
        state["log"] = ([line] + state["log"])[:80]
        save_json(STATE_PATH, {k: state.get(k) for k in ("plan", "log", "by_us", "shift_unsupported", "tank_by_us", "night_stop", "night_pending")})


# ================= ECHONET Lite =================

def eoj_bytes(hexstr):
    return bytes.fromhex(hexstr)


class Echonet:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", PORT))
        self.sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        try:
            mreq = struct.pack("4s4s", socket.inet_aton(MCAST), socket.inet_aton("0.0.0.0"))
            self.sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError as e:
            print("マルチキャストに参加できません:", e, flush=True)
        self.tid = 1
        self.waiting = {}
        self.sink = None
        threading.Thread(target=self._recv, daemon=True).start()

    def _recv(self):
        while True:
            try:
                data, addr = self.sock.recvfrom(1500)
            except OSError:
                time.sleep(1)
                continue
            f = self.decode(data)
            if not f:
                continue
            if self.sink:
                try:
                    self.sink(addr[0], f)
                except Exception:
                    pass
            w = self.waiting.get(f["tid"])
            if w and w["ip"] == addr[0]:
                w["frame"] = f
                w["ev"].set()

    @staticmethod
    def decode(b):
        if len(b) < 12 or b[0] != 0x10 or b[1] != 0x81:
            return None
        f = {"tid": (b[2] << 8) | b[3], "seoj": b[4:7].hex().upper(), "deoj": b[7:10].hex().upper(), "esv": b[10], "props": []}
        i, n = 12, b[11]
        for _ in range(n):
            if i + 2 > len(b):
                break
            epc, pdc = b[i], b[i + 1]
            f["props"].append((epc, b[i + 2:i + 2 + pdc]))
            i += 2 + pdc
        return f

    def send(self, ip, deoj, esv, props, tid=None):
        if tid is None:
            self.tid = (self.tid + 1) & 0xFFFF
            tid = self.tid
        body = bytes([0x10, 0x81, tid >> 8, tid & 0xFF]) + bytes.fromhex("05FF01") + eoj_bytes(deoj) + bytes([esv, len(props)])
        for epc, edt in props:
            body += bytes([epc, len(edt)]) + edt
        self.sock.sendto(body, (ip, PORT))
        return tid

    def request(self, ip, deoj, esv, props, timeout=3.0):
        self.tid = (self.tid + 1) & 0xFFFF
        tid = self.tid
        w = {"ip": ip, "ev": threading.Event(), "frame": None}
        self.waiting[tid] = w
        try:
            self.send(ip, deoj, esv, props, tid)
            w["ev"].wait(timeout)
            return w["frame"]
        except OSError:
            return None
        finally:
            self.waiting.pop(tid, None)

    def get(self, ip, deoj, epcs, timeout=3.0):
        f = self.request(ip, deoj, 0x62, [(e, b"") for e in epcs], timeout)
        if not f:
            return None
        return {epc: edt for epc, edt in f["props"] if edt}

    def setc(self, ip, deoj, epc, edt):
        """1項目だけ設定する。成功なら None、失敗なら理由。
        エコキュートは返事が遅い機種があるので長めに待ち、2回まで送る。それでも返事がなければ
        返事のいらない設定（SetI）で送り、読み直して変わったかで確かめる。"""
        for _ in range(2):
            f = self.request(ip, deoj, 0x61, [(epc, edt)], timeout=8)
            if f:
                return f"値（{edt.hex().upper()}）を受け付けませんでした" if f["esv"] == 0x51 else None
        print(f"{ip} が {epc:02X} の設定に返事をしないため、返事のいらない設定で送ります", flush=True)
        f = self.request(ip, deoj, 0x60, [(epc, edt)], timeout=2)
        if f and f["esv"] == 0x50:
            return f"値（{edt.hex().upper()}）を受け付けませんでした"
        time.sleep(2)
        v = self.get(ip, deoj, [epc], timeout=8)
        if v is None or epc not in v:
            return f"返事がありません（{ip}）。エコキュートの通信アダプターの電源・Wi-Fi を確認してください"
        if v[epc] == edt:
            return None
        return f"設定が反映されませんでした（エコキュートの値：{v[epc].hex().upper()}）"

    def discover(self, seconds=4.0):
        """家の中の ECHONET Lite 機器を探す（マルチキャストと、同じネットワークの全アドレスへの問い合わせ）"""
        found = {}

        def sink(ip, f):
            if f["seoj"].startswith("0EF0"):
                for epc, edt in f["props"]:
                    if epc in (0xD6, 0xD5) and edt:
                        for k in range(edt[0]):
                            e = edt[1 + 3 * k:4 + 3 * k]
                            if len(e) == 3:
                                found.setdefault(ip, set()).add(e.hex().upper())
        self.sink = sink
        try:
            self.send(MCAST, "0EF001", 0x62, [(0xD6, b"")])
            me = local_ip()
            if me:
                net = ipaddress.ip_network(me + "/24", strict=False)
                for h in net.hosts():
                    if str(h) != me:
                        try:
                            self.send(str(h), "0EF001", 0x62, [(0xD6, b"")])
                        except OSError:
                            pass
            time.sleep(seconds)
        finally:
            self.sink = None
        return [{"ip": ip, "eojs": sorted(v)} for ip, v in sorted(found.items(), key=lambda x: int(x[0].split(".")[-1]))]


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.168.1.1", 9))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


def prop_map(edt):
    if not edt:
        return set()
    n = edt[0]
    if n < 16:
        return set(edt[1:1 + n])
    out = set()
    for i in range(16):
        if 1 + i >= len(edt):
            break
        for bit in range(8):
            if edt[1 + i] & (1 << bit):
                out.add(0x80 + bit * 0x10 + i)
    return out


el = None


# ================= 天気予報 =================

def forecast_sunny(day):
    lat, lon = conf.get("lat"), conf.get("lon")
    if lat is None or lon is None:
        raise RuntimeError("天気予報の場所が設定されていません（アプリの「おひさま沸き上げ」から、サーバーに自宅の位置を送ってください）")
    q = urllib.parse.urlencode({"latitude": lat, "longitude": lon, "daily": "weather_code,precipitation_probability_max",
                                "timezone": "Asia/Tokyo", "forecast_days": 3})
    with urllib.request.urlopen("https://api.open-meteo.com/v1/forecast?" + q, timeout=20) as r:
        j = json.load(r)
    days = j.get("daily", {}).get("time", [])
    codes = j.get("daily", {}).get("weather_code", [])
    for d, c in zip(days, codes):
        if d == day.isoformat():
            # 0 快晴 / 1 晴れ / 2 晴れ時々くもり → 晴れ扱い
            return c in (0, 1, 2), c
    raise RuntimeError(f"{day} の天気予報がありません")


# ================= おひさま沸き上げ =================

eco_caps = {"set": None}


def eco_read():
    ip, eoj = conf["eco_ip"], conf["eco_eoj"]
    if not ip:
        return None
    if eco_caps["set"] is None:
        m = el.get(ip, eoj, [0x9E])
        if m and 0x9E in m:
            eco_caps["set"] = prop_map(m[0x9E])
    v = el.get(ip, eoj, [0x80, 0xB0, 0xB2, 0xE1, 0xE2, 0xC0, 0xC7, 0xCA, 0xB6])
    if v is None:
        v = el.get(ip, eoj, [0xB0, 0xB2, 0xE1, 0xC7])
    eco = {"online": v is not None, "at": time.time()}
    if v:
        u8 = lambda e: v[e][0] if e in v and len(v[e]) >= 1 else None
        u16 = lambda e: int.from_bytes(v[e][:2], "big") if e in v and len(v[e]) >= 2 else None
        eco.update(heating=u8(0xB2) == 0x41 if u8(0xB2) is not None else None, manual=u8(0xB0) == 0x42 if u8(0xB0) is not None else None,
                   remain=u16(0xE1), tank=u16(0xE2), shift=u8(0xC7) == 0x41 if u8(0xC7) is not None else None,
                   shift_raw=u8(0xC7), shift_hour=u8(0xCA), tank_mode=u8(0xB6))
    s = eco_caps["set"]
    eco["can_manual"] = s is None or 0xB0 in s
    eco["can_shift"] = s is None or 0xC7 in s
    with lock:
        state["eco"] = eco
    return eco


def meter_surplus_w():
    ip = conf["meter_ip"]
    if not ip:
        return None
    # スマートメーター（0288）は 0xE7、分電盤（0287）は 0xC6 が家全体の瞬時電力
    epc = 0xC6 if str(conf["meter_eoj"]).upper().startswith("0287") else 0xE7
    v = el.get(ip, conf["meter_eoj"], [epc], timeout=4)
    if not v or epc not in v or len(v[epc]) < 4:
        return None
    w = struct.unpack(">i", v[epc][:4])[0]
    if w in (0x7FFFFFFE, -0x7FFFFFFF, 0x7FFFFFFF):
        return None
    return -float(w)  # 買電がプラス・売電がマイナス → 余剰（売電）をプラスに


def aiseg_surplus_w():
    """AiSEG2 の「エネルギーの流れ」画面のデータ（/data/electricflow/111/update）から、
    いまの発電（g_capacity kW）と消費（u_capacity kW）を読み、余剰（発電－消費。プラス＝売電）を返す"""
    base = str(conf.get("aiseg_url") or "").strip().rstrip("/")
    if not base:
        return None
    if not base.startswith("http"):
        base = "http://" + base
    pm = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    pm.add_password(None, base, conf.get("aiseg_user") or "aiseg", conf.get("aiseg_password") or "")
    opener = urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(pm), urllib.request.HTTPBasicAuthHandler(pm))
    body = urllib.parse.urlencode({"data": "{}"}).encode()
    try:
        with opener.open(urllib.request.Request(base + "/data/electricflow/111/update", data=body, method="POST"), timeout=8) as r:
            j = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        state["aiseg_error"] = "AiSEG2 のパスワードが違います" if e.code == 401 else f"AiSEG2 でエラーになりました（HTTP {e.code}）"
        return None
    except Exception as e:
        state["aiseg_error"] = f"AiSEG2（{base}）に届きません：{e}"
        return None

    def num(v):
        if v is None:
            return None
        if isinstance(v, (int, float)):
            return float(v)
        t = str(v).translate(str.maketrans("０１２３４５６７８９．，", "0123456789.,")).replace(",", "").strip()
        try:
            return float("".join(ch for ch in t if ch in "0123456789."))
        except ValueError:
            return None
    if j.get("measReg") == 0:
        state["aiseg_error"] = "AiSEG2 に計測ユニットが登録されていません"
        return None
    g, u = num(j.get("g_capacity")), num(j.get("u_capacity"))
    if g is None or u is None:
        state["aiseg_error"] = "AiSEG2 の発電・消費の値を読めませんでした"
        return None
    state["aiseg_error"] = None
    state["power"] = {"generation_w": g * 1000, "consumption_w": u * 1000,
                      "buy_sell": {0: "buy", 1: "sell", 2: "none"}.get(j.get("lo_buy_sell"), "unknown"), "from": "AiSEG2"}
    return (g - u) * 1000


def aiseg_open(path, post=False):
    base = str(conf.get("aiseg_url") or "").strip().rstrip("/")
    if not base.startswith("http"):
        base = "http://" + base
    pm = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    pm.add_password(None, base, conf.get("aiseg_user") or "aiseg", conf.get("aiseg_password") or "")
    opener = urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(pm), urllib.request.HTTPBasicAuthHandler(pm))
    req = urllib.request.Request(base + path, data=urllib.parse.urlencode({"data": "{}"}).encode() if post else None, method="POST" if post else "GET")
    with opener.open(req, timeout=10) as r:
        return r.read().decode("utf-8", "replace")


_CIRCUIT_RE = None


def aiseg_circuits():
    """AiSEG2 の回路ごとの、いまの電力（W）。/page/electricflow/1113?id=1,2,... を、同じページが返るまで読む"""
    import re
    global _CIRCUIT_RE
    if _CIRCUIT_RE is None:
        _CIRCUIT_RE = re.compile(r'<div class="c_value">(.*?)</div>\s*<div class="c_device">(.*?)</div>', re.S)
    zen = str.maketrans("０１２３４５６７８９．，", "0123456789.,")
    out, prev = {}, None
    for page in range(1, 21):
        html = aiseg_open(f"/page/electricflow/1113?id={page}")
        rows = []
        for val, dev in _CIRCUIT_RE.findall(html):
            name = re.sub(r"<[^>]+>", "", dev).replace("\u3000", " ").strip()
            name = re.sub(r"\s+", " ", name)
            if not name or name == "-":
                continue
            v = re.sub(r"[^0-9.]", "", val.translate(zen))
            rows.append((name, float(v) if v else 0.0))
        sig = ",".join(n for n, _ in rows)
        if not rows or sig == prev:
            break
        prev = sig
        for n, w in rows:
            out[n] = out.get(n, 0.0) + w
    return out


# ---- 時間ごと・回路ごとの電気の記録（AiSEG2 から1分ごとに読んで積み上げる） ----
energy = load_json(ENERGY_PATH, {"hours": {}})
energy_last = {"t": None}
ENERGY5_PATH = os.path.join(DIR, "energy5.json")
energy5 = load_json(ENERGY5_PATH, {})
eco_power = {"ok": None, "checked": 0}
eco_rest = {"base": None}


def eco_power_w():
    """エコキュートのいまの消費電力（W）。ECHONET Lite の瞬時消費電力（0x84）。対応していない機種は None"""
    if eco_power["ok"] is False and time.time() - eco_power["checked"] < 3600:
        return None
    try:
        v = el.get(conf["eco_ip"], conf["eco_eoj"], [0x84], timeout=3)
    except Exception:
        v = None
    eco_power["checked"] = time.time()
    if not v or 0x84 not in v or len(v[0x84]) < 2:
        eco_power["ok"] = False
        return None
    w = int.from_bytes(v[0x84][:2], "big")
    if w >= 0xFFFE:
        eco_power["ok"] = False
        return None
    eco_power["ok"] = True
    return float(w)


dev_power_fail = {}


def device_power_w(d):
    """ECHONET Lite の機器のいまの消費電力（0x84, W）。読めない機器は1時間ごとにだけ試す"""
    key = f'{d.get("ip")}/{d.get("eoj")}'
    if time.time() - dev_power_fail.get(key, 0) < 3600:
        return None
    try:
        v = el.get(d["ip"], d["eoj"], [0x84], timeout=2)
    except Exception:
        v = None
    if not v or 0x84 not in v or len(v[0x84]) < 2:
        dev_power_fail[key] = time.time()
        return None
    w = int.from_bytes(v[0x84][:2], "big")
    if w >= 0xFFFE:
        dev_power_fail[key] = time.time()
        return None
    dev_power_fail.pop(key, None)
    return float(w)


def aiseg_circuit_pages():
    """回路の一覧の確認用：AiSEG2 の回路のページごとの名前と、いまの電力"""
    import re
    out = []
    zen = str.maketrans("０１２３４５６７８９．，", "0123456789.,")
    prev = None
    for page in range(1, 21):
        html = aiseg_open(f"/page/electricflow/1113?id={page}")
        rows = []
        for val, dev in re.findall(r'<div class="c_value">(.*?)</div>\s*<div class="c_device">(.*?)</div>', html, re.S):
            name = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", dev).replace("\u3000", " ")).strip()
            rows.append({"name": name, "value": re.sub(r"<[^>]+>", "", val).translate(zen).strip()})
        sig = ",".join(r["name"] for r in rows)
        if not rows or sig == prev:
            break
        prev = sig
        out.append({"page": page, "rows": rows})
    return out


def energy5_report(day):
    with lock:
        return {"date": day, "slots": [dict(v, t=k[11:]) for k, v in sorted(energy5.items()) if k.startswith(day)]}


def energy_collect():
    now = time.time()
    use = gen = None
    circuits = {}
    src = None
    if conf.get("aiseg_url"):
        try:
            if aiseg_surplus_w() is not None:
                p = state.get("power") or {}
                use, gen, src = p.get("consumption_w"), p.get("generation_w"), "AiSEG2"
            circuits = aiseg_circuits()
        except Exception as e:
            state["aiseg_error"] = f"AiSEG2 の回路を読めませんでした：{e}"
    if use is None and conf.get("meter_ip"):
        w = meter_surplus_w()
        if w is not None:
            src = "スマートメーター"
            use, gen = (max(0.0, -w), 0.0)  # 買電だけ分かる（発電は分からない）
            state["power"] = {"generation_w": None, "consumption_w": None, "net_w": w, "from": src}
    # いまの電力が出る回路（100V）を覚えておく。出ない回路（200V）は「回路ごとの使用量（日）」の増えた分で記録する
    nz = [n for n, w in circuits.items() if w > 0]
    if nz:
        with lock:
            names = set(energy.get("inst_names") or [])
            if not names.issuperset(nz):
                energy["inst_names"] = sorted(names | set(nz))
    daily_ok = aiseg_daily["ok"]
    # エアコンなど、機器から直接読めるもの（AiSEG2 の回路ごとの使用量が読めないときだけ）
    for d in ([] if daily_ok else conf.get("power_devices") or []):
        w = device_power_w(d)
        if w is not None:
            nm = f'{d.get("name") or d.get("ip")}（直接）'
            circuits[nm] = circuits.get(nm, 0.0) + w
    # エコキュートの電気：AiSEG2 の回路に値が出ないときは、エコキュートから直接読む（ECHONET Lite の 0x84）。
    # それもできない機種は、沸き上げ中の「家全体 − 回路の合計」の増え方から推定する
    if src == "AiSEG2" and use is not None and not daily_ok:
        rest = max(0.0, use - sum(circuits.values()))
        if conf.get("eco_ip"):
            eco_w = sum(w for n, w in circuits.items() if "エコキュート" in n or "給湯" in n)
            heating = bool((state.get("eco") or {}).get("heating"))
            if eco_w <= 0:
                w = eco_power_w()
                if w is not None:
                    circuits["エコキュート（直接）"] = w
                    rest = max(0.0, rest - w)
                elif heating:
                    base = eco_rest["base"] if eco_rest["base"] is not None else 0.0
                    est = max(0.0, rest - base)
                    circuits["エコキュート（推定）"] = est
                    rest -= est
                if not heating:
                    # 沸き上げをしていないときの「回路に出ない分」をならしておく（推定の基準）
                    eco_rest["base"] = rest if eco_rest["base"] is None else eco_rest["base"] * 0.9 + rest * 0.1
        if rest > 0:
            circuits["その他（回路に出ない分）"] = rest
    last = energy_last["t"]
    energy_last["t"] = now
    if use is None or last is None:
        return
    dt = min(now - last, 180) / 3600.0  # 時間（h）。読めなかった間は長くても3分ぶんまで
    key = datetime.now().strftime("%Y-%m-%dT%H")
    with lock:
        h = energy["hours"].setdefault(key, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
        net = (gen or 0.0) - use if src == "AiSEG2" else -use
        h["use"] += (use if src == "AiSEG2" else 0.0) * dt
        h["gen"] += (gen or 0.0) * dt
        h["buy"] += max(0.0, -net) * dt
        h["sell"] += max(0.0, net) * dt
        for n, w in circuits.items():
            h["circuits"][n] = h["circuits"].get(n, 0.0) + w * dt
        h["src"] = src
        # 5分ごとの記録（8日分）
        n5 = datetime.now()
        k5 = n5.strftime("%Y-%m-%dT%H:") + f"{n5.minute // 5 * 5:02d}"
        f = energy5.setdefault(k5, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
        f["use"] += (use if src == "AiSEG2" else 0.0) * dt
        f["gen"] += (gen or 0.0) * dt
        f["buy"] += max(0.0, -net) * dt
        f["sell"] += max(0.0, net) * dt
        for n, w in circuits.items():
            f["circuits"][n] = f["circuits"].get(n, 0.0) + w * dt
        cut5 = (n5 - timedelta(days=8)).strftime("%Y-%m-%dT%H:%M")
        for k in [k for k in energy5 if k < cut5]:
            del energy5[k]
        cut = (datetime.now() - timedelta(days=40)).strftime("%Y-%m-%dT%H")
        for k in [k for k in energy["hours"] if k < cut]:
            del energy["hours"][k]


# ---- AiSEG2 の「回路ごとの使用電力量（日）」と「湯」の画面（200V の回路も入っている） ----
aiseg_daily = {"date": None, "last": {}, "ok": False, "at": 0, "pages": None}


def _graph_params(d):
    t = f"{d.year}/{d.month}/{d.day}"
    c = f"{d.year},{d.month},{d.day}"
    return urllib.parse.urlencode({"term": t, "termStr": "day", "day": c, "week": c, "month": c, "year": c})


def aiseg_daily_circuits(d=None):
    """その日の回路ごとの使用電力量（kWh）。582（1ページ目）と 583?id=2… を読む。読めなければ 581（上位5つ＋その他）"""
    import re
    d = d or date.today()
    rx = re.compile(r'<div class="a_label">(.*?)</div>\s*<div class="a_value">([0-9.,０-９．]*)\s*kWh</div>', re.S)
    zen = str.maketrans("０１２３４５６７８９．，", "0123456789.,")

    def rows(html):
        out = {}
        for name, val in rx.findall(html):
            n = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", name).replace("\u3000", " ")).strip()
            v = val.translate(zen).replace(",", "")
            if n and n != "-" and v:
                out[n] = out.get(n, 0.0) + float(v)
        return out
    q = _graph_params(d)
    full, prev_sig = {}, None
    paths = [f"/page/graph/582?{q}"] + [f"/page/graph/583?id={i}&{q}" for i in range(2, 12)]
    for path in paths:
        try:
            r = rows(aiseg_open(path))
        except Exception:
            break
        sig = ",".join(r)
        if not r or sig == prev_sig:
            break
        prev_sig = sig
        full.update(r)
    if full:
        return full, "all"
    r = rows(aiseg_open(f"/page/graph/581?{q}"))
    r.pop("その他", None)
    return r, "top5"


def aiseg_hot_hourly():
    """AiSEG2 の「湯」の画面：1時間ごとのお湯の使用量（L）。今日と昨日"""
    import re
    h = aiseg_open("/page/graph/55111")
    m = re.search(r"var graph_data\s*=\s*(\{.*?\});", h, re.S)
    if not m:
        return None
    j = json.loads(m.group(1))
    return {"today": j.get("total1") or [], "yesterday": j.get("total2") or []}


def aiseg_daily_tick():
    """5分ごと：回路ごとの1日の使用量の増えた分を、いまの時間・5分の記録に入れる（いまの電力が出ない 200V の回路のため）"""
    if not conf.get("aiseg_url"):
        return
    today = date.today()
    try:
        cur, kind = aiseg_daily_circuits(today)
    except Exception as e:
        aiseg_daily["ok"] = False
        print("AiSEG2 の回路ごとの使用量を読めませんでした:", e, flush=True)
        return
    aiseg_daily.update(ok=bool(cur), at=time.time(), pages=kind)
    with lock:
        day = energy.setdefault("days", {}).setdefault(today.isoformat(), {})
        day["circuits_kwh"] = {k: round(v, 3) for k, v in cur.items()}
        if aiseg_daily["date"] != today.isoformat():
            # 日が変わったとき・起動したときは、その時点を起点にする（それまでの分を1つの時間にまとめて入れない）
            aiseg_daily.update(date=today.isoformat(), last=dict(cur))
        else:
            inst = set(energy.get("inst_names") or [])
            now = datetime.now()
            hk = now.strftime("%Y-%m-%dT%H")
            k5 = now.strftime("%Y-%m-%dT%H:") + f"{now.minute // 5 * 5:02d}"
            h = energy["hours"].setdefault(hk, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
            f = energy5.setdefault(k5, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
            for n, kwh in cur.items():
                if n in inst:
                    continue  # いまの電力が読める回路は、そちらで記録している
                dwh = (kwh - aiseg_daily["last"].get(n, kwh)) * 1000
                if dwh > 0:
                    h["circuits"][n] = h["circuits"].get(n, 0.0) + dwh
                    f["circuits"][n] = f["circuits"].get(n, 0.0) + dwh
            aiseg_daily["last"] = dict(cur)
    # 過去7日の回路ごとの使用量も、まだ無ければ1日ずつ読んでおく（電気の分析の「回路別」用）
    for i in range(1, 8):
        dk = (today - timedelta(days=i)).isoformat()
        with lock:
            have = "circuits_kwh" in energy.setdefault("days", {}).setdefault(dk, {})
        if not have:
            try:
                past, _ = aiseg_daily_circuits(today - timedelta(days=i))
                with lock:
                    energy["days"][dk]["circuits_kwh"] = {k: round(v, 3) for k, v in past.items()}
            except Exception:
                pass
            break
    try:
        hot = aiseg_hot_hourly()
        if hot:
            with lock:
                state["hot_hourly"] = hot
                day = energy["days"].setdefault(today.isoformat(), {})
                day["hot_use_l"] = sum(x or 0 for x in hot["today"])
                y = (today - timedelta(days=1)).isoformat()
                energy["days"].setdefault(y, {})["hot_use_l"] = sum(x or 0 for x in hot["yesterday"])
                state["hot_from_aiseg"] = True
    except Exception as e:
        print("AiSEG2 の湯の量を読めませんでした:", e, flush=True)


def energy_loop():
    saved = time.time()
    while True:
        try:
            energy_collect()
        except Exception as e:
            print("電気の記録でエラー:", e, flush=True)
        if time.time() - aiseg_daily["at"] > 290:
            try:
                aiseg_daily_tick()
            except Exception as e:
                aiseg_daily["at"] = time.time()
                print("回路ごとの使用量でエラー:", e, flush=True)
        if time.time() - saved > 300:
            with lock:
                save_json(ENERGY_PATH, energy)
                save_json(ENERGY5_PATH, energy5)
            saved = time.time()
        time.sleep(60)


# ================= 電気料金（電力会社のプランの単価で計算） =================
TARIFF_PATHS = ["/usr/local/share/homepanel/tariffs.json", os.path.join(os.path.dirname(os.path.abspath(__file__)), "tariffs.json")]
_tariffs = {"path": None, "mtime": None, "data": {}}


def tariffs():
    """プランの単価表。サーバーの更新（homepanel-update）が毎日 GitHub から新しくする"""
    for p in TARIFF_PATHS:
        try:
            m = os.path.getmtime(p)
        except OSError:
            continue
        if _tariffs["path"] != p or _tariffs["mtime"] != m:
            _tariffs.update(path=p, mtime=m, data=load_json(p, {}) or {})
        return _tariffs["data"]
    return {}


def find_plan(pid):
    if pid == "custom":
        c = conf.get("tariff_custom") or {}
        day, night = float(c.get("day_rate") or 0), c.get("night_rate")
        bands = []
        if night not in (None, "", 0):
            ns, ne = int(c.get("night_start", 23)), int(c.get("night_end", 7))
            hours = [h % 24 for h in range(ns, ns + ((ne - ns) % 24 or 24))]
            bands.append({"name": f"夜間（{ns}〜{ne}時）", "rate": float(night), "when": [{"days": "all", "hours": hours}]})
        bands.append({"name": "昼間" if bands else "電力量料金", "rate": day})
        return {"id": "custom", "name": "自分で入力"}, {"id": "custom", "name": "自分で入力した単価", "contract": "none",
                                                     "versions": [{"from": "2000-01-01", "basic": {"type": "fixed", "yen": float(c.get("basic") or 0)},
                                                                   "energy": {"type": "tou", "bands": bands}}]}
    for pr in tariffs().get("providers", []):
        for pl in pr.get("plans", []):
            if pl.get("id") == pid:
                return pr, pl
    return None, None


def plan_version(pl, d):
    vs = sorted(pl.get("versions") or [], key=lambda v: v.get("from", ""))
    cur = vs[0] if vs else {}
    for v in vs:
        if v.get("from", "") <= d.isoformat():
            cur = v
    return cur


def is_holiday(pl, d):
    hd = pl.get("holiday") or {"sat": True, "sun": True, "national": True, "extra": []}
    wd = d.weekday()
    if (wd == 5 and hd.get("sat")) or (wd == 6 and hd.get("sun")):
        return True
    if hd.get("national") and d.isoformat() in set(tariffs().get("holidays") or []):
        return True
    return d.strftime("%m-%d") in (hd.get("extra") or [])


def band_of(pl, ver, dt):
    """その時刻の単価（円/kWh）と時間帯の名前。段階料金（従量電灯）なら None"""
    en = ver.get("energy") or {}
    if en.get("type") != "tou":
        return None, None
    season = "other"
    for name, months in (pl.get("seasons") or {}).items():
        if dt.month in months:
            season = name
    hol = is_holiday(pl, dt.date())
    for b in en.get("bands") or []:
        when = b.get("when")
        ok = not when
        for w in when or []:
            days = w.get("days", "all")
            if days == "weekday" and hol or days == "holiday" and not hol:
                continue
            hours = w.get("hours", "rest")
            if hours == "rest" or dt.hour in hours:
                ok = True
                break
        if ok:
            r = b.get("rate")
            if isinstance(r, dict):
                r = r.get(season, r.get("other"))
            return float(r or 0), b.get("name")
    return 0.0, None


def surcharge_of(d):
    ym, val = d.strftime("%Y-%m"), 0.0
    for x in sorted(tariffs().get("surcharge") or [], key=lambda x: x["from"]):
        if x["from"] <= ym:
            val = float(x["yen"])
    return val


def subsidy_of(d):
    ym = d.strftime("%Y-%m")
    return next((float(x["yen"]) for x in tariffs().get("subsidy") or [] if x.get("month") == ym), 0.0)


def adders_of(pr, dt):
    """1kWh あたりに足す額（再エネ賦課金＋燃料費調整−国の補助）と、燃料費調整が電力会社の公表値か"""
    ym = dt.strftime("%Y-%m")
    f = ((pr or {}).get("fuel") or {})
    auto = (f.get("by_usage_month") or {}).get(ym)
    if auto is not None:
        sub = 0.0 if f.get("includes_subsidy") else subsidy_of(dt)
        return surcharge_of(dt) + float(auto) - sub, float(auto), True
    fuel = float(conf.get("tariff_fuel") or 0)
    return surcharge_of(dt) + fuel - subsidy_of(dt), fuel, False


def discount_of(pl):
    on = set(conf.get("tariff_discounts") or [])
    return sum(float(d.get("pct") or 0) for d in pl.get("discounts") or [] if d.get("id") in on) / 100.0


def basic_of(pl, ver):
    b = ver.get("basic") or {}
    c = float(conf.get("tariff_contract") or 0)
    t = b.get("type")
    if t == "per_10a":
        return float(b["yen"]) * (c or 30) / 10
    if t in ("per_kva", "per_kw"):
        return float(b["yen"]) * (c or 6)
    if t == "kw_table":
        c = c or 10
        for lim, yen in b.get("table") or []:
            if c <= lim:
                return float(yen)
        return float((b.get("table") or [[0, 0]])[-1][1]) + (c - float(b.get("over_from", 0))) * float(b.get("per_kw_over", 0))
    if t in ("minimum", "fixed"):
        return float(b.get("yen") or 0)
    return 0.0


def cost_hours(hours):
    """時間ごとの記録（古い順、各 {h, buy, sell}）に、買った電気の料金（円）を付ける。段階料金は月の使用量の合計で決まる"""
    pr, pl = find_plan(conf.get("tariff_plan") or "")
    if not pl:
        return None
    sell_price = float(conf.get("tariff_sell") or 0)
    disc = discount_of(pl)
    months, cum = {}, {}
    for h in hours:
        try:
            dt = datetime.strptime(h["h"], "%Y-%m-%dT%H")
        except Exception:
            continue
        ver = plan_version(pl, dt.date())
        kwh = float(h.get("buy") or 0) / 1000
        ym = dt.strftime("%Y-%m")
        before = cum.get(ym, 0.0)
        cum[ym] = before + kwh
        rate, band = band_of(pl, ver, dt)
        en = ver.get("energy") or {}
        if rate is None:
            # 段階料金：月の合計がどの段にあるかで単価が変わる
            free = float((ver.get("basic") or {}).get("kwh") or 0) if (ver.get("basic") or {}).get("type") == "minimum" else 0.0
            yen, lo = 0.0, before
            hi = before + kwh
            low = free
            for lim, r in en.get("tiers") or []:
                top = float(lim) if lim is not None else 1e12
                a, b = max(lo, low), min(hi, top)
                if b > a:
                    yen += (b - a) * float(r)
                low = top
            rate = yen / kwh if kwh > 0 else next((float(r) for lim, r in en.get("tiers") or [] if lim is None or before < lim), 0.0)
            band = "段階料金"
        else:
            yen = kwh * rate
        yen *= (1 - disc)
        rate *= (1 - disc)
        add, _, _ = adders_of(pr, dt)
        yen += kwh * add
        h["yen"] = round(yen, 2)
        h["rate"] = round(rate + add, 2)
        h["band"] = band
        h["sell_yen"] = round(float(h.get("sell") or 0) / 1000 * sell_price, 2)
        m = months.setdefault(ym, {"kwh": 0.0, "energy_yen": 0.0, "sell_kwh": 0.0, "sell_yen": 0.0, "basic_yen": round(basic_of(pl, ver) * (1 - disc), 0)})
        m["kwh"] += kwh
        m["energy_yen"] += yen
        m["sell_kwh"] += float(h.get("sell") or 0) / 1000
        m["sell_yen"] += h["sell_yen"]
    for m in months.values():
        m["total_yen"] = round(m["energy_yen"] + m["basic_yen"])
        for k in ("kwh", "energy_yen", "sell_kwh", "sell_yen"):
            m[k] = round(m[k], 2)
    _, fuel_now, fuel_auto = adders_of(pr, datetime.now())
    return {"provider": pr.get("name"), "plan": pl.get("name"), "plan_id": pl.get("id"), "months": months,
            "fuel": fuel_now, "fuel_auto": fuel_auto, "discount_pct": round(disc * 100, 1), "sell_price": sell_price, "surcharge": surcharge_of(date.today()), "subsidy": subsidy_of(date.today()),
            "checked": pl.get("checked"), "updated": tariffs().get("updated")}


def cost_summary():
    """今日・今月の電気料金（ホーム画面用）"""
    ym = datetime.now().strftime("%Y-%m")
    with lock:
        hours = [dict(v, h=k) for k, v in sorted(energy["hours"].items()) if k.startswith(ym)]
    c = cost_hours(hours)
    if c is None:
        return None
    today = date.today().isoformat()
    th = [h for h in hours if h["h"].startswith(today)]
    pr, pl = find_plan(conf.get("tariff_plan") or "")
    now = datetime.now()
    rate, band = band_of(pl, plan_version(pl, now.date()), now)
    add, _, _ = adders_of(pr, now)
    m = c["months"].get(ym) or {}
    days_in = (date(now.year + (now.month == 12), now.month % 12 + 1, 1) - timedelta(days=1)).day
    return {"plan": f'{c["provider"]} {c["plan"]}', "today_yen": round(sum(h.get("yen", 0) for h in th)),
            "today_kwh": round(sum(float(h.get("buy") or 0) for h in th) / 1000, 2),
            "today_sell_yen": round(sum(h.get("sell_yen", 0) for h in th)),
            "month_yen": m.get("total_yen"), "month_kwh": m.get("kwh"), "month_sell_yen": round(m.get("sell_yen", 0)),
            "month_estimate_yen": round((m.get("energy_yen", 0) / max(1, now.day - 1 + now.hour / 24)) * days_in + m.get("basic_yen", 0)) if m else None,
            "rate_now": round(rate * (1 - discount_of(pl)) + add, 2) if rate is not None else None,
            "band_now": band}


def tariff_list():
    t = tariffs()
    out = [{"id": pr["id"], "name": pr["name"], "plans": [{"id": pl["id"], "name": pl["name"], "contract": pl.get("contract", "none"), "checked": pl.get("checked")}
                                                       for pl in pr.get("plans", [])]} for pr in t.get("providers", [])]
    pr, pl = find_plan(conf.get("tariff_plan") or "")
    detail = None
    if pl:
        ver = plan_version(pl, date.today())
        en = ver.get("energy") or {}
        if en.get("type") == "tou":
            detail = [{"name": b.get("name"), "rate": b.get("rate")} for b in en.get("bands") or []]
        else:
            detail = [{"name": (f"{lim}kWhまで" if lim else "それより上"), "rate": r} for lim, r in en.get("tiers") or []]
        _, fuel_now, fuel_auto = adders_of(pr, datetime.now())
        on = set(conf.get("tariff_discounts") or [])
        detail = {"bands": detail, "basic_yen": round(basic_of(pl, ver)), "from": ver.get("from"), "contract": pl.get("contract", "none"),
                  "note": pl.get("note"), "fuel": fuel_now, "fuel_auto": fuel_auto, "fuel_source": ((pr or {}).get("fuel") or {}).get("source"),
                  "discounts": [dict(d, on=d.get("id") in on) for d in pl.get("discounts") or []]}
    return {"providers": out, "note": t.get("note"), "updated": t.get("updated"), "current": detail,
            "surcharge": surcharge_of(date.today()), "subsidy": subsidy_of(date.today())}


def energy_report(days):
    days = max(1, min(40, int(days)))
    start = (datetime.now() - timedelta(days=days - 1)).strftime("%Y-%m-%dT00")
    dstart = start[:10]
    with lock:
        allh = [dict(v, h=k) for k, v in sorted(energy["hours"].items())]
        daily = {k: dict(v) for k, v in sorted(energy.get("days", {}).items()) if k >= dstart}
    # 料金は月の初めから順に計算する（段階料金のため）
    first = start[:7]
    cost = cost_hours([h for h in allh if h["h"][:7] >= first])
    hours = [h for h in allh if h["h"] >= start]
    if cost:
        for h in hours:
            d = daily.setdefault(h["h"][:10], {})
            d["yen"] = round(d.get("yen", 0) + h.get("yen", 0), 2)
            d["sell_yen"] = round(d.get("sell_yen", 0) + h.get("sell_yen", 0), 2)
    return {"hours": hours, "days": days, "daily": daily, "cost": cost}


# ---- 日ごとの天気・気温（Open-Meteo。過去2日と今日・明日。3時間ごとに更新して保存） ----
def weather_loop():
    while True:
        try:
            lat, lon = conf.get("lat"), conf.get("lon")
            if lat is not None and lon is not None:
                q = urllib.parse.urlencode({"latitude": lat, "longitude": lon, "timezone": "Asia/Tokyo", "past_days": 2, "forecast_days": 2,
                                            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,sunshine_duration"})
                with urllib.request.urlopen("https://api.open-meteo.com/v1/forecast?" + q, timeout=20) as r:
                    j = json.load(r).get("daily", {})
                with lock:
                    days = energy.setdefault("days", {})
                    for i, d in enumerate(j.get("time", [])):
                        day = days.setdefault(d, {})
                        pick = lambda k: (j.get(k) or [None] * 99)[i]
                        day["weather_code"] = pick("weather_code")
                        day["tmax"] = pick("temperature_2m_max")
                        day["tmin"] = pick("temperature_2m_min")
                        day["rain_mm"] = pick("precipitation_sum")
                        sun = pick("sunshine_duration")
                        day["sun_h"] = round(sun / 3600, 1) if sun is not None else None
                    cut = (date.today() - timedelta(days=60)).isoformat()
                    for k in [k for k in days if k < cut]:
                        del days[k]
                    save_json(ENERGY_PATH, energy)
        except Exception as e:
            print("天気の記録でエラー:", e, flush=True)
        time.sleep(3 * 3600)


# ================= エアコンの自動温度調整（Home Assistant 経由） =================
climate_state = {}


def ha_req(method, path, body=None):
    base = str(conf.get("ha_url") or "").rstrip("/")
    tok = conf.get("ha_token") or ""
    if not base or not tok:
        raise RuntimeError("Home Assistant のトークンが登録されていません（アプリの「エアコンの自動温度調整」で登録してください）")
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        t = r.read().decode("utf-8", "replace")
        return json.loads(t) if t else None


def ha_state(eid):
    return ha_req("GET", "/api/states/" + eid)


def ha_call(domain, service, data):
    return ha_req("POST", f"/api/services/{domain}/{service}", data)


def climate_tick():
    if not conf.get("climate_enabled"):
        for cs in climate_state.values():
            cs["status"] = "自動温度調整は全体がオフです"
        return
    now = time.time()
    for r in conf.get("climate_rules") or []:
        eid = r.get("entity")
        if not eid:
            continue
        cs = climate_state.setdefault(eid, {"actions": [], "temps": [], "last_cmd": 0, "last_mode": None, "last_target": None,
                                            "manual_until": 0, "out_since": 0, "boost": 0, "by_us": False})
        if not r.get("enabled"):
            cs["status"] = "この部屋の自動温度調整はオフです"
            continue
        try:
            ac = ha_state(eid)
            src = r.get("source") or ""
            if src:
                sv = ha_state(src)
                temp = float(sv["state"]) if sv and sv.get("state") not in ("unknown", "unavailable", None) else None
                src_name = (sv.get("attributes") or {}).get("friendly_name", src) if sv else src
            else:
                temp = (ac.get("attributes") or {}).get("current_temperature")
                src_name = "エアコンの室温"
        except Exception as e:
            cs["status"] = f"Home Assistant から読めませんでした：{e}"
            continue
        a = ac.get("attributes") or {}
        mode, target = ac.get("state"), a.get("temperature")
        cs.update(name=a.get("friendly_name", eid), mode=mode, target=target, temp=temp, source_name=src_name, at=now)
        if temp is None:
            cs["status"] = f"温度（{src_name}）が読めません"
            continue
        temp = float(temp)
        cs["temps"] = [p for p in cs["temps"] if p[0] > now - 86400]
        if not cs["temps"] or now - cs["temps"][-1][0] >= 600:
            cs["temps"].append([now, temp, mode, target])
        # 人（アプリ・リモコン・Home Assistant）が操作したら、しばらくその設定を優先する。
        # 以前は「このサーバーが操作したあと」に変わったときだけ気づいていたため、人が切ってもすぐに入れ直すことがあった
        seen = cs.get("seen")
        changed = seen is not None and (mode != seen[0] or (target is not None and seen[1] is not None and target != seen[1]))
        cs["seen"] = [mode, target]
        if changed and not (cs["last_cmd"] and now - cs["last_cmd"] < 90):
            mins = 120 if mode == "off" else 30
            cs["manual_until"] = now + mins * 60
            cs["last_cmd"] = 0
            cs["by_us"] = False
            what = "止められた" if mode == "off" else "操作された"
            cs["actions"] = ([[now, f"手で{what}ので{mins // 60 if mins >= 60 else mins}{'時間' if mins >= 60 else '分'}はそのままにします"]] + cs["actions"])[:50]
            log(f"温度調整：{cs.get('name') or eid} が手で{what}ので、{mins}分間は自動で動かしません")
        if now < cs["manual_until"]:
            cs["status"] = f"いま {temp:.1f}℃（{src_name}）・手で操作されたので {datetime.fromtimestamp(cs['manual_until']).strftime('%H:%M')} まではそのままにしています"
            continue
        lo, hi = float(r.get("min", 20)), float(r.get("max", 27))
        modes = a.get("hvac_modes") or ["cool", "heat", "off"]

        def cmd(new_mode, new_target, what):
            if cs["last_cmd"] and now - cs["last_cmd"] < 300:
                return
            if new_mode is None:
                ha_call("climate", "turn_off", {"entity_id": eid})
            else:
                if mode != new_mode:
                    ha_call("climate", "set_hvac_mode", {"entity_id": eid, "hvac_mode": new_mode})
                if new_target is not None and target != new_target:
                    ha_call("climate", "set_temperature", {"entity_id": eid, "temperature": new_target})
            cs["last_cmd"], cs["last_mode"] = now, new_mode or "off"
            cs["last_target"] = new_target if new_target is not None else target
            msg = f"{what}（{temp:.1f}℃・{src_name}）"
            cs["actions"] = ([[now, msg]] + cs["actions"])[:50]
            log(f"温度調整：{cs['name']} を{msg}")

        try:
            if temp > hi + 0.5 and r.get("cool", True) and "cool" in modes:
                cs["out_since"] = cs["out_since"] or now
                if src and now - cs["out_since"] > 1200 and cs["boost"] < 3 and mode == "cool":
                    cs["boost"] += 1
                    cs["out_since"] = now
                t = hi - cs["boost"]
                cmd("cool", t, f"冷房 {t:g}℃ にしました")
                cs["by_us"] = True
                cs["status"] = f"いま {temp:.1f}℃（{src_name}）：上限 {hi:g}℃ を超えたので冷房中（設定 {t:g}℃）"
            elif temp < lo - 0.5 and r.get("heat", True) and "heat" in modes:
                cs["out_since"] = cs["out_since"] or now
                if src and now - cs["out_since"] > 1200 and cs["boost"] < 3 and mode == "heat":
                    cs["boost"] += 1
                    cs["out_since"] = now
                t = lo + cs["boost"]
                cmd("heat", t, f"暖房 {t:g}℃ にしました")
                cs["by_us"] = True
                cs["status"] = f"いま {temp:.1f}℃（{src_name}）：下限 {lo:g}℃ を下回ったので暖房中（設定 {t:g}℃）"
            else:
                cs["out_since"] = 0
                cs["boost"] = 0
                if r.get("off") and cs["by_us"] and mode != "off" and abs(temp - (lo + hi) / 2) <= (hi - lo) / 4:
                    cmd(None, None, "止めました（範囲の真ん中に戻ったため）")
                    cs["by_us"] = False
                cs["status"] = f"いま {temp:.1f}℃（{src_name}）：範囲（{lo:g}〜{hi:g}℃）の中です"
        except Exception as e:
            cs["status"] = f"エアコンを操作できませんでした：{e}"


# ================= 機器のタイマー（「1時間後に切る」「6時半に暖房」など。Home Assistant 経由で操作） =================
TIMERS_PATH = os.path.join(DIR, "device_timers.json")
device_timers = load_json(TIMERS_PATH, [])
ACTION_NAME = {"turn_on": "つける", "turn_off": "切る", "toggle": "切り替える"}
HVAC_NAME = {"cool": "冷房", "heat": "暖房", "dry": "除湿", "fan_only": "送風", "auto": "自動", "heat_cool": "自動", "off": "停止"}


def timer_label(t):
    a = t.get("action")
    if a == "set_hvac_mode":
        mode = (t.get("data") or {}).get("hvac_mode")
        temp = (t.get("data") or {}).get("temperature")
        if mode == "off":
            return "止める"
        return HVAC_NAME.get(mode, mode or "") + (f" {temp:g}℃" if isinstance(temp, (int, float)) else "") + "にする"
    return ACTION_NAME.get(a, a or "")


def run_device_timer(t):
    eid = t["entity"]
    domain = eid.split(".")[0]
    a = t.get("action")
    data = dict(t.get("data") or {})
    if a == "set_hvac_mode":
        temp = data.pop("temperature", None)
        if data.get("hvac_mode") == "off":
            ha_call("climate", "turn_off", {"entity_id": eid})
        else:
            ha_call("climate", "set_hvac_mode", {"entity_id": eid, "hvac_mode": data.get("hvac_mode")})
            if temp is not None:
                ha_call("climate", "set_temperature", {"entity_id": eid, "temperature": temp})
    else:
        svc_domain = "homeassistant" if domain in ("switch", "light", "fan", "input_boolean", "climate", "cover", "media_player") else domain
        ha_call(svc_domain, a, {"entity_id": eid})


def device_timer_loop():
    while True:
        try:
            now = time.time()
            due = [t for t in list(device_timers) if t.get("at", 0) <= now]
            for t in due:
                try:
                    run_device_timer(t)
                    log(f"タイマー：{t.get('name') or t['entity']} を{timer_label(t)}（{t.get('by') or '画面'}で設定）")
                except Exception as e:
                    log(f"タイマー：{t.get('name') or t['entity']} を{timer_label(t)}ことができませんでした（{e}）")
                with lock:
                    if t in device_timers:
                        device_timers.remove(t)
                    save_json(TIMERS_PATH, device_timers)
        except Exception as e:
            print("タイマーでエラー:", e, flush=True)
        time.sleep(10)


def timers_public():
    with lock:
        out = [dict(t, label=timer_label(t)) for t in sorted(device_timers, key=lambda x: x.get("at", 0))]
    return {"timers": out, "ha_token_set": bool(conf.get("ha_token")), "now": time.time()}


def climate_loop():
    while True:
        try:
            climate_tick()
        except Exception as e:
            print("温度調整でエラー:", e, flush=True)
        time.sleep(60)


def climate_public():
    out = {}
    for eid, cs in climate_state.items():
        out[eid] = {k: cs.get(k) for k in ("status", "name", "mode", "target", "temp", "source_name", "at", "actions", "temps")}
    return {"enabled": bool(conf.get("climate_enabled")), "rules": conf.get("climate_rules") or [], "state": out,
            "ha_token_set": bool(conf.get("ha_token"))}


def read_surplus():
    """余剰（W）。AiSEG2 が設定されていればそれを、なければスマートメーター（ECHONET Lite）を使う"""
    if conf.get("aiseg_url"):
        v = aiseg_surplus_w()
        if v is not None:
            return v, "AiSEG2"
    if conf.get("meter_ip"):
        v = meter_surplus_w()
        if v is not None:
            return v, "スマートメーター"
    return None, None


def set_each(props):
    out = {}
    s = eco_caps["set"]
    for epc, edt in props:
        if epc not in SAFE_SET:
            continue
        if s is not None and epc not in s:
            out[epc] = "この機種は設定に対応していません"
            continue
        out[epc] = el.setc(conf["eco_ip"], conf["eco_eoj"], epc, edt)
    return out


retry_at = [0.0]
morning_checked = [""]


def plan_target():
    now = datetime.now()
    return now.date() if now.hour < 5 else now.date() + timedelta(days=1)


def run_plan(manual=False):
    target = plan_target()

    def result(ok, msg, sunny=None):
        with lock:
            state["plan"] = {"date": target.isoformat(), "ok": ok, "message": msg, "at": time.time(), "sunny": sunny}
        retry_at[0] = 0 if ok else time.time() + 600
        log(("" if msg.startswith("⚠") else "✓ " if ok else "✕ ") + msg)
        return ok

    if not conf["shift"]:
        return result(True, "夜の沸き上げを昼にずらす設定はオフです（いつもどおり夜に沸かします）")
    if not conf["eco_ip"]:
        return result(False, "エコキュートの IP アドレスが設定されていません")
    eco = eco_read()
    if not eco or not eco.get("online"):
        return result(False, f"エコキュート（{conf['eco_ip']}）から返事がありません" + ("" if manual else "（10分後にもう一度試します）"))
    if manual:
        state["shift_unsupported"] = False
    name = "今日" if target == date.today() else "明日"
    try:
        sunny, code = forecast_sunny(target)
    except Exception as e:
        return result(False, f"{name}の天気予報を読めませんでした：{e}")
    if conf.get("night_method") == "stop":
        return stop_plan(sunny, name, eco, result)
    if state.get("shift_unsupported") or (eco_caps["set"] is not None and 0xC7 not in eco_caps["set"]):
        return tank_plan(sunny, name, eco, result)
    if sunny:
        props = [(0xC7, b"\x41"), (0xC9, b"\x01"), (0xCA, bytes([max(9, min(17, int(conf["start_hour"])))]))]
    else:
        props = [(0xC7, b"\x42"), (0xCA, b"\x00")]
    res = set_each(props)
    if res.get(0xC7) and "受け付けませんでした" in res[0xC7]:
        # 時刻・回数を先に設定してから、もう一度参加を送る（順番で受け付ける機種があるため）
        res.update(set_each([pp for pp in props if pp[0] != 0xC7][::-1]))
        res.update(set_each([pp for pp in props if pp[0] == 0xC7]))
        if res.get(0xC7) and "受け付けませんでした" in res[0xC7]:
            state["shift_unsupported"] = True
            save_json(STATE_PATH, {k: state.get(k) for k in ("plan", "log", "by_us", "shift_unsupported", "tank_by_us", "night_stop", "night_pending")})
            log("エネルギーシフトを受け付けない機種のため、タンクの運転モードで夜の沸き上げを調整します（エコキュートの応答：" + res[0xC7] + "）")
            return tank_plan(sunny, name, eco, result)
    if res.get(0xC7):
        return result(False, f"設定できませんでした（{EPC_NAME[0xC7]}：{res[0xC7]}）" + ("" if manual else "（10分後にもう一度試します）"), sunny)
    sub = {k: v for k, v in res.items() if k != 0xC7 and v}
    if sub:
        log("補足：" + "、".join(f"{EPC_NAME[k]}は{v}" for k, v in sub.items()) + "（エコキュートの決まった時刻で沸かします）")
    time.sleep(1.5)
    eco = eco_read()
    want = 0x41 if sunny else 0x42
    if eco and eco.get("shift_raw") is not None and eco["shift_raw"] != want:
        return result(False, f"設定したはずの内容が反映されていません（エコキュートの応答：{eco['shift_raw']:02X}）", sunny)
    if sunny:
        return result(True, f"{name}は晴れの予報：夜の沸き上げを減らして、{conf['start_hour']}時から昼に沸かす設定にしました", True)
    return result(True, f"{name}は晴れない予報：いつもどおり夜に沸かす設定にしました", False)


def tank_plan(sunny, name, eco, result):
    """エネルギーシフトが使えない機種：晴れの予報の夜だけタンクの運転モードを「節約」にして夜の沸き上げを減らす。
    昼の時間帯が終わったら「標準」に戻す。残りのお湯が少ないときは減らさない。"""
    unsup = ("⚠ このエコキュートは、夜の沸き上げを昼にずらす設定（エネルギーシフト）も、タンクの運転モードも外から変えられない機種です。"
             "夜はいつもどおり沸かし、昼に余った電気での沸き増しだけ行います（夜の沸き上げを減らしたいときは、エコキュートのアプリ・リモコンの昼間沸き上げやソーラーチャージなどの設定を使ってください）")
    s = eco_caps["set"]
    if s is not None and 0xB6 not in s:
        return result(True, unsup, sunny)
    remain, tank = (eco or {}).get("remain"), (eco or {}).get("tank")
    pct = remain * 100 // tank if remain is not None and tank else None
    if sunny and pct is not None and pct < 40:
        err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB6, b"\x41")
        return result(True, f"{name}は晴れの予報ですが、残りのお湯が少ない（{pct}%）ため、夜はいつもどおり沸かします（タンクの運転モード：標準）", sunny)
    want = 0x42 if sunny else 0x41
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB6, bytes([want]))
    if err and "受け付けませんでした" in err:
        return result(True, unsup + f"（タンクの運転モード：{err}）", sunny)
    if err:
        return result(False, f"タンクの運転モードを設定できませんでした：{err}（10分後にもう一度試します）", sunny)
    with lock:
        state["tank_by_us"] = sunny
        state["plan_method"] = "tank"
    if sunny:
        return result(True, f"{name}は晴れの予報：タンクの運転モードを「節約」にして夜の沸き上げを減らし、昼に余った電気で沸き増しします（昼の時間帯のあと「標準」に戻します）", True)
    return result(True, f"{name}は晴れない予報：タンクの運転モードを「標準」にして、いつもどおり夜に沸かします", False)


# ---- お湯の使用量（残りのお湯の減り方から、日ごとに積み上げる） ----
hot_last = {"remain": None}


def hot_water_track(eco):
    r = (eco or {}).get("remain")
    if r is None:
        return
    prev = hot_last["remain"]
    hot_last["remain"] = r
    if prev is None:
        return
    d = date.today().isoformat()
    with lock:
        day = energy.setdefault("days", {}).setdefault(d, {})
        if r < prev and not state.get("hot_from_aiseg"):
            day["hot_use_l"] = day.get("hot_use_l", 0) + (prev - r)
        elif r > prev:
            day["hot_heat_l"] = day.get("hot_heat_l", 0) + (r - prev)
        day["hot_tank_l"] = (eco or {}).get("tank")
        # 残りのお湯の変化（5分ごと・2日分）。エコキュートの画面のグラフ用
        hist = energy.setdefault("hot", [])
        now = time.time()
        if not hist or now - hist[-1][0] >= 300 or abs(hist[-1][1] - r) >= 20:
            hist.append([int(now), r])
            cut = now - 2 * 86400
            while hist and hist[0][0] < cut:
                hist.pop(0)


def remain_pct(eco):
    r, t = (eco or {}).get("remain"), (eco or {}).get("tank")
    return r * 100 // t if r is not None and t else None


def stop_plan(sunny, name, eco, result):
    """このサーバーが夜の沸き上げを手動で止める（沸き上げ自動設定 B0=0x43「手動沸き上げ停止」）。
    翌日が晴れで、残りのお湯が十分なときだけ止める。止めたら、昼に余った電気で沸かし、昼の時間帯の終わりに自動へ戻す。"""
    s = eco_caps["set"]
    if s is not None and 0xB0 not in s:
        return result(True, "⚠ このエコキュートは、沸き上げの自動・停止を外から変えられない機種です（夜の沸き上げを止められません）", sunny)
    pct = remain_pct(eco)
    if not sunny:
        state["night_pending"] = False
        if state.get("night_stop"):
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
            state["night_stop"] = False
        return result(True, f"{name}は晴れない予報：夜はいつもどおり沸かします（沸き上げ：自動）", False)
    target_pct = int(conf.get("night_heat_pct", 50))
    tank = (eco or {}).get("tank")
    lit = lambda q: f"{q}%（約{tank * q // 100}L）" if tank else f"{q}%"
    if pct is not None and pct < target_pct:
        # まだ少ないので、夜は目標の量まで沸かしてから止める
        el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
        with lock:
            state["night_pending"] = True
            state["night_stop"] = False
            state["plan_method"] = "stop"
        return result(True, f"{name}は晴れの予報：夜は {lit(target_pct)} まで沸かしたら止めます（いま {lit(pct)}）。"
                            f"昼の{conf['start_hour']}時から余った電気で沸かし、{conf['end_hour']}時に自動に戻します", True)
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
    if err:
        return result(False, f"夜の沸き上げを止められませんでした（{EPC_NAME[0xB0]}：{err}）（10分後にもう一度試します）", True)
    with lock:
        state["night_stop"] = True
        state["plan_method"] = "stop"
    return result(True, f"{name}は晴れの予報：夜の沸き上げを止めました（残り {pct if pct is not None else '?'}%）。"
                        f"昼の{conf['start_hour']}時から余った電気で沸かし、{conf['end_hour']}時に自動に戻します"
                        f"（夜中に残りが{conf.get('safety_pct', 30)}%より減ったら、止めるのをやめて沸かします）", True)


def eco_pause(reason):
    """夜止めのときの沸き増しの終わり：自動に戻さず、止めたままにする（昼の高い電気で沸かさないため）"""
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
    log(f"沸き増しを止めました（{reason}）" if not err else f"沸き増しを止められませんでした（{err}）")
    with lock:
        state["by_us"] = False


counters = {"over": 0, "buy": 0}


def eco_auto(reason):
    if not state["eco"].get("can_manual", True):
        return
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
    log(f"自動の沸き上げに戻しました（{reason}）" if not err else f"自動の沸き上げに戻せませんでした（{err}）")
    with lock:
        state["by_us"] = False


def eco_start(surplus):
    s = eco_caps["set"]
    if s is None or 0xC0 in s:
        el.setc(conf["eco_ip"], conf["eco_eoj"], 0xC0, b"\x41")
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
    if err:
        log(f"沸き増しを始められませんでした（{err}）")
        return False
    log(f"売電 {surplus / 1000:.1f}kW：余った電気で沸き増しを始めました")
    with lock:
        state["by_us"] = True
    return True


def diagnose():
    """エコキュートが何を読めて、何を設定できるかを調べる"""
    ip, eoj = conf["eco_ip"], conf["eco_eoj"]
    if not ip:
        return "エコキュートの IP アドレスが設定されていません"
    lines = [f"【エコキュート（{ip}・{eoj}）】"]
    m = el.get(ip, eoj, [0x9E, 0x9F], timeout=5)
    setm = prop_map(m[0x9E]) if m and 0x9E in m else None
    getm = prop_map(m[0x9F]) if m and 0x9F in m else None
    if setm is not None:
        eco_caps["set"] = setm
    else:
        lines.append("設定できる項目の一覧を読めませんでした" + ("（返事がありません）" if m is None else ""))
    for epc in DIAG_EPCS:
        v = el.get(ip, eoj, [epc], timeout=4)
        val = v.get(epc) if v else None
        txt = val.hex().upper() if val else "読めない"
        if epc == 0xB6 and val:
            txt += f"（{TANK_MODE.get(val[0], '?')}）"
        if epc in (0xE1, 0xE2) and val and len(val) >= 2:
            txt += f"（{int.from_bytes(val[:2], 'big')}L）"
        can = "（不明）" if setm is None else "設定できる" if epc in setm else "設定できない"
        lines.append(f"・{DIAG_NAME.get(epc, hex(epc))}：{can}・値 {txt}")
    lines.append("→ 夜の沸き上げを減らす方法：" + (
        "エネルギーシフト" if setm and 0xC7 in setm and not state.get("shift_unsupported")
        else "タンクの運転モード（晴れの夜だけ「節約」）" if setm is None or 0xB6 in setm
        else "外からは変えられません（エコキュートのアプリ・リモコンの設定を使ってください）"))
    return "\n".join(lines)


def tick():
    now = datetime.now()
    in_window = conf["start_hour"] <= now.hour < conf["end_hour"]
    eco = eco_read() if conf["eco_ip"] else None
    surplus, source = read_surplus()
    state["surplus_from"] = source
    if surplus is not None:
        with lock:
            state["history"].append([time.time(), surplus])
            cut = time.time() - 3600
            state["history"] = [p for p in state["history"] if p[0] >= cut]
            state["surplus_w"] = surplus
    hot_water_track(eco)
    if not conf["enabled"]:
        state["status"] = "おひさま沸き上げはオフです"
        return
    if not conf["eco_ip"]:
        state["status"] = "エコキュートの IP アドレスが設定されていません"
        return

    # 夜止め：目標の量まで沸いたら止める
    pct0 = remain_pct(eco)
    if state.get("night_pending") and not in_window and pct0 is not None and pct0 >= int(conf.get("night_heat_pct", 50)):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43"):
            state["night_pending"] = False
            state["night_stop"] = True
            tank = (eco or {}).get("tank")
            log(f"夜の沸き上げを止めました（{pct0}%" + (f"・約{tank * pct0 // 100}L" if tank else "") + " まで沸きました）")

    # 1. 毎日の設定
    plan = state.get("plan") or {}
    target = plan_target().isoformat()
    if (now.hour >= conf["plan_hour"] or now.hour < 5) and (plan.get("date") != target or (not plan.get("ok") and time.time() >= retry_at[0])):
        run_plan()
    # 朝 7 時の確認
    today = date.today().isoformat()
    if now.hour == 7 and morning_checked[0] != today and conf["shift"]:
        morning_checked[0] = today
        p = state.get("plan") or {}
        if p.get("date") == today and p.get("sunny") is not None and eco and eco.get("shift_raw") is not None and not state.get("shift_unsupported") and conf.get("night_method") != "stop":
            if eco["shift_raw"] == (0x41 if p["sunny"] else 0x42):
                log("朝の確認：昨夜の設定どおりです")
            else:
                log("朝の確認：昨夜の設定が変わっています。設定し直します")
                run_plan()

    # タンクの運転モードを「節約」にしていたら、昼の時間帯が終わったところで「標準」に戻す
    if state.get("tank_by_us") and now.hour >= conf["end_hour"] and now.hour < conf["plan_hour"]:
        err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB6, b"\x41")
        if not err:
            state["tank_by_us"] = False
            log("昼の時間帯が終わったので、タンクの運転モードを「標準」に戻しました")

    # 夜止め：夜中にお湯が減りすぎたら、止めるのをやめる（安い夜の電気で沸かす）
    pct = remain_pct(eco)
    if state.get("night_stop") and conf.get("safety_hour", 4) <= now.hour < conf["start_hour"] and pct is not None and pct < conf.get("safety_pct", 30):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41"):
            state["night_stop"] = False
            log(f"残りのお湯が少ない（{pct}%）ため、夜の沸き上げを止めるのをやめました（自動に戻しました）")
    # 夜止め：昼の時間帯が終わったら自動に戻す
    if state.get("night_stop") and not in_window and conf["end_hour"] <= now.hour < conf["plan_hour"]:
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41"):
            state["night_stop"] = False
            state["by_us"] = False
            log("昼の時間帯が終わったので、沸き上げを自動に戻しました")
    # 夜止め：昼の時間帯の終わる2時間前に、まだお湯が少なければ、売電が無くても沸かす（お湯切れを防ぐ保険）
    if state.get("night_stop") and in_window and now.hour >= conf["end_hour"] - 2 and not state["by_us"] \
            and pct is not None and pct < conf.get("ensure_pct", 60) and not (eco or {}).get("heating"):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42"):
            state["by_us"] = True
            state["ensure"] = True
            log(f"残りのお湯が少ない（{pct}%）ため、売電が無くても沸き増しを始めました（お湯切れを防ぐため）")

    # 2. 昼：売電をリアルタイムに見て沸き増し
    if not in_window:
        counters["over"] = counters["buy"] = 0
        state["ensure"] = False
        if state["by_us"]:
            eco_auto("昼の時間帯が終わったため")
        state["status"] = f"待機中（{conf['start_hour']}時〜{conf['end_hour']}時に、余った電気で沸かします）"
        return
    if not conf["meter_ip"] and not conf.get("aiseg_url"):
        state["status"] = "売電を読む機器（AiSEG2 またはスマートメーター）が設定されていません（昼の沸き増しは行いません）"
        return
    if surplus is None:
        state["status"] = state.get("aiseg_error") or f"スマートメーター（{conf['meter_ip']}）から返事がありません"
        return
    full = eco and eco.get("remain") and eco.get("tank") and eco["remain"] * 100 // eco["tank"] >= 95
    th = conf["threshold_w"]
    if not state["by_us"]:
        counters["over"] = counters["over"] + 15 if surplus >= th else 0
        state["status"] = f"昼の時間帯：売電 {max(0, surplus) / 1000:.1f}kW（{th / 1000:.1f}kW 以上が2分続くと沸き増し）"
        if counters["over"] >= 120 and not (eco or {}).get("heating") and not full and (eco or {}).get("can_manual", True):
            if eco_start(surplus):
                counters["buy"] = 0
    else:
        counters["buy"] = counters["buy"] + 15 if surplus < -conf["stop_buy_w"] else 0
        state["status"] = "余った電気で沸き増し中（" + (f"売電 {surplus / 1000:.1f}kW" if surplus >= 0 else f"買電 {-surplus / 1000:.1f}kW") + "）"
        stop = eco_pause if state.get("night_stop") else eco_auto
        if full:
            stop("お湯がいっぱいになったため")
            state["ensure"] = False
        elif counters["buy"] >= 180 and not state.get("ensure"):
            stop("電気を買い始めたため")


wake = threading.Event()


def loop():
    while True:
        try:
            tick()
        except Exception as e:
            state["status"] = f"エラー：{e}"
        h = datetime.now().hour
        fast = conf["enabled"] and conf["start_hour"] <= h < conf["end_hour"]
        wake.wait(15 if fast else 60)
        wake.clear()


# ================= 画面からの操作（/solar-api/） =================

def cost_summary_safe():
    try:
        return cost_summary()
    except Exception as e:
        print("料金の計算でエラー:", e, flush=True)
        return None


def public_state():
    with lock:
        c = {k: v for k, v in conf.items() if k not in SECRET_CONF}
        c["aiseg_password_set"] = bool(conf.get("aiseg_password"))
        c["ha_token_set"] = bool(conf.get("ha_token"))
        hot = [p for p in energy.get("hot", []) if p[0] > time.time() - 86400]
        return {"night_pending": bool(state.get("night_pending")), "daily": dict(sorted(energy.get("days", {}).items())[-10:]),
                "hot_history": hot, "hot_hourly": state.get("hot_hourly"), "cost": cost_summary_safe(),
                "aiseg_daily": {"ok": aiseg_daily["ok"], "pages": aiseg_daily["pages"]},
                "night_stop": bool(state.get("night_stop")), "power": state.get("power"), "surplus_from": state.get("surplus_from"), "aiseg_error": state.get("aiseg_error"), "config": c, "plan": state.get("plan"), "shift_unsupported": bool(state.get("shift_unsupported")), "log": state["log"][:40], "status": state["status"],
                "surplus_w": state.get("surplus_w") if state["history"] and time.time() - state["history"][-1][0] < 120 else None,
                "history": state["history"], "eco": state["eco"], "by_us": state["by_us"], "server_time": time.time()}


class Api(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _ok(self, body, code=200):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def _path(self):
        return urllib.parse.urlparse(self.path).path.replace("/solar-api", "", 1).rstrip("/") or "/"

    def do_GET(self):
        p = self._path()
        if p in ("/", "/status"):
            return self._ok(public_state())
        if p == "/climate":
            return self._ok(climate_public())
        if p == "/timers":
            return self._ok(timers_public())
        if p == "/tariffs":
            return self._ok(tariff_list())
        if p == "/energy5":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            return self._ok(energy5_report((q.get("date") or [date.today().isoformat()])[0]))
        if p == "/energy":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            return self._ok(energy_report((q.get("days") or ["7"])[0]))
        self._ok({"error": "not found"}, 404)

    def do_POST(self):
        p = self._path()
        try:
            b = self._body()
        except Exception:
            return self._ok({"error": "JSON を読めません"}, 400)
        if p == "/config":
            allowed = set(DEFAULT_CONF)
            with lock:
                for k, v in b.items():
                    if k in allowed:
                        conf[k] = v
                save_json(CONF_PATH, conf)
            eco_caps["set"] = None
            log("設定を変えました：" + "、".join(k for k in b if k in allowed))
            wake.set()
            return self._ok(public_state())
        if p == "/timers":
            # 追加：{entity, name, action, data, at（時刻・秒）または after_min}
            if not conf.get("ha_token"):
                return self._ok({"error": "サーバーに Home Assistant の鍵が登録されていません（アプリの「エアコンの自動温度調整」で登録してください）"})
            eid = str(b.get("entity") or "")
            if "." not in eid or b.get("action") not in ("turn_on", "turn_off", "toggle", "set_hvac_mode"):
                return self._ok({"error": "機器か操作が正しくありません"})
            at = float(b["at"]) if b.get("at") else time.time() + float(b.get("after_min") or 0) * 60
            if at < time.time() - 60 or at > time.time() + 8 * 86400:
                return self._ok({"error": "時刻が正しくありません（1週間以内）"})
            t = {"id": uuid.uuid4().hex[:10], "entity": eid, "name": str(b.get("name") or "")[:60], "action": b["action"],
                 "data": b.get("data") or {}, "at": at, "by": str(b.get("by") or "")[:30], "created": time.time()}
            with lock:
                if b.get("replace"):
                    # 同じ機器の前のタイマーは消す
                    device_timers[:] = [x for x in device_timers if x["entity"] != eid]
                device_timers.append(t)
                save_json(TIMERS_PATH, device_timers)
            log(f"タイマーを設定しました：{t['name'] or eid} を {datetime.fromtimestamp(at).strftime('%m/%d %H:%M')} に{timer_label(t)}")
            return self._ok(timers_public())
        if p == "/timers/delete":
            with lock:
                before = len(device_timers)
                device_timers[:] = [x for x in device_timers if x["id"] != b.get("id") and not (b.get("entity") and x["entity"] == b.get("entity"))]
                save_json(TIMERS_PATH, device_timers)
            if len(device_timers) != before:
                log("タイマーを取り消しました")
            return self._ok(timers_public())
        if p == "/retry":
            log("手動で設定をやり直します")
            run_plan(manual=True)
            return self._ok(public_state())
        if p == "/heat":
            a = b.get("action")
            if a == "start":
                s = eco_caps["set"]
                if s is None or 0xC0 in s:
                    el.setc(conf["eco_ip"], conf["eco_eoj"], 0xC0, b"\x41")
                err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
                log("手動で沸き増しを始めました" if not err else f"手動の沸き増しができませんでした（{err}）")
            elif a == "auto":
                eco_auto("手動の操作")
            eco_read()
            return self._ok(public_state())
        if p == "/diagnose":
            return self._ok({"text": diagnose()})
        if p == "/aiseg-test":
            v = aiseg_surplus_w()
            return self._ok({"ok": v is not None, "surplus_w": v, "power": state.get("power"), "error": state.get("aiseg_error")})
        if p == "/aiseg-circuits":
            try:
                pages = aiseg_circuit_pages()
            except Exception as e:
                return self._ok({"ok": False, "error": str(e)})
            eco_props = None
            if conf.get("eco_ip"):
                eco_power["ok"] = None  # もう一度試す
                try:
                    m = el.get(conf["eco_ip"], conf["eco_eoj"], [0x9F, 0x84, 0x85], timeout=5)
                    if m and 0x9F in m:
                        eco_props = ["%02X" % e for e in sorted(prop_map(m[0x9F]))]
                except Exception:
                    pass
            return self._ok({"ok": True, "pages": pages, "eco_direct": eco_power_w() if conf.get("eco_ip") else None,
                             "eco_ip": conf.get("eco_ip"), "eco_props": eco_props})
        if p == "/discover":
            return self._ok({"devices": el.discover()})
        self._ok({"error": "not found"}, 404)


def main():
    global el
    os.makedirs(DIR, exist_ok=True)
    el = Echonet()
    threading.Thread(target=loop, daemon=True).start()
    threading.Thread(target=energy_loop, daemon=True).start()
    threading.Thread(target=weather_loop, daemon=True).start()
    threading.Thread(target=climate_loop, daemon=True).start()
    threading.Thread(target=device_timer_loop, daemon=True).start()
    print(f"おひさま沸き上げのサービスを開始（画面用 127.0.0.1:{API_PORT}）", flush=True)
    ThreadingHTTPServer(("127.0.0.1", API_PORT), Api).serve_forever()


if __name__ == "__main__":
    main()
