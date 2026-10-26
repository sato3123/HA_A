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
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIR = os.environ.get("SOLAR_DIR", "/var/lib/homepanel-solar")
CONF_PATH = os.path.join(DIR, "config.json")
STATE_PATH = os.path.join(DIR, "state.json")
PORT = 3610
MCAST = "224.0.23.0"
API_PORT = int(os.environ.get("SOLAR_API_PORT", "8098"))

DEFAULT_CONF = {
    "enabled": False,
    "eco_ip": "", "eco_eoj": "026B01",      # エコキュート
    "meter_ip": "", "meter_eoj": "028801",  # スマートメーター（低圧スマート電力量メータ）
    "start_hour": 10, "end_hour": 15,       # 昼に沸かす時間帯
    "threshold_w": 1200,                    # 沸き増しを始める売電（W）
    "stop_buy_w": 300,                      # 沸き増し中、この量を超えて買い始めたら戻す（W）
    "plan_hour": 20,                        # 毎日の設定をする時刻
    "shift": True,                          # 晴れの日は夜の沸き上げを昼にずらす
    "lat": None, "lon": None,               # 天気予報の場所（Home Assistant の自宅の位置を画面から送る）
}

EPC_NAME = {0xB0: "沸き上げ自動設定", 0xC0: "昼間沸き増し許可", 0xC7: "エネルギーシフト参加", 0xC9: "エネルギーシフト回数", 0xCA: "昼間沸き上げシフト時刻"}
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
        save_json(STATE_PATH, {k: state[k] for k in ("plan", "log", "by_us")})


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
        """1項目だけ設定する。成功なら None、失敗なら理由"""
        f = self.request(ip, deoj, 0x61, [(epc, edt)])
        if not f:
            return f"返事がありません（{ip}）"
        if f["esv"] == 0x51:
            return f"値（{edt.hex().upper()}）を受け付けませんでした"
        return None

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
    v = el.get(ip, eoj, [0x80, 0xB0, 0xB2, 0xE1, 0xE2, 0xC0, 0xC7, 0xCA])
    if v is None:
        v = el.get(ip, eoj, [0xB0, 0xB2, 0xE1, 0xC7])
    eco = {"online": v is not None, "at": time.time()}
    if v:
        u8 = lambda e: v[e][0] if e in v and len(v[e]) >= 1 else None
        u16 = lambda e: int.from_bytes(v[e][:2], "big") if e in v and len(v[e]) >= 2 else None
        eco.update(heating=u8(0xB2) == 0x41 if u8(0xB2) is not None else None, manual=u8(0xB0) == 0x42 if u8(0xB0) is not None else None,
                   remain=u16(0xE1), tank=u16(0xE2), shift=u8(0xC7) == 0x41 if u8(0xC7) is not None else None,
                   shift_raw=u8(0xC7), shift_hour=u8(0xCA))
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
    v = el.get(ip, conf["meter_eoj"], [0xE7], timeout=4)
    if not v or 0xE7 not in v or len(v[0xE7]) < 4:
        return None
    w = struct.unpack(">i", v[0xE7][:4])[0]
    if w in (0x7FFFFFFE, -0x7FFFFFFF):
        return None
    return -float(w)  # 買電がプラス・売電がマイナス → 余剰（売電）をプラスに


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
        log(("✓ " if ok else "✕ ") + msg)
        return ok

    if not conf["shift"]:
        return result(True, "夜の沸き上げを昼にずらす設定はオフです（いつもどおり夜に沸かします）")
    if not conf["eco_ip"]:
        return result(False, "エコキュートの IP アドレスが設定されていません")
    eco = eco_read()
    if not eco or not eco.get("online"):
        return result(False, f"エコキュート（{conf['eco_ip']}）から返事がありません" + ("" if manual else "（10分後にもう一度試します）"))
    if eco_caps["set"] is not None and 0xC7 not in eco_caps["set"]:
        return result(False, "このエコキュートは昼間沸き上げ（エネルギーシフト）の設定に対応していません")
    name = "今日" if target == date.today() else "明日"
    try:
        sunny, code = forecast_sunny(target)
    except Exception as e:
        return result(False, f"{name}の天気予報を読めませんでした：{e}")
    if sunny:
        props = [(0xC7, b"\x41"), (0xC9, b"\x01"), (0xCA, bytes([max(9, min(17, int(conf["start_hour"])))]))]
    else:
        props = [(0xC7, b"\x42"), (0xCA, b"\x00")]
    res = set_each(props)
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


def tick():
    now = datetime.now()
    in_window = conf["start_hour"] <= now.hour < conf["end_hour"]
    eco = eco_read() if conf["eco_ip"] else None
    surplus = meter_surplus_w() if conf["meter_ip"] else None
    if surplus is not None:
        with lock:
            state["history"].append([time.time(), surplus])
            cut = time.time() - 3600
            state["history"] = [p for p in state["history"] if p[0] >= cut]
            state["surplus_w"] = surplus
    if not conf["enabled"]:
        state["status"] = "おひさま沸き上げはオフです"
        return
    if not conf["eco_ip"]:
        state["status"] = "エコキュートの IP アドレスが設定されていません"
        return

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
        if p.get("date") == today and p.get("sunny") is not None and eco and eco.get("shift_raw") is not None:
            if eco["shift_raw"] == (0x41 if p["sunny"] else 0x42):
                log("朝の確認：昨夜の設定どおりです")
            else:
                log("朝の確認：昨夜の設定が変わっています。設定し直します")
                run_plan()

    # 2. 昼：売電をリアルタイムに見て沸き増し
    if not in_window:
        counters["over"] = counters["buy"] = 0
        if state["by_us"]:
            eco_auto("昼の時間帯が終わったため")
        state["status"] = f"待機中（{conf['start_hour']}時〜{conf['end_hour']}時に、余った電気で沸かします）"
        return
    if not conf["meter_ip"]:
        state["status"] = "スマートメーターの IP アドレスが設定されていません（昼の沸き増しは行いません）"
        return
    if surplus is None:
        state["status"] = f"スマートメーター（{conf['meter_ip']}）から返事がありません"
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
        if full:
            eco_auto("お湯がいっぱいになったため")
        elif counters["buy"] >= 180:
            eco_auto("電気を買い始めたため")


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

def public_state():
    with lock:
        c = dict(conf)
        return {"config": c, "plan": state.get("plan"), "log": state["log"][:40], "status": state["status"],
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
        if p == "/discover":
            return self._ok({"devices": el.discover()})
        self._ok({"error": "not found"}, 404)


def main():
    global el
    os.makedirs(DIR, exist_ok=True)
    el = Echonet()
    threading.Thread(target=loop, daemon=True).start()
    print(f"おひさま沸き上げのサービスを開始（画面用 127.0.0.1:{API_PORT}）", flush=True)
    ThreadingHTTPServer(("127.0.0.1", API_PORT), Api).serve_forever()


if __name__ == "__main__":
    main()
