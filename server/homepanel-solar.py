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
import re
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
# 作るファイルは、このサービスだけが読めるようにする（設定に AiSEG2 のパスワードや鍵が入るため）
os.umask(0o077)

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
    "night_heat_pct": 50,                   # 「夜は止める」：夜はこの残量（%）まで沸かしたら止める（hot_auto のときは自動で決める）
    # お湯の量を過去の使用量から自動で決める：夕方 prep_hour 時までに「夜（prep_hour〜night_start_hour 時）に使う予想＋余裕」を用意し、
    # 「夜は止める」の夜に沸かす量も「翌朝（0時〜昼の沸き上げの始まり）に使う予想＋余裕」にする
    # 昼に電気が余っていれば、今夜の分だけでなく翌日1日分まで先に沸かしておく（その分、夜の沸き上げが減る）
    "hot_store_tomorrow": True,
    "hot_auto": False, "hot_margin_l": 50, "prep_hour": 17, "night_start_hour": 23,
    "heat_rate_lph": 0,                     # 沸き上げの速さ（L/時）。0 なら記録から求める
    # 多めにお湯が必要な日：[{date: "YYYY-MM-DD", liters, part: evening（夕方〜夜）/ morning（朝）, note}]
    "hot_extra": [],
    # 買電が多いときはお湯を沸かさない：買電が guard_kw 以上（0 なら「30分の電力」の注意の値、それも無ければ 3kW）か、
    # 30分の電力が注意の値を超えそうなときは、沸き上げを一時停止し、下がったら元に戻す
    "heat_guard": True, "heat_guard_kw": 0,
    # 夜の沸き上げを、できるだけ遅く（朝に近く）する：沸かしたお湯は外気との温度差に比例して冷めるので、
    # 冷める分と、その時刻の電気料金・外気温での効率（COP）から、いちばん安く済む開始時刻を選ぶ（「夜は止める」の方式のとき）
    "night_late": True, "night_end_hour": 7,
    "tank_temp": 65,                        # 沸き上げの温度（℃）
    "tank_loss_w_per_k": 1.0,               # タンクの冷めやすさ（外気との温度差1℃あたりの熱の逃げ方・W）
    "night_late_tol_yen": 1.0,
    # 気温による、お湯の使い方・冷めて減る量を学び、翌日の気温の予報で沸かす量の予想を直す
    "hot_temp_learn": True,              # いちばん安い時刻との差がこの円以内なら、できるだけ遅い時刻を選ぶ
    # ECHONET Lite の機器（Android 版が見つけたもの）：[{id: "climate.el_…", name, room}]。iPad 版の自動化の画面で選べるようにする
    "el_devices": [],
    # ECHONET Lite のエアコンに数分ごとに「名乗って」と頼む（読み取りだけ。運転は変えない）。
    # 全員あての呼びかけを聞き逃すエアコンが、Home Assistant（HEMS Echonet Lite）の一覧から消えたままになるのを防ぐ
    "el_rescue": True, "el_rescue_min": 3, "el_rescue_ips": [],
    # Android 版が知っている ECHONET Lite のエアコンの IP（アプリが自動で送る）
    "el_known_ips": [],
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
    # 日射量の予報から発電量を予想するための、太陽光パネルの情報
    "pv_kw": 0,                             # パネルの容量（kW）。0 なら記録から推定
    "pv_tilt": 30, "pv_azimuth": 0,         # 傾き（度）・向き（0＝南、-90＝東、90＝西）
    "solar_need_kwh": 0,                    # 昼に沸かすのに必要な余剰（kWh）。0 なら記録（エコキュートの1日の使用量）から決める
    # 留守（外出・旅行）：この時刻まで沸き上げを止める。reheat_hours 時間前から沸かし始める
    "away_until": 0, "away_reheat_hours": 6,
    # まとめて操作のボタン：[{id, name, icon, action: on/off, entities: [...]}]
    "groups": [],
    # 30分ごとの電力（デマンド）の見張り：対象（use＝使った電気 / buy＝買った電気）、上限（0 なら直近12か月の最大）、余裕（kW）、通知先（Home Assistant の notify）
    "demand_target": "buy", "demand_limit_kw": 0, "demand_margin_kw": 0.5, "demand_notify": [], "demand_enabled": False,
    # ---- 自給率を上げる（どれもオプション。初めはオフ） ----
    # ホーム画面に今日の自給率と目標を出す
    "ss_show": False, "ss_target_pct": 50,
    # 昼に売電が続いたら「いま使うとお得」と知らせる（kW 以上が min 分続いたら。from〜to 時のあいだ・2時間に1回まで）。知らせる先は電力の見張りと同じ
    "ss_notify": False, "ss_notify_kw": 1.5, "ss_notify_min": 10, "ss_notify_from": 9, "ss_notify_to": 15,
    # 昼の余剰でエアコンを先に動かす：動いているエアコンだけ、冷房は offset℃下げ・暖房は上げる。買電になったら元に戻す
    "ss_aircon": False, "ss_aircon_entities": [], "ss_aircon_kw": 1.0, "ss_aircon_offset": 1.0, "ss_aircon_from": 10, "ss_aircon_to": 16,
    # 余剰で動かす機器（電気自動車の充電・蓄電池の充電など）：[{entity, name, on_kw, off_kw}]。売電が on_kw 以上で入れ、買電が off_kw を超えたら切る
    "ss_loads": False, "ss_loads_items": [], "ss_loads_min": 10, "ss_loads_from": 9, "ss_loads_to": 16,
    # くもりの日も、昼に沸かせる見込みの分だけ夜の沸き上げを減らす（「夜は止める」方式のとき）
    "ss_partial": False,
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
    """書きかけで電源が切れても壊れないよう、一時ファイルに書いて fsync してから置き換える。root とこのサービスだけが読める（0600）"""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    try:
        d = os.open(os.path.dirname(path) or ".", os.O_RDONLY)
        os.fsync(d)
        os.close(d)
    except Exception:
        pass


conf = dict(DEFAULT_CONF, **load_json(CONF_PATH, {}))
# 30分ごとの電力の見張りは「買った電気」が基本（前の版で「使った電気」のまま保存された設定を、1回だけ直す）
if not conf.get("demand_target_v2"):
    conf["demand_target"] = "buy"
    conf["demand_target_v2"] = True
    save_json(CONF_PATH, conf)
state = dict({"plan": None, "log": [], "status": "準備中", "history": [], "eco": {}, "by_us": False}, **load_json(STATE_PATH, {}))
state["history"] = []  # グラフは起動しなおすと空から


def log(msg):
    line = datetime.now().strftime("%m/%d %H:%M") + "　" + msg
    print(line, flush=True)
    with lock:
        state["log"] = ([line] + state["log"])[:80]
        save_json(STATE_PATH, {k: state.get(k) for k in ("plan", "log", "by_us", "shift_unsupported", "tank_by_us", "night_stop", "night_pending", "night_resume", "night_heat")})


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

# ---- 日射量の予報から、昼の時間帯の発電量と余る電気を予想する ----
# 気象庁のモデル（JMA）・ECMWF・GFS の3つの予報を重み付きで平均し、
# 実際の発電量（AiSEG2 の記録）と予報の日射量の比から、この家のパネルの「日射量→発電量」の係数を学習する。
FC_MODELS = [("jma_seamless", 0.5), ("ecmwf_ifs025", 0.3), ("gfs_seamless", 0.2)]


def _om_hourly(lat, lon, past_days, forecast_days):
    q = {"latitude": lat, "longitude": lon, "timezone": "Asia/Tokyo", "past_days": past_days, "forecast_days": forecast_days,
         "hourly": "global_tilted_irradiance,shortwave_radiation,cloud_cover",
         "tilt": conf.get("pv_tilt", 30), "azimuth": conf.get("pv_azimuth", 0),
         "models": ",".join(m for m, _ in FC_MODELS)}
    with urllib.request.urlopen("https://api.open-meteo.com/v1/forecast?" + urllib.parse.urlencode(q), timeout=25) as r:
        return json.load(r).get("hourly") or {}


def _irr_by_day(h, hours_range):
    """日付 → {model: 窓の時間の日射量（kWh/m²）}"""
    times = h.get("time") or []
    out = {}
    for m, _ in FC_MODELS:
        vals = h.get(f"global_tilted_irradiance_{m}") or h.get(f"shortwave_radiation_{m}")
        if vals is None and len(FC_MODELS) and m == FC_MODELS[0][0]:
            vals = h.get("global_tilted_irradiance") or h.get("shortwave_radiation")
        if not vals:
            continue
        for t, v in zip(times, vals):
            if v is None:
                continue
            d, hh = t[:10], int(t[11:13])
            if hours_range is None or hours_range[0] <= hh < hours_range[1]:
                out.setdefault(d, {}).setdefault(m, 0.0)
                out[d][m] += float(v) / 1000.0
    return out


def _blend(per_model):
    """重み付きの平均と、いちばん少ない予報（控えめに見るため）"""
    w = {m: wt for m, wt in FC_MODELS}
    tot = sum(w[m] for m in per_model)
    if not tot:
        return None, None
    mean = sum(per_model[m] * w[m] for m in per_model) / tot
    return mean, min(per_model.values())


def pv_factor():
    """日射量（kWh/m²）1 あたりの発電量（kWh）。過去の実績と、その日の日射量（予報モデルの値）から学習する"""
    with lock:
        days = dict(energy.get("days", {}))
    ratios = []
    for d, v in sorted(days.items())[-30:]:
        irr, gen = v.get("irr_day"), v.get("gen_kwh")
        if irr and gen and irr > 1.0:
            ratios.append(gen / irr)
    if len(ratios) >= 3:
        ratios.sort()
        return ratios[len(ratios) // 2], f"過去{len(ratios)}日の実績から学習"
    kw = float(conf.get("pv_kw") or 0)
    if kw <= 0:
        # 記録の中でいちばん大きい1時間の発電量から、パネルの容量を推定（晴れた昼の1時間でおよそ容量の75%）
        with lock:
            mx = max((float(v.get("gen") or 0) for v in energy.get("hours", {}).values()), default=0.0) / 1000
        kw = mx / 0.75 if mx > 0.5 else 5.0
    return kw * 0.8, f"パネル{kw:.1f}kW から計算"


def update_irr_history():
    """過去の日の日射量（予報モデルの値）と実際の発電量を日ごとに記録する（学習と、予報の当たり具合の確認用）"""
    lat, lon = conf.get("lat"), conf.get("lon")
    if lat is None or lon is None:
        return
    h = _om_hourly(lat, lon, 31, 1)
    per = _irr_by_day(h, None)
    with lock:
        days = energy.setdefault("days", {})
        for d, pm in per.items():
            if d >= date.today().isoformat():
                continue
            mean, _ = _blend(pm)
            gen = sum(float(v.get("gen") or 0) for k, v in energy["hours"].items() if k.startswith(d)) / 1000
            day = days.setdefault(d, {})
            if mean is not None:
                day["irr_day"] = round(mean, 3)
            if gen > 0 and not day.get("gen_from_aiseg"):
                day["gen_kwh"] = round(gen, 2)


def need_kwh():
    """昼に沸かすのに必要な余剰（kWh）。設定が無ければ、AiSEG2 の記録のエコキュートの1日の使用量の平均"""
    v = float(conf.get("solar_need_kwh") or 0)
    if v > 0:
        return v, "設定した値"
    with lock:
        vals = [float((d.get("circuits_kwh") or {}).get("エコキュート") or 0) for _, d in sorted(energy.get("days", {}).items())[-8:-1]]
    vals = [x for x in vals if x > 0.3]
    if vals:
        return round(sum(vals) / len(vals), 2), f"エコキュートの直近{len(vals)}日の平均"
    return 3.0, "目安"


def forecast_solar(day):
    """その日の、昼の時間帯（沸かす時間帯）の発電と余る電気の予想。晴れ扱いにするかと、説明の文を返す"""
    lat, lon = conf.get("lat"), conf.get("lon")
    if lat is None or lon is None:
        raise RuntimeError("天気予報の場所が設定されていません（アプリの「おひさま沸き上げ」から、サーバーに自宅の位置を送ってください）")
    try:
        update_irr_history()
    except Exception as e:
        print("日射量の記録でエラー:", e, flush=True)
    sh, eh = int(conf["start_hour"]), int(conf["end_hour"])
    h = _om_hourly(lat, lon, 0, 3)
    win = _irr_by_day(h, (sh, eh)).get(day.isoformat()) or {}
    allday = _irr_by_day(h, None).get(day.isoformat()) or {}
    mean, low = _blend(win)
    if mean is None:
        raise RuntimeError("日射量の予報がありません")
    k, how = pv_factor()
    # 控えめな見込み：平均とモデルの中でいちばん少ない予報の間
    irr = 0.7 * mean + 0.3 * low
    gen = k * irr
    # 昼の時間帯にふだん使う電気（直近7日の平均）
    with lock:
        uses = {}
        for kk, v in energy["hours"].items():
            hh = int(kk[11:13])
            if sh <= hh < eh:
                uses[kk[:10]] = uses.get(kk[:10], 0.0) + float(v.get("use") or 0) / 1000
    recent = [v for d, v in sorted(uses.items())[-8:] if d != date.today().isoformat()][-7:]
    use = sum(recent) / len(recent) if recent else 1.5
    surplus = max(0.0, gen - use)
    need, need_how = need_kwh()
    sunny = surplus >= need
    spread = (max(win.values()) - min(win.values())) / mean if mean > 0 and len(win) > 1 else 0
    all_mean, _ = _blend(allday)
    with lock:
        energy.setdefault("days", {}).setdefault(day.isoformat(), {}).update(
            pv_fc_kwh=round(k * (all_mean or 0), 2), pv_fc_window_kwh=round(gen, 2), pv_fc_surplus_kwh=round(surplus, 2))
    note = (f"（{sh}〜{eh}時の予想：発電 {gen:.1f}kWh・ふだんの使用 {use:.1f}kWh・余り {surplus:.1f}kWh／沸かすのに必要 {need:.1f}kWh"
            f"{'・予報のばらつき大' if spread > 0.4 else ''}）")
    return sunny, {"note": note, "gen": gen, "surplus": surplus, "need": need, "factor": k, "factor_how": how, "need_how": need_how,
                   "models": {m: round(v, 2) for m, v in win.items()}, "spread": round(spread, 2)}


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
    state["power_at"] = time.time()
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
            state["circuits_inst"] = dict(circuits)
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
        demand_args = ((use if src == "AiSEG2" else 0.0), max(0.0, -net), dt)
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
    try:
        demand_tick(*demand_args)
    except Exception as e:
        print("電力の見張りでエラー:", e, flush=True)


# ---- AiSEG2 の「回路ごとの使用電力量（日）」と「湯」の画面（200V の回路も入っている） ----
aiseg_daily = {"date": None, "last": {}, "ok": False, "at": 0, "pages": None, "hist": [], "patterns": []}


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
    full = {}
    used = []
    pats = aiseg_page_patterns(d)
    # 前に回路が見つかった読み方だけを使う（AiSEG2 に負担をかけないため）。1時間ごとに全部の読み方を試し直す
    known = aiseg_daily.get("patterns") or []
    if known and time.time() - aiseg_daily.get("probed", 0) < 3600:
        pats = [x for x in pats if x[0] in known] or pats
    else:
        aiseg_daily["probed"] = time.time()
    for name, make in pats:
        got, prev_sig = 0, None
        for i in range(1, 13):
            path = make(i)
            if path is None:
                continue
            try:
                r = rows(aiseg_open(path))
            except Exception:
                break
            sig = ",".join(r)
            if not r or sig == prev_sig:
                break
            prev_sig = sig
            new = {k: v for k, v in r.items() if k not in full}
            full.update(r)
            got += len(new)
        if got:
            used.append(name)
    if full:
        aiseg_daily["patterns"] = used
        return full, "all"
    r = rows(aiseg_open(f"/page/graph/581?{_graph_params(d)}"))
    r.pop("その他", None)
    return r, "top5"


def aiseg_page_patterns(d):
    """「回路ごとの使用電力量（日）」の、ページの読み方の候補（機種・版によって違うため、全部試して回路を集める）"""
    import base64
    q = _graph_params(d)

    def b64(i):
        j = {"day": [d.year, d.month, d.day], "term": f"{d.year}/{d.month}/{d.day}", "termStr": "day", "id": str(i)}
        return urllib.parse.quote(base64.b64encode(json.dumps(j, separators=(",", ":")).encode()).decode())
    return [
        ("582+583?id", lambda i: f"/page/graph/582?{q}" if i == 1 else f"/page/graph/583?id={i}&{q}"),
        ("582?id", lambda i: f"/page/graph/582?id={i}&{q}"),
        ("582&page", lambda i: f"/page/graph/582?{q}&page={i}"),
        ("582?data", lambda i: f"/page/graph/582?data={b64(i)}"),
        ("583?data", lambda i: None if i == 1 else f"/page/graph/583?data={b64(i)}"),
    ]


def aiseg_hot_hourly():
    """AiSEG2 の「湯」の画面：1時間ごとのお湯の使用量（L）。今日と昨日"""
    import re
    h = aiseg_open("/page/graph/55111")
    m = re.search(r"var graph_data\s*=\s*(\{.*?\});", h, re.S)
    if not m:
        return None
    j = json.loads(m.group(1))
    return {"today": j.get("total1") or [], "yesterday": j.get("total2") or []}


def circuits_now():
    """回路ごとのいまの電力（W）：[{name, w, est}]。
    100V の回路は AiSEG2 の「いまの電力」の値。200V の回路（いまの値が出ない）は、1日の使用量が直近5〜20分で増えた分から平均の電力を出す（est=True）"""
    inst = state.get("circuits_inst") or {}
    inst_names = set(energy.get("inst_names") or [])
    out = [{"name": n, "w": round(w), "est": False} for n, w in inst.items() if n in inst_names or w > 0]
    hist = aiseg_daily.get("hist") or []
    if len(hist) >= 2:
        t1, c1, _ = hist[-1]
        # 5分以上前の記録のうち、いちばん新しいもの（使用量の値は細かく変わらないので、少し長めの間隔で割る）
        old = [h for h in hist if t1 - h[0] >= 290]
        if old:
            t0, c0, _ = old[-1]
            hours = (t1 - t0) / 3600
            for n, kwh in c1.items():
                if n in inst_names or n in inst:
                    continue
                if n in c0:
                    w = max(0.0, (kwh - c0[n]) * 1000 / hours)
                    out.append({"name": n, "w": round(w), "est": True, "minutes": round((t1 - t0) / 60)})
    out.sort(key=lambda x: -x["w"])
    return out


def hourly_fill_tick(today, cur, now=None):
    """回路ごとの時間別の記録を、AiSEG2 の「1日の使用量」に合わせて足りない分を足す。
    いまの電力（W）の積み上げで記録できている回路（100V など）は足さない。200V の機器のように、いまの電力が
    出ない・ときどきしか出ない回路は、その日の記録の合計が1日の使用量より少ない分を、いまの時間（5分）に入れる。
    起動したときに足りない分（止まっていた間の分）は、1つの時間にまとめて入れないように起点にする。lock の中で呼ぶ"""
    now = now or datetime.now()
    dk = today.isoformat()
    rec = {}
    for k, h in energy["hours"].items():
        if k.startswith(dk):
            for n, v in (h.get("circuits") or {}).items():
                rec[n] = rec.get(n, 0.0) + v
    first = aiseg_daily["date"] != dk
    if first:
        # 起動したとき：いまの不足分は数えない。日が変わったとき：0 から
        off = {} if aiseg_daily["date"] else {n: max(0.0, kwh * 1000 - rec.get(n, 0.0)) for n, kwh in cur.items()}
        aiseg_daily.update(date=dk, offset=off)
    off = aiseg_daily.get("offset") or {}
    hk = now.strftime("%Y-%m-%dT%H")
    k5 = now.strftime("%Y-%m-%dT%H:") + f"{now.minute // 5 * 5:02d}"
    added = {}
    for n, kwh in cur.items():
        extra = kwh * 1000 - off.get(n, 0.0) - rec.get(n, 0.0)
        if extra > 0.5:
            added[n] = extra
    if added:
        h = energy["hours"].setdefault(hk, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
        f = energy5.setdefault(k5, {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
        for n, wh in added.items():
            h["circuits"][n] = h["circuits"].get(n, 0.0) + wh
            f["circuits"][n] = f["circuits"].get(n, 0.0) + wh
    aiseg_daily["last"] = dict(cur)
    return added


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
    # 直近20分の「1日の使用量」の記録（200V の回路のいまの電力を、増え方から出す）
    hist = aiseg_daily.setdefault("hist", [])
    if hist and hist[-1][2] != today.isoformat():
        hist.clear()
    hist.append((time.time(), dict(cur), today.isoformat()))
    while hist and time.time() - hist[0][0] > 1200:
        hist.pop(0)
    with lock:
        day = energy.setdefault("days", {}).setdefault(today.isoformat(), {})
        day["circuits_kwh"] = {k: round(v, 3) for k, v in cur.items()}
        hourly_fill_tick(today, cur)
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
    # 今月の日ごとの発電量（AiSEG2 の「発電量（月）」の画面）。日射量→発電量の学習に使う（1日1回）
    if aiseg_daily.get("gen_month_day") != today.isoformat():
        try:
            import re
            htm = aiseg_open("/page/graph/51311")
            m = re.search(r"var graph_data\s*=\s*(\{.*?\});", htm, re.S)
            if m:
                vals = json.loads(m.group(1)).get("total1") or []
                with lock:
                    for i, v in enumerate(vals):
                        dd = today.replace(day=1) + timedelta(days=i)
                        if dd.month != today.month or dd >= today or not v:
                            continue
                        rec = energy.setdefault("days", {}).setdefault(dd.isoformat(), {})
                        rec["gen_kwh"] = round(float(v), 2)
                        rec["gen_from_aiseg"] = True
            aiseg_daily["gen_month_day"] = today.isoformat()
        except Exception as e:
            print("AiSEG2 の月の発電量を読めませんでした:", e, flush=True)
    try:
        hot = aiseg_hot_hourly()
        if hot:
            with lock:
                state["hot_hourly"] = hot
                day = energy["days"].setdefault(today.isoformat(), {})
                day["hot_use_l"] = sum(x or 0 for x in hot["today"])
                day["hot_h"] = [round(float(x or 0), 1) for x in hot["today"]][:24]
                y = (today - timedelta(days=1)).isoformat()
                yd = energy["days"].setdefault(y, {})
                yd["hot_use_l"] = sum(x or 0 for x in hot["yesterday"])
                if len(hot["yesterday"] or []) >= 24:
                    yd["hot_h"] = [round(float(x or 0), 1) for x in hot["yesterday"]][:24]
                state["hot_from_aiseg"] = True
    except Exception as e:
        print("AiSEG2 の湯の量を読めませんでした:", e, flush=True)


# ---- 30分ごとの電力（デマンド）。30分の使用量 ÷ 0.5時間 ＝ 平均の電力（kW） ----
ENERGY30_PATH = os.path.join(DIR, "energy30.json")
energy30 = load_json(ENERGY30_PATH, {})  # "YYYY-MM-DDTHH:MM"（:00 か :30）→ {"use": Wh, "buy": Wh}
demand_state = {"notified": None, "last": None}


def slot30(dt):
    return dt.strftime("%Y-%m-%dT%H:") + ("00" if dt.minute < 30 else "30")


def demand_limit():
    """見張る上限（kW）と、その理由。設定が無ければ、直近12か月の30分ごとの最大"""
    v = float(conf.get("demand_limit_kw") or 0)
    if v > 0:
        return v, "設定した値"
    key = "buy" if conf.get("demand_target") == "buy" else "use"
    cut = (datetime.now() - timedelta(days=365)).strftime("%Y-%m-%dT%H:%M")
    cur = slot30(datetime.now())
    with lock:
        items = [(k, float(x.get(key) or 0) * 2 / 1000) for k, x in energy30.items() if cut <= k < cur]
    if not items:
        return None, "記録がまだありません"
    k, v = max(items, key=lambda t: t[1])
    days = len({kk[:10] for kk, _ in items})
    return v, f"直近12か月の最大・{int(k[5:7])}/{int(k[8:10])} {k[11:]}（記録 {days} 日分から）"


def demand_tick(use_w, buy_w, dt_h):
    """1分ごと：いまの30分の使用量を積み上げ、このまま使い続けたときの30分の電力を予想して、上限に近づいたら知らせる"""
    now = datetime.now()
    k = slot30(now)
    with lock:
        x = energy30.setdefault(k, {"use": 0.0, "buy": 0.0})
        x["use"] += (use_w or 0) * dt_h
        x["buy"] += (buy_w or 0) * dt_h
        cut = (now - timedelta(days=400)).strftime("%Y-%m-%dT%H:%M")
        if len(energy30) > 400 * 48 + 100:
            for kk in [kk for kk in energy30 if kk < cut]:
                del energy30[kk]
    key = "buy" if conf.get("demand_target") == "buy" else "use"
    w_now = (buy_w if key == "buy" else use_w) or 0
    start = now.replace(minute=0 if now.minute < 30 else 30, second=0, microsecond=0)
    left_h = max(0.0, (start + timedelta(minutes=30) - now).total_seconds() / 3600)
    projected = (x[key] + w_now * left_h) * 2 / 1000  # kW
    limit, how = demand_limit()
    margin = float(conf.get("demand_margin_kw") or 0.5)
    demand_state["last"] = {"slot": k, "so_far_kw": round(x[key] * 2 / 1000, 2), "projected_kw": round(projected, 2), "now_kw": round(w_now / 1000, 2),
                            "limit_kw": round(limit, 2) if limit else None, "limit_how": how, "warn_kw": round(limit - margin, 2) if limit else None, "target": key}
    if not conf.get("demand_enabled") or not limit:
        return
    # 30分の初めのうちは予想がぶれやすいので、5分たってから判断する
    if (now - start).total_seconds() < 300 or demand_state["notified"] == k:
        return
    if projected > limit - margin:
        demand_state["notified"] = k
        what = "買っている電気" if key == "buy" else "使っている電気"
        rest = (f"上限 {limit:.1f}kW を超えそうです" if projected >= limit else f"上限 {limit:.1f}kW まであと {limit - projected:.1f}kW です")
        msg = (f"この30分（{start.strftime('%H:%M')}〜）の{what}が {projected:.1f}kW になりそうです。{rest}（{how}）。"
               f"いま {w_now / 1000:.1f}kW 使っています。")
        log("電力の見張り：" + msg)
        state["demand_alert"] = {"at": time.time(), "message": msg, "slot": k}
        for target in conf.get("demand_notify") or []:
            try:
                dom, svc = str(target).split(".", 1)
                if dom != "notify":
                    continue
                ha_call("notify", svc, {"title": "⚡ 電気の使いすぎに注意", "message": msg})
            except Exception as e:
                log(f"電力の見張り：{target} に知らせられませんでした（{e}）")


# ================= 自給率を上げる（オプション） =================
ss = {"sell_since": None, "buy_since": None, "notified_at": 0, "aircon": {}, "loads": {}, "last": None}


def _in_hours(now, a, b):
    a, b = int(a), int(b)
    return a <= now.hour < b if a < b else (now.hour >= a or now.hour < b)


def self_rate_today():
    """今日の自給率（%）：1 − 買った電気 ÷ 使った電気"""
    pre = date.today().isoformat()
    use = buy = 0.0
    with lock:
        for k, v in energy["hours"].items():
            if k.startswith(pre):
                use += float(v.get("use") or 0)
                buy += float(v.get("buy") or 0)
    if use <= 0:
        return None
    return round(max(0.0, min(100.0, (1 - buy / use) * 100)), 1)


def ss_notify_send(title, msg):
    for target in conf.get("demand_notify") or []:
        try:
            dom, svc = str(target).split(".", 1)
            if dom == "notify":
                ha_call("notify", svc, {"title": title, "message": msg})
        except Exception as e:
            log(f"{title}：{target} に知らせられませんでした（{e}）")


def selfsuff_tick(surplus, now=None):
    """売電（surplus＞0）・買電（surplus＜0）が続いた時間を見て、通知・エアコン・余剰で動かす機器を動かす"""
    now = now or datetime.now()
    t = now.timestamp()
    if surplus is None:
        return
    ss["sell_since"] = (ss["sell_since"] or t) if surplus > 0 else None
    ss["buy_since"] = (ss["buy_since"] or t) if surplus < 0 else None
    sell_kw, buy_kw = max(0.0, surplus) / 1000, max(0.0, -surplus) / 1000
    ss["last"] = {"at": t, "sell_kw": round(sell_kw, 2), "buy_kw": round(buy_kw, 2)}

    def held(key, minutes):
        return ss[key] is not None and t - ss[key] >= minutes * 60

    # 1. いま使うとお得の知らせ
    if conf.get("ss_notify") and _in_hours(now, conf.get("ss_notify_from", 9), conf.get("ss_notify_to", 15)):
        kw = float(conf.get("ss_notify_kw") or 1.5)
        mn = float(conf.get("ss_notify_min") or 10)
        if sell_kw >= kw and held("sell_since", mn) and t - ss["notified_at"] > 7200:
            ss["notified_at"] = t
            msg = (f"太陽光の電気が {sell_kw:.1f}kW 余って売電しています（{int(mn)}分以上）。"
                   "いま洗濯機・食器洗い機・乾燥機などを使うと、買う電気を増やさずに済みます。")
            log("自給率：" + msg)
            state["ss_alert"] = {"at": t, "message": msg}
            ss_notify_send("☀ いま使うとお得です", msg)

    # 2. 余剰でエアコンを先に動かす（動いているエアコンだけ）
    ac_on = conf.get("ss_aircon") and conf.get("ha_token") and _in_hours(now, conf.get("ss_aircon_from", 10), conf.get("ss_aircon_to", 16))
    for eid in list(set(conf.get("ss_aircon_entities") or []) | set(ss["aircon"])):
        st = ss["aircon"].get(eid)
        want = ac_on and eid in (conf.get("ss_aircon_entities") or [])
        try:
            if st is None and want and sell_kw >= float(conf.get("ss_aircon_kw") or 1.0) and held("sell_since", 10):
                a = ha_state(eid) or {}
                mode = a.get("state")
                cur = (a.get("attributes") or {}).get("temperature")
                if mode in ("cool", "heat") and cur is not None:
                    off = max(0.5, min(3.0, float(conf.get("ss_aircon_offset") or 1.0)))
                    new = round((float(cur) - off if mode == "cool" else float(cur) + off) * 2) / 2
                    ha_call("climate", "set_temperature", {"entity_id": eid, "temperature": new})
                    ss["aircon"][eid] = {"base": float(cur), "set": new, "mode": mode, "at": t}
                    log(f"自給率：電気が余っているので、{eid} を {cur}℃ → {new}℃ にしました（{'冷房' if mode == 'cool' else '暖房'}を先に）")
            elif st is not None and (not want or held("buy_since", 10)):
                a = ha_state(eid) or {}
                cur = (a.get("attributes") or {}).get("temperature")
                # 人が温度を変えていたら、そのままにする
                if a.get("state") == st["mode"] and cur is not None and abs(float(cur) - st["set"]) < 0.01:
                    ha_call("climate", "set_temperature", {"entity_id": eid, "temperature": st["base"]})
                    log(f"自給率：{eid} を元の {st['base']}℃ に戻しました")
                ss["aircon"].pop(eid, None)
        except Exception as e:
            print("自給率（エアコン）でエラー:", e, flush=True)

    # 3. 余剰で動かす機器（電気自動車・蓄電池の充電など）
    ld_on = conf.get("ss_loads") and conf.get("ha_token") and _in_hours(now, conf.get("ss_loads_from", 9), conf.get("ss_loads_to", 16))
    mn = float(conf.get("ss_loads_min") or 10)
    items = {x["entity"]: x for x in conf.get("ss_loads_items") or [] if isinstance(x, dict) and x.get("entity")}
    for eid in list(set(items) | set(ss["loads"])):
        it = items.get(eid) or {}
        dom = eid.split(".")[0]
        try:
            if eid not in ss["loads"] and ld_on and it and sell_kw >= float(it.get("on_kw") or 1.5) and held("sell_since", mn):
                a = ha_state(eid) or {}
                if a.get("state") == "off":
                    ha_call(dom, "turn_on", {"entity_id": eid})
                    ss["loads"][eid] = {"at": t}
                    log(f"自給率：電気が {sell_kw:.1f}kW 余っているので、{it.get('name') or eid} を入れました")
            elif eid in ss["loads"] and (not ld_on or not it or (buy_kw >= float(it.get("off_kw") or 0.3) and held("buy_since", 5))):
                ha_call(dom, "turn_off", {"entity_id": eid})
                ss["loads"].pop(eid, None)
                why = "買う電気が増えた" if ld_on and it else "時間外・設定がオフ"
                log(f"自給率：{why}ので、{it.get('name') or eid} を切りました")
        except Exception as e:
            print("自給率（余剰で動かす機器）でエラー:", e, flush=True)


def selfsuff_public():
    return {"rate": self_rate_today(), "show": bool(conf.get("ss_show")), "target": int(conf.get("ss_target_pct") or 0),
            "now": ss.get("last"), "alert": state.get("ss_alert"),
            "aircon": sorted(ss["aircon"]), "loads": sorted(ss["loads"]), "partial": state.get("solar_partial")}


def auto_now():
    """いま自動で動いていること（理由つき）。ホーム画面に1行ずつ出す"""
    out = []
    a = state.get("away")
    if a:
        out.append({"icon": "🧳", "text": "留守のため、お湯の沸き上げを止めています" if a == "stop" else "留守からの帰りに合わせて、お湯を沸かしています"})
    g = guard_public()
    if g.get("active"):
        out.append({"icon": "⏸", "text": "買う電気が多いので、お湯の沸き上げを一時停止しています" + (f"（{g['reason']}）" if g.get("reason") else "")})
    if state.get("by_us"):
        out.append({"icon": "☀", "text": "太陽光の余った電気で、お湯を沸かしています"})
    nh = state.get("night_heat")
    if nh and nh.get("start") and nh["start"] > time.time():
        out.append({"icon": "🌙", "text": f"今夜は {datetime.fromtimestamp(nh['start']).strftime('%H:%M')} からお湯を沸かします（朝に近いほど冷める分が少なく安いため）"})
    elif state.get("night_stop"):
        out.append({"icon": "🌙", "text": "晴れの予報なので、夜の沸き上げを止めて昼に沸かします"})
    sp = state.get("solar_partial")
    if sp and sp.get("frac") and sp.get("date") in (date.today().isoformat(), (date.today() + timedelta(days=1)).isoformat()):
        out.append({"icon": "⛅", "text": f"くもりでも昼に約{round(sp['frac'] * 100)}%ぶん沸かせる見込みなので、夜の沸き上げを減らしています"})
    if ss["aircon"]:
        out.append({"icon": "❄", "text": f"電気が余っているので、エアコン {len(ss['aircon'])}台を先に動かしています（買う電気が増えたら元の温度に戻します）"})
    if ss["loads"]:
        names = [next((x.get("name") for x in conf.get("ss_loads_items") or [] if x.get("entity") == e), None) or e for e in ss["loads"]]
        out.append({"icon": "🔌", "text": "電気が余っているので動かしています：" + "、".join(names)})
    al = state.get("demand_alert")
    if al and time.time() - al.get("at", 0) < 1800:
        out.append({"icon": "⚡", "text": "この30分の電気が上限に近づいています"})
    return out


def demand_report(day):
    key_day = day
    lim, how = demand_limit()
    with lock:
        slots = [dict(v, t=k[11:]) for k, v in sorted(energy30.items()) if k.startswith(key_day)]
        # 月ごとの最大（直近12か月）
        months = {}
        for k, v in energy30.items():
            for f in ("use", "buy"):
                kw = float(v.get(f) or 0) * 2 / 1000
                m = months.setdefault(k[:7], {"use": 0.0, "buy": 0.0})
                m[f] = max(m[f], round(kw, 2))
    return {"date": day, "slots": slots, "limit_kw": lim, "limit_how": how, "margin_kw": float(conf.get("demand_margin_kw") or 0.5),
            "target": conf.get("demand_target"), "months": dict(sorted(months.items())[-13:]), "now": demand_state.get("last")}


def ha_notify_services():
    try:
        svcs = ha_req("GET", "/api/services") or []
        for d in svcs:
            if d.get("domain") == "notify":
                return sorted("notify." + k for k in (d.get("services") or {}))
    except Exception:
        pass
    return []


def energy_loop():
    saved = time.time()
    while True:
        try:
            energy_collect()
        except Exception as e:
            print("電気の記録でエラー:", e, flush=True)
        if time.time() - aiseg_daily["at"] > 55:
            try:
                aiseg_daily_tick()
            except Exception as e:
                aiseg_daily["at"] = time.time()
                print("回路ごとの使用量でエラー:", e, flush=True)
        if time.time() - saved > 300:
            with lock:
                save_json(ENERGY_PATH, energy)
                save_json(ENERGY5_PATH, energy5)
                save_json(ENERGY30_PATH, energy30)
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
    base = ha_base_url()
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
    if is_el_entity(eid):
        return el_call(eid, a, data)
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


# ================= ECHONET Lite の機器を、このサーバーから操作する =================
# アプリでは ECHONET Lite の機器を「climate.el_192_168_1_50_013001」のような ID で表示している（Home Assistant には無い機器）。
# スケジュール・オートメーションは Home Assistant が動かすので、その操作は Home Assistant のイベント「homepanel_el」として送り、
# このサーバーが受け取って ECHONET Lite で機器に送る。まとめて操作・機器のタイマーも同じ関数を使う。
EL_MODE = {"auto": 0x41, "heat_cool": 0x41, "cool": 0x42, "heat": 0x43, "dry": 0x44, "fan_only": 0x45}
EL_FAN = {"auto": 0x41, "1": 0x31, "2": 0x32, "3": 0x33, "4": 0x34, "5": 0x35, "6": 0x36, "7": 0x37, "8": 0x38, "low": 0x31, "medium": 0x34, "high": 0x38}
EL_ID = re.compile(r"^(climate|light|switch|cover|fan)\.el_(\d{1,3})_(\d{1,3})_(\d{1,3})_(\d{1,3})_([0-9a-f]{6})$")


def is_el_entity(eid):
    """ECHONET Lite の機器の ID で、家の中のアドレスのもの"""
    try:
        el_target(eid)
        return True
    except ValueError:
        return False


def el_target(eid):
    m = EL_ID.match(str(eid or ""))
    if not m:
        raise ValueError("ECHONET Lite の機器の ID ではありません")
    ip = ".".join(m.group(i) for i in range(2, 6))
    if not (ip.startswith("192.168.") or ip.startswith("10.") or re.match(r"^172\.(1[6-9]|2\d|3[01])\.", ip)):
        raise ValueError("家の中の機器だけ操作できます")
    return m.group(1), ip, m.group(6).upper()


# ---- 「名乗って」の依頼（ECHONET Lite のインスタンスリスト通知要求：0x63 / EPC 0xD5） ----
el_rescue_state = {"last": 0, "sent": [], "error": None}


def el_rescue_targets():
    """名乗らせる相手：アプリで登録したエアコン（0x0130）と、設定で足した IP。家の中の IP だけ"""
    ips = []
    for d in conf.get("el_devices") or []:
        m = EL_ID.match(str(d.get("id") or "")) if isinstance(d, dict) else None
        if m and m.group(6).lower().startswith("0130"):
            ips.append(".".join(m.group(i) for i in range(2, 6)))
    ips += [str(x) for x in conf.get("el_known_ips") or []]
    ips += [str(x) for x in conf.get("el_rescue_ips") or []]
    ok = []
    for ip in ips:
        if re.fullmatch(r"(192\.168|10\.\d{1,3})\.\d{1,3}\.\d{1,3}", ip) and ip not in ok:
            ok.append(ip)
    return ok


def el_rescue_frame(tid):
    # EHD 1081 / TID / SEOJ 05FF01（コントローラ）/ DEOJ 0EF001（ノードプロファイル）/ ESV 0x63 / OPC 1 / EPC D5 / PDC 0
    return bytes.fromhex("1081") + int(tid & 0xFFFF).to_bytes(2, "big") + bytes.fromhex("05ff010ef0016301d500")


def el_rescue_tick(now=None, sender=None):
    if not conf.get("el_rescue", True):
        return []
    now = now or time.time()
    every = max(1.0, min(30.0, float(conf.get("el_rescue_min") or 3))) * 60
    if now - el_rescue_state["last"] < every:
        return []
    el_rescue_state["last"] = now
    targets = el_rescue_targets()
    if not targets:
        return []
    sent = []
    try:
        if sender is None:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(1)
            sender = lambda data, ip: sock.sendto(data, (ip, 3610))
        for i, ip in enumerate(targets):
            try:
                sender(el_rescue_frame(int(now) + i), ip)
                sent.append(ip)
            except OSError as e:
                el_rescue_state["error"] = f"{ip}: {e}"
    finally:
        try:
            sock.close()
        except Exception:
            pass
    el_rescue_state["sent"] = sent
    return sent


def el_rescue_loop():
    while True:
        try:
            el_rescue_tick()
        except Exception as e:
            print("名乗りの依頼でエラー:", e, flush=True)
        time.sleep(30)


def el_call(eid, service, data=None):
    """ECHONET Lite の機器を操作する（アプリの操作と同じ対応）。失敗したら例外"""
    data = data or {}
    domain, ip, eoj = el_target(eid)
    on, off = (0x80, b"\x30"), (0x80, b"\x31")
    props = []
    if service in ("turn_on", "turn_off"):
        props = [on if service == "turn_on" else off]
        if domain == "light" and service == "turn_on" and data.get("brightness_pct") is not None:
            props.append((0xB0, bytes([max(0, min(100, int(data["brightness_pct"])))])))
    elif service == "toggle":
        v = el.get(ip, eoj, [0x80], timeout=4) or {}
        props = [off if v.get(0x80) == b"\x30" else on]
    elif domain == "climate" and service in ("set_hvac_mode", "set_temperature"):
        mode = data.get("hvac_mode")
        if mode == "off":
            props = [off]
        else:
            if mode:
                if mode not in EL_MODE:
                    raise ValueError(f"この運転モード（{mode}）には対応していません")
                props += [on, (0xB0, bytes([EL_MODE[mode]]))]
            if data.get("temperature") is not None and mode not in ("fan_only", "dry"):
                props.append((0xB3, bytes([max(0, min(50, int(round(float(data["temperature"])))))])))
            if data.get("fan_mode") is not None and str(data["fan_mode"]) in EL_FAN:
                props.append((0xA0, bytes([EL_FAN[str(data["fan_mode"])]])))
    elif domain == "climate" and service == "set_fan_mode":
        f = str(data.get("fan_mode"))
        if f not in EL_FAN:
            raise ValueError(f"この風量（{f}）には対応していません")
        props = [(0xA0, bytes([EL_FAN[f]]))]
    elif domain == "cover" and service in ("open_cover", "close_cover", "stop_cover"):
        props = [(0xE0, bytes([{"open_cover": 0x41, "close_cover": 0x42, "stop_cover": 0x43}[service]]))]
    elif domain == "cover" and service == "set_cover_position":
        props = [(0xE1, bytes([max(0, min(100, int(data.get("position") or 0)))]))]
    if not props:
        raise ValueError(f"この操作（{service}）には対応していません")
    for epc, edt in props:
        err = el.setc(ip, eoj, epc, edt)
        if err:
            raise RuntimeError(err)


def ha_ws_loop():
    """Home Assistant の WebSocket につなぎ、イベント homepanel_el（スケジュール・オートメーションからの ECHONET Lite の操作）を受け取る"""
    import base64
    import socket
    import struct
    import urllib.parse as up

    def send(sock, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        n = len(data)
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126); hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127); hdr += struct.pack(">Q", n)
        mask = os.urandom(4)
        hdr += mask
        sock.sendall(bytes(hdr) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv_exact(sock, n):
        b = b""
        while len(b) < n:
            c = sock.recv(n - len(b))
            if not c:
                raise ConnectionError("切れました")
            b += c
        return b

    def recv(sock):
        buf = b""
        while True:
            h = recv_exact(sock, 2)
            fin, op, n = h[0] & 0x80, h[0] & 0x0F, h[1] & 0x7F
            if n == 126:
                n = struct.unpack(">H", recv_exact(sock, 2))[0]
            elif n == 127:
                n = struct.unpack(">Q", recv_exact(sock, 8))[0]
            if h[1] & 0x80:
                recv_exact(sock, 4)
            payload = recv_exact(sock, n)
            if op == 0x8:
                raise ConnectionError("Home Assistant が接続を閉じました")
            if op == 0x9:  # ping → pong
                sock.sendall(bytes([0x8A, 0x80 | len(payload)]) + b"\0\0\0\0" + payload)
                continue
            if op in (0x1, 0x0, 0x2):
                buf += payload
                if fin:
                    return json.loads(buf.decode("utf-8", "replace"))

    while True:
        base, tok = ha_base_url(), conf.get("ha_token") or ""
        if not base or not tok:
            time.sleep(60)
            continue
        try:
            u = up.urlparse(base)
            port = u.port or (443 if u.scheme == "https" else 80)
            if u.scheme == "https":
                time.sleep(300)  # 家の中の http だけ対応
                continue
            sock = socket.create_connection((u.hostname, port), timeout=30)
            key = base64.b64encode(os.urandom(16)).decode()
            sock.sendall((f"GET /api/websocket HTTP/1.1\r\nHost: {u.hostname}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
            head = b""
            while b"\r\n\r\n" not in head:
                c = sock.recv(1024)
                if not c:
                    raise ConnectionError("返事がありません")
                head += c
            if b" 101 " not in head.split(b"\r\n")[0]:
                raise ConnectionError(head.split(b"\r\n")[0].decode(errors="replace"))
            sock.settimeout(None)
            recv(sock)  # auth_required
            send(sock, {"type": "auth", "access_token": tok})
            r = recv(sock)
            if r.get("type") != "auth_ok":
                raise ConnectionError("Home Assistant の鍵が使えません")
            send(sock, {"id": 1, "type": "subscribe_events", "event_type": "homepanel_el"})
            state["ha_ws"] = "つながっています"
            sock.settimeout(50)
            last_ping = time.time()
            while True:
                try:
                    m = recv(sock)
                except socket.timeout:
                    m = None
                if time.time() - last_ping > 45:
                    last_ping = time.time()
                    send(sock, {"id": int(time.time()), "type": "ping"})
                if not m or m.get("type") != "event":
                    continue
                d = (m.get("event") or {}).get("data") or {}
                eid, svc = d.get("entity_id"), d.get("service")
                name = d.get("name") or eid

                def run(eid=eid, svc=svc, d=d, name=name):
                    try:
                        el_call(eid, svc, d.get("data") or {})
                        log(f"自動化：{name} を操作しました（ECHONET Lite・{svc}）")
                    except Exception as e:
                        log(f"自動化：{name} を操作できませんでした（ECHONET Lite：{e}）")
                threading.Thread(target=run, daemon=True).start()
        except Exception as e:
            state["ha_ws"] = f"つながっていません（{e}）"
            print("Home Assistant の WebSocket:", e, flush=True)
            time.sleep(30)


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
    info = None
    try:
        sunny, info = forecast_solar(target)
        note = info["note"]
        state["forecast"] = dict(info, date=target.isoformat(), at=time.time())
    except Exception as e:
        print("日射量の予報を使えませんでした:", e, flush=True)
        try:
            sunny, code = forecast_sunny(target)
            note = "（日射量の予報が使えないため、天気の種類で判断）"
        except Exception as e2:
            return result(False, f"{name}の天気予報を読めませんでした：{e2}")
    # くもりの日でも、昼に沸かせる見込みの分だけ夜を減らす（オプション・「夜は止める」方式のとき）
    state["solar_partial"] = None
    if not sunny and conf.get("ss_partial") and conf.get("night_method") == "stop" and isinstance(info, dict) and info.get("need"):
        frac = float(info.get("surplus") or 0) / float(info["need"])
        if frac >= 0.3:
            state["solar_partial"] = {"date": target.isoformat(), "frac": round(min(frac, 0.95), 2)}
            sunny = True
            note += f"（くもりでも昼に約{round(min(frac, 0.95) * 100)}%ぶん沸かせる見込みなので、夜はその分だけ減らします）"
    base_result = result

    def result(ok, msg, sunny_=None):
        return base_result(ok, msg + (note if ok else ""), sunny_)
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
            save_json(STATE_PATH, {k: state.get(k) for k in ("plan", "log", "by_us", "shift_unsupported", "tank_by_us", "night_stop", "night_pending", "night_resume", "night_heat")})
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
hot_last = {"remain": None, "ref": None}
HOT_DEADBAND_L = 10  # 残りのお湯の量の細かい上下（表示のゆれ）は数えない


def hot_water_track(eco, now=None):
    r = (eco or {}).get("remain")
    if r is None:
        return
    now = now or datetime.now()
    prev = hot_last["remain"]
    hot_last["remain"] = r
    if prev is None:
        hot_last["ref"] = r
        return
    d = now.date().isoformat()
    # 冷めて減る分の予想（外気温の予報を読むことがあるので、lock の外で）
    r0h = (energy.get("days", {}).get(d) or {}).get("hot_r0_h")
    loss = 0.0
    if r0h is not None and state.get("hot_from_aiseg"):
        try:
            loss = loss_liters(datetime.combine(now.date(), datetime.min.time()) + timedelta(hours=r0h), now)
        except Exception:
            loss = 0.0
    with lock:
        day = energy.setdefault("days", {}).setdefault(d, {})
        # 増えた・減ったは、前に数えたところ（ref）から10L以上動いたときだけ数える。
        # 1分ごとの値は数Lずつ上下にゆれることがあり、そのまま足すと沸かした量・使った量が大きくなりすぎるため
        ref = hot_last["ref"] if hot_last["ref"] is not None else prev
        if r <= ref - HOT_DEADBAND_L:
            if not state.get("hot_from_aiseg"):
                day["hot_use_l"] = day.get("hot_use_l", 0) + (ref - r)
                hh = day.setdefault("hot_h", [0.0] * 24)
                hh[now.hour] = round(hh[now.hour] + (ref - r), 1)
            hot_last["ref"] = r
        elif r >= ref + HOT_DEADBAND_L:
            day["hot_heat_ramp_l"] = round(day.get("hot_heat_ramp_l", 0) + (r - ref), 1)
            hot_last["ref"] = r
        # その日の最初の残りの量（起点）
        if "hot_r0" not in day:
            day.update(hot_r0=prev, hot_r0_h=now.hour + now.minute / 60, hot_heat_base=day.get("hot_heat_ramp_l", 0),
                       hot_use_base=day.get("hot_use_l", 0))
        ramp = day.get("hot_heat_ramp_l", 0)
        if state.get("hot_from_aiseg"):
            # 使った量が AiSEG2 から分かるときは、収支で上限をかける：
            # 沸かした量 ≦ （いまの残り − 起点の残り）＋ 使った量 ＋ 冷めて減る分（＋ゆとり20L）
            bal = max(0.0, r - day["hot_r0"] + day.get("hot_use_l", 0) - day.get("hot_use_base", 0) + loss)
            day["hot_heat_l"] = round(min(ramp, day.get("hot_heat_base", 0) + bal + 20), 1)
        else:
            day["hot_heat_l"] = round(ramp, 1)
        day["hot_tank_l"] = (eco or {}).get("tank")
        # 1時間ごとの残りのお湯（その時刻の最初の値）と、沸き上げをしたか（冷めて減る量を学ぶため）
        hr = datetime.now().hour
        rh = day.setdefault("remain_h", [None] * 24)
        if rh[hr] is None:
            rh[hr] = r
        hh_ = day.setdefault("heat_h", [False] * 24)
        if (eco or {}).get("heating") or r >= prev + HOT_DEADBAND_L:
            hh_[hr] = True
        # 残りのお湯の変化（5分ごと・2日分）。エコキュートの画面のグラフ用
        hist = energy.setdefault("hot", [])
        now = time.time()
        if not hist or now - hist[-1][0] >= 300 or abs(hist[-1][1] - r) >= 20:
            hist.append([int(now), r])
            cut = now - 2 * 86400
            while hist and hist[0][0] < cut:
                hist.pop(0)


# ---- お湯の量の予想（過去の使用量から） ----
# 1時間ごとの記録が無い日（日の合計だけの日）に使う、ふつうの家の1日の使い方の割合（0〜23時）
HOT_PROFILE = [1, 0.5, 0.3, 0.3, 0.5, 1.5, 4, 6, 4, 2.5, 2, 2, 2.5, 2, 1.5, 1.5, 2.5, 5, 9, 13, 14, 12, 8, 4]


def hot_day_type(d):
    """平日か休日（土日祝）か。休日はお湯の使い方が違うことが多いので分けて予想する"""
    if d.weekday() >= 5:
        return "holiday"
    try:
        if d.isoformat() in set(tariffs().get("holidays") or []):
            return "holiday"
    except Exception:
        pass
    return "weekday"


def hot_used(rec, h0, h1):
    """その日の h0 時〜h1 時（h1 は含まない）に使ったお湯（L）。1時間ごとの記録が無ければ日の合計から割合で"""
    hh = rec.get("hot_h")
    if hh and len(hh) >= 24 and sum(hh) > 0:
        return sum(hh[h0:h1]), True
    tot = rec.get("hot_use_l")
    if not tot:
        return None, False
    return tot * sum(HOT_PROFILE[h0:h1]) / sum(HOT_PROFILE), False


def _quantile(vals, q):
    v = sorted(vals)
    if not v:
        return None
    k = (len(v) - 1) * q
    i = int(k)
    return v[i] + (v[min(i + 1, len(v) - 1)] - v[i]) * (k - i)


def hot_clean(items):
    """お湯の記録から、当てにならない日を除く。items：[(その時間帯の量, 1日の合計, 1時間ごとの記録か)]
    ・1日の合計が 0L（10L 未満）の日：留守・記録の抜け
    ・1日の合計が、ほかの日の中央値の 4割未満／2.5倍より多い日：極端に少ない・多い日
    ・その時間帯の量が、四分位の範囲から大きく外れる日（1.5×四分位範囲の外）
    戻り値：(残った items, 除いた日数)"""
    ok = [x for x in items if (x[1] or 0) >= 10]
    removed = len(items) - len(ok)
    if len(ok) >= 4:
        med = _quantile([x[1] for x in ok], 0.5) or 0
        if med > 0:
            keep = [x for x in ok if 0.4 * med <= x[1] <= 2.5 * med]
            removed += len(ok) - len(keep)
            ok = keep
    if len(ok) >= 5:
        q1, q3 = _quantile([x[0] for x in ok], 0.25), _quantile([x[0] for x in ok], 0.75)
        iqr = q3 - q1
        lo, hi = q1 - 1.5 * iqr, q3 + 1.5 * iqr
        keep = [x for x in ok if lo <= x[0] <= hi]
        if len(keep) >= 3:
            removed += len(ok) - len(keep)
            ok = keep
    return ok, removed


def predict_hot(d, h0, h1):
    """d の日の h0〜h1 時に使うお湯の予想（L）：直近4週間の同じ種類の日（平日／休日）の平均。少なければ全部の日。
    0L の日や、極端に多い・少ない日は除いて計算する（hot_clean）"""
    days = energy.get("days") or {}
    typ = hot_day_type(d)
    same, other = [], []
    for i in range(1, 29):
        dd = d - timedelta(days=i)
        if dd >= date.today():
            continue
        rec = days.get(dd.isoformat())
        if not rec:
            continue
        v, hourly = hot_used(rec, h0, h1)
        if v is None:
            continue
        tot = rec.get("hot_use_l")
        if tot is None and rec.get("hot_h"):
            tot = sum(rec["hot_h"])
        (same if hot_day_type(dd) == typ else other).append((v, float(tot or 0), hourly))
    same_c, rem_s = hot_clean(same)
    if len(same_c) >= 3:
        use, removed, kind = same_c, rem_s, ("休日" if typ == "holiday" else "平日")
    else:
        use, removed = hot_clean(same + other)
        kind = "すべての日"
    if not use:
        return None
    vals = [v for v, _, _ in use]
    base = sum(vals) / len(vals)
    # 翌日などの予想気温が、ふだん（学んだ日々の平均）より寒ければ多め、暖かければ少なめにする
    f, info = temp_factor(d) if d >= date.today() else (1.0, None)
    return {"liters": round(base * f), "base": round(base), "factor": f, "temp": info, "max": round(max(vals)), "days": len(vals), "removed": removed,
            "type": kind, "hourly": sum(1 for _, _, h in use if h), "p75": round((_quantile(vals, 0.75) or base) * f)}


def evening_end_hour(d):
    """夕方に用意したお湯を使う終わりの時刻（d の0時からの時間。24 を超えると翌日）：
    今夜の沸き上げを始める時刻（夜の沸き上げの計画があればその時刻、無ければ夜の始まり）"""
    h0 = int(conf.get("prep_hour", 17))
    end = max(h0 + 1, min(24, int(conf.get("night_start_hour", 23))))
    nh = state.get("night_heat") or {}
    if nh.get("start"):
        st = datetime.fromtimestamp(nh["start"])
        if st.date() == d + timedelta(days=1):
            end = max(end, min(24 + 9, 24 + st.hour))
        elif st.date() == d and st.hour > h0:
            end = max(end, st.hour)
    return end


def predict_span(d, h0, h1):
    """h0〜h1 時（h1 は 24 を超えてよい＝翌日の朝まで）の予想。日をまたぐときは2日分を足す"""
    if h1 <= 24:
        return predict_hot(d, h0, h1)
    a = predict_hot(d, h0, 24)
    b = predict_hot(d + timedelta(days=1), 0, h1 - 24)
    if a is None or b is None:
        return a or b
    out = dict(a)
    for k in ("liters", "base", "max", "p75"):
        out[k] = (a.get(k) or 0) + (b.get(k) or 0)
    return out


# ================= 気温による、お湯の使い方・冷めて減る量の学習 =================
# 沸かしたお湯は、外気との温度差に比例して冷める（残りのお湯の量が減る）。寒い日はお湯を多く使う。
# 記録（1時間ごとの残りのお湯・使った量・外気温）から、この2つを学び、翌日の気温の予報で予想を直す。
LOSS_DEFAULT = 0.017  # 冷めて減る量の目安（L／時／℃）：460L のタンクで外気との差 55℃ のとき、1時間に約 1L


def learn_loss():
    """冷めて減る量（L／時／℃）を学ぶ：沸き上げもお湯の使用もほとんど無い1時間に、残りのお湯が減った量 ÷ (タンクの温度 − 外気温)。
    直近30日の中央値。記録が少なければ目安の値"""
    days = energy.get("days") or {}
    t_tank = float(conf.get("tank_temp") or 65)
    samples = []
    for i in range(1, 31):
        d = (date.today() - timedelta(days=i)).isoformat()
        rec = days.get(d) or {}
        rh, hh, uh, th = rec.get("remain_h"), rec.get("heat_h"), rec.get("hot_h"), rec.get("t_h")
        if not (rh and hh and uh and th):
            continue
        for h in range(23):
            if rh[h] is None or rh[h + 1] is None or hh[h] or hh[h + 1] or (uh[h] or 0) > 2 or th[h] is None:
                continue
            dt = t_tank - th[h]
            if dt < 10:
                continue
            samples.append(max(0.0, rh[h] - rh[h + 1]) / dt)
    if len(samples) < 12:
        return {"k": LOSS_DEFAULT, "n": len(samples), "how": "目安（記録がたまると、このお宅の値に変わります）"}
    k = _quantile(samples, 0.5)
    k = max(0.002, min(0.08, k))
    return {"k": round(k, 4), "n": len(samples), "how": f"記録 {len(samples)} 時間分から"}


def learn_draw_ratio():
    """使ったお湯（AiSEG2 の使用量・蛇口で混ぜた後の量）1L で、タンクの残りのお湯（エコキュートが知らせる量）が何L減るかを学ぶ。
    エコキュートの残湯量は、タンクの熱いお湯の量で知らせる機種が多く、蛇口のお湯（約40℃）より少なく減る。
    沸き上げの無い1時間で、20L 以上使った時間の「残りの減り − 冷めて減る分」÷「使った量」の中央値（直近30日）。記録が少なければ 1"""
    days = energy.get("days") or {}
    k = learn_loss()["k"]
    t_tank = float(conf.get("tank_temp") or 65)
    samples = []
    for i in range(1, 31):
        rec = days.get((date.today() - timedelta(days=i)).isoformat()) or {}
        rh, hh, uh, th = rec.get("remain_h"), rec.get("heat_h"), rec.get("hot_h"), rec.get("t_h") or [None] * 24
        if not (rh and hh and uh):
            continue
        for h in range(23):
            if rh[h] is None or rh[h + 1] is None or hh[h] or hh[h + 1] or (uh[h] or 0) < 20:
                continue
            loss = k * max(0.0, t_tank - (th[h] if th[h] is not None else 15))
            samples.append(max(0.0, rh[h] - rh[h + 1] - loss) / uh[h])
    if len(samples) < 10:
        return {"r": 1.0, "n": len(samples), "how": "記録がたまるまでは 1:1"}
    r = max(0.3, min(1.5, _quantile(samples, 0.5)))
    return {"r": round(r, 2), "n": len(samples), "how": f"記録 {len(samples)} 時間分から"}


def learn_usage_temp(typ):
    """1日に使うお湯（L）と、その日の平均気温の関係を学ぶ（直近60日の同じ種類の日。最小二乗法）。
    戻り値：{a, b, n, t_avg}（使う量 ≒ a + b × 平均気温）。日数が少ない・気温の幅が小さいときは None"""
    days = energy.get("days") or {}
    pts = []
    for i in range(1, 61):
        dd = date.today() - timedelta(days=i)
        rec = days.get(dd.isoformat()) or {}
        tot = rec.get("hot_use_l")
        if tot is None and rec.get("hot_h"):
            tot = sum(rec["hot_h"])
        if rec.get("t_mean") is None or not tot:
            continue
        pts.append((float(tot), float(tot), rec["t_mean"], hot_day_type(dd) == typ))
    # 同じ種類の日（平日／休日）が10日に足りなければ、全部の日で学ぶ（気温による増え方は、どちらもあまり変わらないため）
    same = [x for x in pts if x[3]]
    use = same if len(same) >= 10 else pts
    # 0L の日や極端な日を除く（hot_clean は（その時間帯の量, 1日の合計, …）の形）
    clean, _ = hot_clean([(a, b, t) for a, b, t, _ in use])
    xs = [t for _, _, t in clean]
    ys = [a for a, _, _ in clean]
    if len(xs) < 10 or max(xs) - min(xs) < 4:
        return None
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    a = my - b * mx
    return {"a": a, "b": b, "n": len(xs), "t_avg": mx}


def forecast_mean(d):
    """d の日の平均気温の予報（℃）。分からなければ None"""
    temps = outdoor_temps()
    vals = [temps.get(f"{d.isoformat()}T{h:02d}") for h in range(24)]
    vals = [v for v in vals if v is not None]
    return round(sum(vals) / len(vals), 1) if len(vals) >= 18 else None


def temp_factor(d):
    """d の日の予想気温で、お湯の使う量の予想を何倍にするか（0.7〜1.4）。学べていなければ 1"""
    if not conf.get("hot_temp_learn", True):
        return 1.0, None
    lr = learn_usage_temp(hot_day_type(d))
    tf = forecast_mean(d)
    if not lr or tf is None:
        return 1.0, {"t_forecast": tf, "learned": bool(lr)}
    base = lr["a"] + lr["b"] * lr["t_avg"]
    want = lr["a"] + lr["b"] * tf
    if base <= 0:
        return 1.0, None
    f = max(0.7, min(1.4, want / base))
    return round(f, 3), {"t_forecast": tf, "t_avg": round(lr["t_avg"], 1), "per_deg": round(-lr["b"], 1), "days": lr["n"], "factor": round(f, 3)}


def loss_liters(start, end):
    """start から end までに、冷めて減るお湯の量（L）の予想：k × Σ（タンクの温度 − 外気温の予報）"""
    if end <= start:
        return 0.0
    k = learn_loss()["k"]
    temps = outdoor_temps()
    t_tank = float(conf.get("tank_temp") or 65)
    tot, t = 0.0, start
    while t < end:
        dh = min(1.0, (end - t).total_seconds() / 3600)
        tot += k * max(0.0, t_tank - temp_at(t, temps)) * dh
        t += timedelta(hours=dh)
    return tot


def hot_extra_for(d, part):
    ex = [x for x in conf.get("hot_extra") or [] if x.get("date") == d.isoformat() and (x.get("part") or "evening") == part]
    return sum(int(x.get("liters") or 0) for x in ex), "・".join(x.get("note") for x in ex if x.get("note"))


def heat_rate():
    """沸き上げの速さ（L/時）：設定、または残りのお湯の増え方の記録（中央値）から。分からなければ 80L/時"""
    v = float(conf.get("heat_rate_lph") or 0)
    if v > 0:
        return v, "設定"
    hist = energy.get("hot") or []
    rates = []
    for (t0, r0), (t1, r1) in zip(hist, hist[1:]):
        dt = (t1 - t0) / 3600
        if 0.05 <= dt <= 0.4 and r1 > r0:
            rates.append((r1 - r0) / dt)
    if len(rates) >= 4:
        rates.sort()
        return max(30.0, min(300.0, rates[len(rates) // 2])), "記録から"
    return 80.0, "目安"


def evening_need(d=None, h_from=None):
    """d（今日）の夕方に残しておくお湯：夕方（prep_hour 時）から今夜の沸き上げが始まるまでに使う予想＋余裕＋多めの日の分＋冷めて減る分。
    予想は、直近の同じ種類の日の平均と、多めの日寄りの値（上位25%）の大きい方。h_from を渡すと、その時刻からの残りの分"""
    d = d or date.today()
    ph = int(conf.get("prep_hour", 17))
    h0 = max(ph, int(h_from)) if h_from is not None else ph
    h1 = evening_end_hour(d)
    if h0 >= h1:
        h0 = h1 - 1
    p = predict_span(d, h0, h1)
    extra, note = hot_extra_for(d, "evening")
    margin = int(conf.get("hot_margin_l") or 0)
    # 用意する時刻から、使い終わる時刻までに冷めて減る分も足す
    day0 = datetime.combine(d, datetime.min.time())
    loss = round(loss_liters(day0 + timedelta(hours=h0), day0 + timedelta(hours=h1)) * 0.5) if conf.get("hot_temp_learn", True) else 0
    if p is None:
        return {"date": d.isoformat(), "from": h0, "to": h1, "predicted": None, "margin": margin, "extra": extra, "note": note,
                "need": (margin + extra) or None, "how": "記録がまだありません"}
    use = max(p["liters"], p.get("p75") or 0)
    how = f"直近の{p['type']} {p['days']}日の平均" + (f"（0L や極端な {p['removed']}日を除く）" if p.get("removed") else "")
    if use > p["liters"]:
        how += f"より多めの日寄り（平均 {p['liters']}L → {use}L）"
    dr = learn_draw_ratio()
    tank_use = round(use * dr["r"])
    tank_extra = round(extra * dr["r"])
    if abs(dr["r"] - 1) >= 0.05:
        how += f"・使った 1L でタンクの残りは {dr['r']}L 減る（{dr['how']}）ので {use}L → {tank_use}L"
    return {"date": d.isoformat(), "from": h0, "to": h1, "predicted": tank_use, "tap": use, "avg": p["liters"], "max": p["max"], "margin": margin,
            "extra": tank_extra, "note": note, "need": tank_use + margin + tank_extra + loss, "how": how + temp_note(p), "loss": loss,
            "temp": p.get("temp"), "ratio": dr["r"]}


def temp_note(p):
    t = p.get("temp") or {}
    if not t or not t.get("factor") or abs(t["factor"] - 1) < 0.01:
        return ""
    return f"・明日の予想気温 {t['t_forecast']}℃（ふだん {t['t_avg']}℃）なので {round((t['factor'] - 1) * 100):+d}%"


def night_need(d):
    """「夜は止める」の夜に沸かす量：d（翌日）の朝（0時〜昼の沸き上げの始まり）に使う予想＋余裕＋多めの日の分"""
    h1 = max(1, min(24, int(conf.get("start_hour", 9))))
    p = predict_hot(d, 0, h1)
    extra, note = hot_extra_for(d, "morning")
    margin = int(conf.get("hot_margin_l") or 0)
    # 夜に沸かし終わってから、朝に使い終わるまでに冷めて減る分（夜の安い時間の終わりごろに沸き終わるとして）
    day0 = datetime.combine(d, datetime.min.time())
    ne = int(conf.get("night_end_hour", 7))
    loss = round(loss_liters(day0 + timedelta(hours=min(ne, h1) - 1), day0 + timedelta(hours=h1))) if conf.get("hot_temp_learn", True) else 0
    if p is None:
        return {"date": d.isoformat(), "from": 0, "to": h1, "predicted": None, "margin": margin, "extra": extra, "note": note, "need": None,
                "how": "記録がまだありません"}
    dr = learn_draw_ratio()
    tank_use, tank_extra = round(p["liters"] * dr["r"]), round(extra * dr["r"])
    how = f"直近の{p['type']} {p['days']}日の平均" + (f"（0L や極端な {p['removed']}日を除く）" if p.get("removed") else "") + temp_note(p)
    if abs(dr["r"] - 1) >= 0.05:
        how += f"・タンクの残りに直すと ×{dr['r']}"
    return {"date": d.isoformat(), "from": 0, "to": h1, "predicted": tank_use, "tap": p["liters"], "max": p["max"], "margin": margin, "extra": tank_extra,
            "note": note, "need": tank_use + margin + tank_extra + loss, "how": how, "loss": loss, "temp": p.get("temp"), "ratio": dr["r"]}


def night_target_pct(d=None):
    """「夜は止める」で夜に沸かす量（%）。自動なら、翌朝の予想＋余裕から（20〜100%）。記録が無ければ設定の値"""
    base = int(conf.get("night_heat_pct", 50))
    tank = (state.get("eco") or {}).get("tank")
    if d is None:
        now = datetime.now()
        d = now.date() if now.hour < 12 else now.date() + timedelta(days=1)
    pct = base
    if conf.get("hot_auto") and tank:
        n = night_need(d)
        if n.get("need"):
            pct = max(20, min(100, -(-int(n["need"]) * 100 // tank)))
    # くもりの日：昼に沸かせない見込みの分を、夜に足す
    sp = state.get("solar_partial")
    if sp and sp.get("frac") and sp.get("date") == d.isoformat():
        pct = min(100, int(round(pct + (100 - pct) * (1 - sp["frac"]))))
    return pct


# ================= 夜の沸き上げの時刻を決める（冷める量と料金から、いちばん安い開始時刻） =================
_temps_cache = {"at": 0, "data": {}}
# 水道水の温度の目安（月ごと・℃）。沸かすのに要る熱の計算に使う
SUPPLY_TEMP = [7, 6, 8, 12, 16, 20, 23, 25, 24, 20, 15, 10]


def outdoor_temps():
    """外気温の予報（1時間ごと・℃）：{"YYYY-MM-DDTHH": 温度}。1時間ごとに読み直す"""
    if time.time() - _temps_cache["at"] < 3600:
        return _temps_cache["data"]
    lat, lon = conf.get("lat"), conf.get("lon")
    if lat is None or lon is None:
        return {}
    try:
        q = {"latitude": lat, "longitude": lon, "timezone": "Asia/Tokyo", "past_days": 31, "forecast_days": 3, "hourly": "temperature_2m"}
        with urllib.request.urlopen("https://api.open-meteo.com/v1/forecast?" + urllib.parse.urlencode(q), timeout=20) as r:
            h = json.load(r).get("hourly") or {}
        data = {t[:13]: v for t, v in zip(h.get("time") or [], h.get("temperature_2m") or []) if v is not None}
        _temps_cache.update(at=time.time(), data=data)
        # 過去の日の外気温（日ごとの平均と1時間ごと）を記録に残す（気温とお湯の関係を学ぶため）
        by_day = {}
        for k_, v_ in data.items():
            by_day.setdefault(k_[:10], [None] * 24)[int(k_[11:13])] = round(float(v_), 1)
        today_s = date.today().isoformat()
        with lock:
            for d_, hrs in by_day.items():
                if d_ >= today_s or None in hrs:
                    continue
                rec = energy.setdefault("days", {}).setdefault(d_, {})
                rec["t_h"] = hrs
                rec["t_mean"] = round(sum(hrs) / 24, 1)
    except Exception as e:
        print("外気温の予報を読めませんでした:", e, flush=True)
        # 読めないときは10分後にやり直す（画面の表示が毎回待たされないように）
        _temps_cache["at"] = time.time() - 3000
    return _temps_cache["data"]


def temp_at(dt, temps):
    v = temps.get(dt.strftime("%Y-%m-%dT%H"))
    if v is not None:
        return float(v)
    return 8.0 if dt.month in (12, 1, 2, 3) else 15.0 if dt.month in (4, 5, 10, 11) else 22.0


def cop_at(t_out):
    """ヒートポンプの効率（COP）の目安：外気温が低いほど下がる（7℃で約3.2、-7℃で約2.4）"""
    return max(1.5, min(5.0, 3.2 + 0.06 * (t_out - 7)))


_rate_cache = {}


def rate_at(dt):
    """その時刻の電気の単価（円/kWh・賦課金など込み）。プランが無ければ None"""
    k = (conf.get("tariff_plan"), dt.strftime("%Y-%m-%dT%H"))
    if k in _rate_cache:
        return _rate_cache[k]
    try:
        hh = [{"h": dt.strftime("%Y-%m-%dT%H"), "buy": 1000, "sell": 0}]
        cost_hours(hh)
        v = hh[0].get("rate")
    except Exception:
        v = None
    if len(_rate_cache) > 500:
        _rate_cache.clear()
    _rate_cache[k] = v
    return v


def night_window(now):
    """今夜の安い時間：(始まり, 終わり)。始まりは night_start_hour、終わりは night_end_hour（翌朝）"""
    ns, ne = night_start_h(), int(conf.get("night_end_hour", 7))
    base = now.date() if now.hour >= 12 else now.date() - timedelta(days=1)
    start = datetime.combine(base, datetime.min.time()) + timedelta(hours=ns)
    end = datetime.combine(base + timedelta(days=1), datetime.min.time()) + timedelta(hours=ne)
    return start, end


def plan_night_heat(liters, now=None):
    """liters（L）を今夜沸かすときの、いちばん安い開始時刻を選ぶ。
    費用 ＝ 沸かす電気（熱 ÷ その時刻の外気温での COP × 単価）＋ 沸かし終わりから朝までに冷める分を補う電気。
    早く沸かすほど朝までに冷める分が増えるので、料金が同じなら遅いほうが安い。外気温が下がると COP も下がるので、それも比べる"""
    now = now or datetime.now()
    w0, w1 = night_window(now)
    rate_lph, _ = heat_rate()
    hours = max(0.0, liters) / max(30.0, rate_lph)
    temps = outdoor_temps()
    t_tank = float(conf.get("tank_temp") or 65)
    t_in = SUPPLY_TEMP[(w1.month - 1) % 12]
    q_total = max(0.0, liters) * 4.186 * max(10.0, t_tank - t_in) / 3600  # 沸かすのに要る熱（kWh）
    k = float(conf.get("tank_loss_w_per_k") or 1.0) / 1000                 # kW/℃
    earliest = max(w0, now.replace(second=0, microsecond=0))
    latest = w1 - timedelta(hours=hours + 0.25)                             # 朝の15分前に沸き終わる
    if latest <= earliest:
        return {"start": earliest.timestamp(), "end": (earliest + timedelta(hours=hours)).timestamp(), "liters": round(liters),
                "hours": round(hours, 2), "how": "時間が足りないので、すぐに沸かします", "compare": []}
    step = timedelta(minutes=15)

    def cost(st):
        yen = kwh = 0.0
        t = st
        left = hours
        while left > 1e-6:
            dh = min(0.25, left)
            t_out = temp_at(t, temps)
            e = q_total * (dh / hours) / cop_at(t_out)
            r = rate_at(t)
            kwh += e
            yen += e * (r if r is not None else 0)
            t += timedelta(hours=dh)
            left -= dh
        fin = t
        # 沸き終わりから朝（w1）までに冷める熱 → 次の夜に補うとして、その分の電気代
        loss_th = 0.0
        t = fin
        while t < w1:
            loss_th += k * max(0.0, t_tank - temp_at(t, temps)) * 0.25
            t += timedelta(minutes=15)
        r_n = rate_at(w0) or 0
        loss_e = loss_th / cop_at(temp_at(w1, temps))
        return yen + loss_e * r_n, kwh + loss_e, loss_th
    rows, t = [], earliest
    while t <= latest:
        c, kw, loss = cost(t)
        rows.append((t, c, kw, loss))
        t += step
    # いちばん安い時刻。ただし差が night_late_tol_yen 円以内なら、できるだけ遅い時刻にする
    low = min(r[1] for r in rows)
    tol = float(conf.get("night_late_tol_yen", 1.0) or 0)
    best = [r for r in rows if r[1] <= low + tol][-1]
    first = rows[0]
    bt = best[0]
    return {"start": bt.timestamp(), "end": (bt + timedelta(hours=hours)).timestamp(), "liters": round(liters), "hours": round(hours, 2),
            "yen": round(best[1], 1), "kwh": round(best[2], 2), "loss_kwh": round(best[3], 2),
            "early_yen": round(first[1], 1), "early_start": first[0].timestamp(), "saving_yen": round(first[1] - best[1], 1),
            "rate_now": rate_at(bt), "t_out": round(temp_at(bt, temps), 1), "min_yen": round(low, 1),
            "choices": [{"start": r[0].strftime("%H:%M"), "yen": round(r[1], 1)} for r in rows[::4]],
            "how": f"{bt.strftime('%H:%M')} から約{hours:.1f}時間（{w1.strftime('%H:%M')} の15分前までに沸き終わる）"}


def night_heat_target(sunny):
    """今夜沸かす量（L）。晴れなら翌朝の分（「夜は止める」の目標）、晴れなければ翌日1日分（予想＋余裕）。予想が無ければ満タン"""
    tank = (state.get("eco") or {}).get("tank") or 0
    d = plan_target()
    if sunny:
        return tank * night_target_pct(d) // 100 if tank else None
    if conf.get("hot_auto"):
        p = predict_hot(d, 0, 24)
        if p:
            ex = sum(hot_extra_for(d, part)[0] for part in ("morning", "evening"))
            # 沸き終わり（朝）から夜まで、使う量が減るにつれて冷める量も減るので半分を見込む
            day0 = datetime.combine(d, datetime.min.time())
            loss = loss_liters(day0 + timedelta(hours=int(conf.get("night_end_hour", 7))), day0 + timedelta(hours=night_start_h())) * 0.5 \
                if conf.get("hot_temp_learn", True) else 0
            return min(int(tank * 0.98) if tank else 10 ** 6, int(p["liters"] + int(conf.get("hot_margin_l") or 0) + ex + loss))
    return int(tank * 0.98) if tank else None


def night_heat_tick(eco, now):
    """夜の沸き上げ（できるだけ遅く）：決めた時刻に手動で沸かし始め、目標の量になったら止める"""
    nh = state.get("night_heat")
    if not nh or not eco or eco.get("remain") is None:
        return
    remain = eco["remain"]
    w0, w1 = night_window(now)
    if now.timestamp() > nh.get("until", 0):
        if nh.get("started") and eco.get("heating"):
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
        log("夜の沸き上げ：朝の時間を過ぎたので終わりにしました" + (f"（{remain}L）" if nh.get("started") else "（沸かしませんでした）"))
        state["night_heat"] = None
        return
    target = int(nh["target"])
    if not nh.get("started"):
        # 残りのお湯に合わせて、15分ごとに開始時刻を見直す
        if time.time() - nh.get("planned_at", 0) > 900:
            pl = plan_night_heat(max(0, target - remain), now)
            nh.update(plan=pl, start=pl["start"], planned_at=time.time())
        if remain >= target:
            nh["status"] = f"もう {remain}L あるので沸かしません（目標 {target}L）"
            return
        if now.timestamp() >= nh["start"]:
            s_ = eco_caps["set"]
            if s_ is None or 0xC0 in s_:
                el.setc(conf["eco_ip"], conf["eco_eoj"], 0xC0, b"\x41")
            err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
            if not err:
                nh.update(started=True, started_at=time.time(), resent=time.time())
                pl = nh.get("plan") or {}
                log(f"夜の沸き上げを始めました：{remain}L → {target}L（約{pl.get('hours', 0)}時間"
                    + (f"・23時から沸かすより約{pl['saving_yen']}円安い見込み" if pl.get("saving_yen") else "") + "）")
            else:
                log(f"夜の沸き上げを始められませんでした（{err}）")
        return
    if remain >= target:
        el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
        with lock:
            state["night_stop"] = True
            state["night_heat"] = None
        log(f"夜の沸き上げを終えました（{remain}L・目標 {target}L）。止めたままにします")
        return
    if not eco.get("heating") and time.time() - nh.get("resent", 0) > 600 and not guard["active"]:
        nh["resent"] = time.time()
        el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")


def schedule_night_heat(sunny):
    """毎日の設定のあと：今夜沸かすなら、量と開始時刻を決める（「夜は止める」の方式・できるだけ遅く がオンのとき）"""
    target = night_heat_target(sunny)
    if target is None:
        return None
    eco = state.get("eco") or {}
    now = datetime.now()
    remain = eco.get("remain") or 0
    # 夜までに使う分を引いておく（夕方〜夜のお風呂など）
    w0, w1 = night_window(now)
    if now < w0:
        p = predict_hot(now.date(), now.hour, min(24, w0.hour if w0.date() == now.date() else 24))
        if p:
            remain = max(0, remain - p["liters"])
    pl = plan_night_heat(max(0, target - remain), now)
    with lock:
        state["night_heat"] = {"target": int(target), "start": pl["start"], "plan": pl, "planned_at": time.time(),
                               "until": (w1 + timedelta(hours=1)).timestamp(), "started": False, "sunny": bool(sunny)}
    return pl


def hot_plan_public():
    now = datetime.now()
    nd = now.date() if now.hour < 12 else now.date() + timedelta(days=1)
    rate, how = heat_rate()
    ev = evening_need(now.date() if now.hour < int(conf.get("night_start_hour", 23)) else now.date() + timedelta(days=1))
    ng = night_need(nd)
    tank = (state.get("eco") or {}).get("tank")
    if tank and ng.get("need"):
        ng["pct"] = night_target_pct(nd)
    lo = learn_loss()
    tomorrow = date.today() + timedelta(days=1)
    lr = learn_usage_temp(hot_day_type(tomorrow))
    f, tinfo = temp_factor(tomorrow)
    learn = {"loss_k": lo["k"], "loss_n": lo["n"], "loss_how": lo["how"],
             "loss_day_l": round(lo["k"] * max(0.0, float(conf.get("tank_temp") or 65) - (forecast_mean(tomorrow) or 10)) * 24),
             "usage_per_deg": round(-lr["b"], 1) if lr else None, "usage_days": lr["n"] if lr else 0,
             "tomorrow_t": forecast_mean(tomorrow), "tomorrow_factor": f, "enabled": bool(conf.get("hot_temp_learn", True))}
    try:
        dt_, dh_ = day_store_target(now)
        day_store = {"target": dt_, "how": dh_, "on": bool(conf.get("hot_store_tomorrow", True))}
    except Exception:
        day_store = None
    return {"evening": ev, "night": ng, "rate_lph": round(rate), "rate_how": how, "prep": state.get("prep"), "night_heat": state.get("night_heat"), "learn": learn,
            "day_store": day_store,
            "extra": [x for x in conf.get("hot_extra") or [] if x.get("date", "") >= date.today().isoformat()]}


def prep_tick(eco, now):
    """夕方の準備：prep_hour 時に「今夜の沸き上げまでに使う予想＋余裕」が残っているようにする。足りなければ、間に合う時刻から沸かす。
    夕方の時刻を過ぎても、今夜の沸き上げが始まるまでは見張り、残りが「そこからの予想＋余裕」を下回ったら沸かし足す。
    この沸き上げは、買電が多いときの一時停止より優先する（お湯切れを防ぐため）"""
    if not conf.get("hot_auto") or not eco or eco.get("remain") is None:
        return
    ph = int(conf.get("prep_hour", 17))
    remain = eco["remain"]
    st = state.get("prep") or {}
    if st.get("date") != now.date().isoformat():
        st = {"date": now.date().isoformat(), "heating": False}
    d0 = datetime.combine(now.date(), datetime.min.time())
    end_h = evening_end_hour(now.date())
    end_at = d0 + timedelta(hours=end_h)
    in_evening = now >= d0 + timedelta(hours=ph)
    if in_evening and now >= end_at:
        st["heating"] = False
        state["prep"] = st
        return
    need = evening_need(now.date(), now.hour if in_evening else None)
    st["need"] = need.get("need")
    st["until"] = end_at.strftime("%H:%M") if end_h < 24 else f"翌{end_h - 24}:00"
    if not need.get("need"):
        state["prep"] = st
        return
    tank = eco.get("tank") or need["need"]
    target = min(int(need["need"]), int(tank * 0.98))
    st["target"] = target
    rate, _ = heat_rate()
    short = target - remain
    st["short"] = max(0, short)
    if st.get("heating"):
        # 沸かしている：目標まで沸いたら（または今夜の沸き上げの時刻になったら）やめる
        if remain < target and not eco.get("heating") and time.time() - st.get("resent", 0) > 600:
            # ほかの設定（「夜は止める」を自動に戻す・買電の一時停止など）で止まったときは、もう一度沸かす
            st["resent"] = time.time()
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
        if remain >= target or now >= end_at:
            st["heating"] = False
            st["stopped_at"] = time.time()
            (eco_pause if state.get("night_stop") else eco_auto)(
                f"夕方の準備：{remain}L になりました（目標 {target}L）" if remain >= target else "今夜の沸き上げの時刻になったため")
            state["ensure"] = False
        state["prep"] = st
        return
    # 少しの不足では沸かさない（細かく入れたり切ったりしないように）。止めてから30分は沸かし直さない
    if short < 20 or time.time() - st.get("stopped_at", 0) < 1800:
        state["prep"] = st
        return
    if in_evening:
        start_at = now  # 夕方を過ぎて足りなくなった：すぐ沸かし足す
        why = f"今夜の沸き上げ（{st['until']}）までに使う予想 {need['predicted'] or 0}L ＋ 余裕 {need['margin']}L"
    else:
        # 間に合うように始める時刻：足りない量 ÷ 沸かす速さ ＋ 15分の余裕
        lead_h = short / rate + 0.25
        start_at = d0 + timedelta(hours=ph - lead_h)
        why = f"夕方〜今夜の沸き上げ（{st['until']}）までに使う予想 {need['predicted'] or 0}L ＋ 余裕 {need['margin']}L"
    st["start_at"] = start_at.strftime("%H:%M")
    if now >= start_at and not eco.get("heating") and eco.get("can_manual", True):
        if guard["active"]:
            guard_release("夕方のお湯が足りないため（お湯切れを防ぐ）", cool=1800)
        s_ = eco_caps["set"]
        if s_ is None or 0xC0 in s_:
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xC0, b"\x41")
        err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
        if not err:
            st["heating"] = True
            with lock:
                state["by_us"] = True
                state["ensure"] = True
            log(f"夕方の準備：{why}" + (f" ＋ 多めの日 {need['extra']}L" if need["extra"] else "")
                + f" に対して、いま {remain}L なので沸かし始めました（目標 {target}L）")
        else:
            log(f"夕方の準備：沸かし始められませんでした（{err}）")
    state["prep"] = st


# ---- 買電が多いときはお湯を沸かさない ----
guard = {"active": False, "since": 0, "over": 0, "under": 0, "mode": None, "buy_before": None, "eco_w": None,
         "cool_until": 0, "last_pause": 0, "reason": ""}


def guard_level_kw():
    v = float(conf.get("heat_guard_kw") or 0)
    if v > 0:
        return v, "設定"
    d = demand_state.get("last") or {}
    if d.get("target") == "buy" and d.get("warn_kw"):
        return float(d["warn_kw"]), "30分の電力の注意の値"
    return 3.0, "目安"


def guard_tick(eco, surplus, step_s):
    """買電が多いあいだは沸き上げを止める（沸き上げ自動設定 B0=0x43）。下がったら、止める前の設定（自動／手動沸き増し）に戻す。
    お湯が少ないとき（20%未満）や、止めて1時間たったときは止めない（湯切れを防ぐ）"""
    if not conf.get("heat_guard") or not eco or not eco.get("can_manual", True) or surplus is None:
        if guard["active"]:
            guard_release("見張りをやめたため")
        return
    if (state.get("prep") or {}).get("heating"):
        # 夕方の準備（お湯が足りない）で沸かしているあいだは止めない
        if guard["active"]:
            guard_release("夕方のお湯が足りないため（お湯切れを防ぐ）", cool=1800)
        return
    buy_w = max(0.0, -surplus)
    level_kw, how = guard_level_kw()
    d = demand_state.get("last") or {}
    demand_hot = d.get("target") == "buy" and d.get("warn_kw") and (d.get("projected_kw") or 0) > d["warn_kw"]
    over = buy_w >= level_kw * 1000 or demand_hot
    now = time.time()
    pct = remain_pct(eco)
    if not guard["active"]:
        guard["over"] = guard["over"] + step_s if over and eco.get("heating") else 0
        if guard["over"] >= 60 and now >= guard["cool_until"] and (pct is None or pct >= 20):
            mode = b"\x42" if eco.get("manual") else b"\x41"
            if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43"):
                guard.update(active=True, since=now, under=0, mode=mode, buy_before=buy_w, eco_w=None, last_pause=now,
                             reason=(f"30分の電力が {d.get('projected_kw')}kW になりそう" if demand_hot and buy_w < level_kw * 1000
                                     else f"買電 {buy_w / 1000:.1f}kW"))
                log(f"買電が多いので沸き上げを一時停止しました（{guard['reason']}・目安 {level_kw:.1f}kW：{how}）")
        return
    # 止めている：止めた直後の買電の下がり方から、エコキュートの電力を覚える（戻したらまた上がるので、その分を見込む）
    if guard["eco_w"] is None and now - guard["since"] >= 120:
        guard["eco_w"] = max(500.0, min(4000.0, (guard["buy_before"] or 0) - buy_w)) if guard["buy_before"] else 1500.0
    eco_w = guard["eco_w"] or 1500.0
    # ほかの処理で沸き上げが戻されたら、止め直す
    if eco.get("heating") and now - guard["last_pause"] > 120:
        guard["last_pause"] = now
        el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
    resume_ok = buy_w + eco_w < level_kw * 1000 * 0.9 and not demand_hot
    guard["under"] = guard["under"] + step_s if resume_ok else 0
    if guard["under"] >= 300:
        guard_release(f"買電が {buy_w / 1000:.1f}kW に下がったため")
    elif now - guard["since"] > 3600:
        guard_release("1時間止めたため（お湯切れを防ぐため、しばらく止めません）", cool=1800)
    elif pct is not None and pct < 15:
        guard_release(f"残りのお湯が少ない（{pct}%）ため", cool=1800)


def guard_release(reason, cool=0):
    mode = guard["mode"] or b"\x41"
    # 止めているあいだに「「夜は止める」」（目標まで沸いた・晴れの夜）になったときは、止めたままにする
    if mode == b"\x41" and state.get("night_stop"):
        mode = b"\x43"
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, mode)
    log(f"沸き上げの一時停止をやめました（{reason}）" if not err else f"沸き上げを元に戻せませんでした（{err}）")
    guard.update(active=False, over=0, under=0, cool_until=time.time() + cool)


def guard_public():
    lv, how = guard_level_kw()
    return {"enabled": bool(conf.get("heat_guard")), "active": guard["active"], "since": guard["since"] if guard["active"] else None,
            "reason": guard["reason"] if guard["active"] else "", "level_kw": round(lv, 2), "level_how": how,
            "eco_w": round(guard["eco_w"]) if guard["eco_w"] else None}


def remain_pct(eco):
    r, t = (eco or {}).get("remain"), (eco or {}).get("tank")
    return r * 100 // t if r is not None and t else None


def night_start_h():
    """夜の沸き上げ（安い夜の時間）の始まり。0 時は 24 として扱う"""
    v = int(conf.get("night_start_hour", 23) or 23)
    return 24 if v in (0, 24) else v


def before_night(now=None):
    """夕方〜夜の沸き上げの時刻の前か（昼の時間帯のあと〜night_start_hour の前）"""
    now = now or datetime.now()
    return conf["start_hour"] <= now.hour < night_start_h()


def resume_at_night(eco_ok=True):
    """止めている沸き上げを、すぐ自動に戻さず、夜の沸き上げの時刻に自動へ戻す（夕方に高い電気で沸かさないため）"""
    with lock:
        state["night_resume"] = True


def stop_plan(sunny, name, eco, result):
    """このサーバーが夜の沸き上げを手動で止める（沸き上げ自動設定 B0=0x43「手動沸き上げ停止」）。
    翌日が晴れで、残りのお湯が十分なときだけ止める。止めたら、昼に余った電気で沸かす。夕方は自動に戻さず止めたままにし、
    次の毎日の設定で「今夜は沸かす」と決めたら、夜の沸き上げの時刻（night_start_hour）に自動へ戻す。"""
    s = eco_caps["set"]
    if s is not None and 0xB0 not in s:
        return result(True, "⚠ このエコキュートは、沸き上げの自動・停止を外から変えられない機種です（夜の沸き上げを止められません）", sunny)
    pct = remain_pct(eco)
    ns = night_start_h() % 24
    if not sunny:
        state["night_pending"] = False
        if conf.get("night_late") and (state.get("night_stop") or before_night()):
            # 夜はできるだけ遅く：冷める量と料金から開始時刻を決め、それまで止めておく
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
            state["night_stop"] = True
            state["night_resume"] = False
            pl = schedule_night_heat(False)
            if pl:
                return result(True, f"{name}は晴れない予報：今夜は {datetime.fromtimestamp(pl['start']).strftime('%H:%M')} から約{pl['liters']}L 沸かします"
                              + (f"（朝に近いほど冷める分が少なく、{ns}時から沸かすより約{pl['saving_yen']}円安い見込み）" if pl.get("saving_yen") else "（朝に近い時刻にして、冷める分を減らします）"), False)
        if state.get("night_stop"):
            if before_night():
                # 夕方はまだ止めたまま。夜の沸き上げの時刻に自動へ戻す
                resume_at_night()
                return result(True, f"{name}は晴れない予報：夜はいつもどおり沸かします（{ns}時に沸き上げを自動に戻します。それまでは止めたまま）", False)
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
            state["night_stop"] = False
        return result(True, f"{name}は晴れない予報：夜はいつもどおり沸かします（沸き上げ：自動）", False)
    target_pct = night_target_pct(plan_target())
    tank = (eco or {}).get("tank")
    lit = lambda q: f"{q}%（約{tank * q // 100}L）" if tank else f"{q}%"
    if pct is not None and pct < target_pct:
        # まだ少ないので、夜は目標の量まで沸かしてから止める（夕方に止めていれば、夜の沸き上げの時刻まで止めたまま）
        if conf.get("night_late"):
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
            with lock:
                state["night_stop"] = True
                state["night_resume"] = False
                state["night_pending"] = False
                state["plan_method"] = "stop"
            pl = schedule_night_heat(True)
            if pl:
                return result(True, f"{name}は晴れの予報：今夜は {datetime.fromtimestamp(pl['start']).strftime('%H:%M')} から {lit(target_pct)} まで沸かし（いま {lit(pct)}）、"
                                    f"昼の{conf['start_hour']}時から余った電気で沸かします" + (f"（{ns}時から沸かすより約{pl['saving_yen']}円安い見込み）" if pl.get("saving_yen") else ""), True)
        if state.get("night_stop") and before_night():
            resume_at_night()
            with lock:
                state["night_pending"] = True
                state["plan_method"] = "stop"
        else:
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
            with lock:
                state["night_pending"] = True
                state["night_stop"] = False
                state["plan_method"] = "stop"
        return result(True, f"{name}は晴れの予報：夜（{ns}時から）は {lit(target_pct)} まで沸かしたら止めます（いま {lit(pct)}）。"
                            f"昼の{conf['start_hour']}時から余った電気で沸かします（夕方は自動に戻さず、止めたまま）", True)
    state["night_heat"] = None
    err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
    if err:
        return result(False, f"夜の沸き上げを止められませんでした（{EPC_NAME[0xB0]}：{err}）（10分後にもう一度試します）", True)
    with lock:
        state["night_stop"] = True
        state["plan_method"] = "stop"
    return result(True, f"{name}は晴れの予報：夜の沸き上げを止めました（残り {pct if pct is not None else '?'}%）。"
                        f"昼の{conf['start_hour']}時から余った電気で沸かします（夕方も自動に戻さず止めたまま。毎日の設定で次の夜を決めます）"
                        f"（夜中に残りが{conf.get('safety_pct', 30)}%より減ったら、止めるのをやめて沸かします）", True)


def eco_pause(reason):
    """「夜は止める」のときの沸き増しの終わり：自動に戻さず、止めたままにする（昼の高い電気で沸かさないため）"""
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


def store_ahead_cost(now=None):
    """翌日分のお湯を、いま（昼の余った電気）で沸かすのと、今夜（安い時間）に買った電気で沸かすのとで、どちらが安いか。
    昼：売れたはずの電気（売電の単価）＋ 夜まで余分に冷める分。夜：その時刻の単価 ÷ その時刻の外気温での効率（COP）。
    戻り値：{"day": 100L あたりの円, "night": 同, "cheaper": 昼のほうが安いか, "how": 説明}"""
    now = now or datetime.now()
    tank = float((state.get("eco") or {}).get("tank") or 460)
    t_tank = float(conf.get("tank_temp") or 65)
    w0, w1 = night_window(now if now.hour >= 12 else now + timedelta(hours=12))
    t_in = SUPPLY_TEMP[(w1.month - 1) % 12]
    q = 100 * 4.186 * max(10.0, t_tank - t_in) / 3600                      # 100L を沸かす熱（kWh）
    temps = outdoor_temps()
    t_day = temp_at(now, temps)
    night_at = w1 - timedelta(hours=2)                                       # 夜はできるだけ遅く（朝の前）に沸かす
    t_night = temp_at(night_at, temps)
    # 夜まで余分に置いておくあいだに冷める分（タンクの冷め方を、増やした量の割合で）
    k = float(conf.get("tank_loss_w_per_k") or 1.0) / 1000
    hold_h = max(0.0, (night_at - now).total_seconds() / 3600)
    t_avg = (t_day if t_day is not None else 10) - 3
    loss_q = k * max(0.0, t_tank - t_avg) * hold_h * (100 / max(100.0, tank))
    sell = float(conf.get("tariff_sell") or 0)
    rate = rate_at(night_at)
    day = (q + loss_q) / cop_at(t_day if t_day is not None else 15) * sell
    if rate is None:
        return {"day": round(day), "night": None, "cheaper": True,
                "how": "電気料金のプランが無いので比べられません（ふつうは売電より夜の電気が高いので、昼に沸かします）"}
    night = q / cop_at(t_night if t_night is not None else 5) * rate
    cheaper = day < night
    how = (f"100L あたり 昼 約{day:.0f}円（売電 {sell:g}円/kWh・冷める分 込み）／夜 約{night:.0f}円（{night_at:%H}時 {rate:.1f}円/kWh）"
           + ("なので昼が安い" if cheaper else "なので夜が安い"))
    if sell <= 0:
        how += "。売電の単価が入っていないので 0円 として比べています（⚙ 電気料金 で入れられます）"
    return {"day": round(day), "night": round(night), "cheaper": cheaper, "how": how}


def day_store_target(now=None):
    """昼に余った電気で沸かす目標（L）：今夜の沸き上げまでに使う予想＋余裕 ＋ 翌日1日分の予想 ＋ 冷めて減る分。
    翌日分を先に沸かしておけば、夜の沸き上げ（買う電気）がその分減る。予想が無ければ満タン近く（95%）。
    戻り値：(目標 L, 説明)"""
    now = now or datetime.now()
    tank = (state.get("eco") or {}).get("tank") or 0
    full = int(tank * 0.95) if tank else None
    if not conf.get("hot_store_tomorrow", True) or not tank:
        return full, "満タン近くまで"
    ph = int(conf.get("prep_hour", 17))
    ev = evening_need(now.date(), now.hour if now.hour >= ph else None)
    tom = now.date() + timedelta(days=1)
    p = predict_hot(tom, 0, 24)
    if not ev.get("need") or p is None:
        return full, "満タン近くまで（使う量の記録がまだ少ないため）"
    # 電気代で決める：翌日分を昼に沸かすほうが、夜に沸かすより安いときだけ
    try:
        ec = store_ahead_cost(now)
    except Exception as e:
        print("昼と夜の比べでエラー:", e, flush=True)
        ec = {"cheaper": True, "how": ""}
    if not ec["cheaper"]:
        return min(full, int(ev["need"])), f"今夜の分 {ev['need']}L（翌日分は夜に沸かすほうが安い：{ec['how']}）"
    r = learn_draw_ratio()["r"]
    ex = sum(hot_extra_for(tom, part)[0] for part in ("morning", "evening"))
    tom_l = round((max(p["liters"], p.get("p75") or 0) + ex) * r)
    loss = 0
    if conf.get("hot_temp_learn", True):
        # いまから翌日の夕方まで冷める分の半分（使うにつれて減るため）
        loss = round(loss_liters(now, datetime.combine(tom, datetime.min.time()) + timedelta(hours=ph)) * 0.5)
    t = int(ev["need"] + tom_l + loss)
    return min(full, t), f"今夜の分 {ev['need']}L ＋ 翌日分 {tom_l}L" + (f" ＋ 冷める分 {loss}L" if loss else "") + (f"（{ec['how']}）" if ec.get("how") else "")


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


def away_tick(eco):
    """留守の間は沸き上げを止める（沸き上げ自動設定 0xB0 = 手動沸き上げ停止）。True のあいだは、ほかの沸き上げの制御をしない"""
    until = float(conf.get("away_until") or 0)
    if not until or not conf.get("eco_ip"):
        return False
    now = time.time()
    reheat = until - float(conf.get("away_reheat_hours") or 0) * 3600
    if now >= until:
        with lock:
            conf["away_until"] = 0
            save_json(CONF_PATH, conf)
        if state.get("away") == "stop":
            el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
        state.pop("away", None)
        log("留守が終わりました（ふだんの沸き上げに戻ります）")
        return False
    if now >= reheat:
        if state.get("away") != "reheat":
            err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
            if not err:
                state["away"] = "reheat"
                log("留守：帰る前なので、沸き上げを自動に戻しました（お湯を沸かします）")
                # 残りが少なければ、すぐに沸き増しする
                pct = remain_pct(eco)
                if pct is not None and pct < 60 and (eco or {}).get("can_manual", True):
                    el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42")
        state["status"] = f"留守：{datetime.fromtimestamp(until).strftime('%m/%d %H:%M')} に帰る予定。お湯を沸かしています"
        return True
    # 止めておく（エコキュートのリモコンで戻されても、10分ごとに止め直す）
    if state.get("away") != "stop" or now - state.get("away_checked", 0) > 600:
        state["away_checked"] = now
        if True:
            err = el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x43")
            if not err and state.get("away") != "stop":
                state["away"] = "stop"
                log(f"留守：{datetime.fromtimestamp(until).strftime('%m/%d %H:%M')} まで沸き上げを止めました")
            elif err:
                log(f"留守：沸き上げを止められませんでした（{err}）")
    state["status"] = (f"留守：{datetime.fromtimestamp(until).strftime('%m/%d %H:%M')} まで沸き上げを止めています"
                       f"（{datetime.fromtimestamp(reheat).strftime('%m/%d %H:%M')} から沸かします）")
    state["night_stop"] = False
    state["night_pending"] = False
    return True


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
    # 買電が多いときはお湯を沸かさない（だれが沸かしていても止める）
    if conf["eco_ip"]:
        try:
            h_ = now.hour
            guard_tick(eco, surplus, 15 if conf["enabled"] and conf["start_hour"] <= h_ < conf["end_hour"] else 60)
        except Exception as e:
            print("買電の見張りでエラー:", e, flush=True)
    try:
        selfsuff_tick(surplus)
    except Exception as e:
        print("自給率の見張りでエラー:", e, flush=True)
    # 留守：決めた時刻まで沸き上げを止めておき、帰る前に沸かし始める（おひさま沸き上げがオフでも働く）
    if away_tick(eco):
        return
    if not conf["enabled"]:
        state["status"] = "おひさま沸き上げはオフです"
        return
    if not conf["eco_ip"]:
        state["status"] = "エコキュートの IP アドレスが設定されていません"
        return

    # 「夜は止める」：目標の量まで沸いたら止める
    pct0 = remain_pct(eco)
    if state.get("night_pending") and not in_window and pct0 is not None and pct0 >= night_target_pct():
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

    # 「夜は止める」：夜中にお湯が減りすぎたら、止めるのをやめる（安い夜の電気で沸かす）
    pct = remain_pct(eco)
    if state.get("night_stop") and conf.get("safety_hour", 4) <= now.hour < conf["start_hour"] and pct is not None and pct < conf.get("safety_pct", 30):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41"):
            state["night_stop"] = False
            log(f"残りのお湯が少ない（{pct}%）ため、夜の沸き上げを止めるのをやめました（自動に戻しました）")
    # 夜の沸き上げ（できるだけ遅く）
    try:
        night_heat_tick(eco, now)
    except Exception as e:
        print("夜の沸き上げでエラー:", e, flush=True)
    # 「夜は止める」：昼の時間帯が終わっても自動には戻さない（夕方に高い電気で沸かさないため）。
    # 毎日の設定で「今夜は沸かす」と決めたときや、毎日の設定ができていないときは、夜の沸き上げの時刻に自動へ戻す
    at_night = now.hour >= night_start_h() or now.hour < conf["start_hour"]
    tonight = (now.date() if now.hour < 12 else now.date() + timedelta(days=1)).isoformat()  # 今夜の毎日の設定の対象（翌日）
    plan_ok = (state.get("plan") or {}).get("date") == tonight and (state.get("plan") or {}).get("ok")
    if state.get("night_stop") and at_night and not state.get("night_heat") and (state.get("night_resume") or not plan_ok):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41"):
            state["night_stop"] = False
            state["night_resume"] = False
            state["by_us"] = False
            log("夜の沸き上げの時刻になったので、沸き上げを自動に戻しました" + ("" if plan_ok else "（毎日の設定ができていないため）"))
    elif state.get("night_resume") and not state.get("night_stop"):
        state["night_resume"] = False
    # 「夜は止める」：昼の時間帯の終わる2時間前に、まだお湯が少なければ、売電が無くても沸かす（お湯切れを防ぐ保険）
    if not conf.get("hot_auto") and state.get("night_stop") and in_window and now.hour >= conf["end_hour"] - 2 and not state["by_us"] \
            and pct is not None and pct < conf.get("ensure_pct", 60) and not (eco or {}).get("heating"):
        if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42"):
            state["by_us"] = True
            state["ensure"] = True
            log(f"残りのお湯が少ない（{pct}%）ため、売電が無くても沸き増しを始めました（お湯切れを防ぐため）")

    # 買電が多くて一時停止しているあいだは、沸かし始めない（夕方の準備・昼の沸き増しも待つ）
    if guard["active"]:
        state["status"] = f"買電が多いので沸き上げを一時停止しています（{guard['reason']}）"
        return

    # 夕方の準備（過去の使用量から）
    try:
        prep_tick(eco, now)
    except Exception as e:
        print("夕方の準備でエラー:", e, flush=True)
    prep_on = bool((state.get("prep") or {}).get("heating"))

    # 2. 昼：売電をリアルタイムに見て沸き増し
    if not in_window:
        counters["over"] = counters["buy"] = 0
        if prep_on:
            p_ = state["prep"]
            state["status"] = f"夕方の準備：{conf.get('prep_hour', 17)}時までに {p_.get('need')}L になるよう沸かしています"
            return
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
    # 昼に沸かす目標：今夜の分＋翌日分（翌日分を先に沸かしておく）。目標に届いたら止める
    try:
        day_t, day_how = day_store_target(now)
    except Exception as e:
        print("昼の沸き上げの目標でエラー:", e, flush=True)
        day_t, day_how = (int(eco["tank"] * 0.95) if eco and eco.get("tank") else None), "満タン近くまで"
    state["day_store"] = {"target": day_t, "how": day_how}
    full = bool(eco and eco.get("remain") is not None and day_t and eco["remain"] >= day_t)
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
            stop(f"今夜の分と翌日分（{day_t}L：{day_how}）まで沸いたため" if conf.get("hot_store_tomorrow", True) else "お湯がいっぱいになったため")
            state["ensure"] = False
        elif eco and not eco.get("heating") and surplus >= 0 and eco.get("can_manual", True) and time.time() - state.get("day_resent", 0) > 600:
            # エコキュートが自分の決めた量で沸き増しを終えたが、まだ目標に届かず電気も余っている：もう一度沸き増し
            state["day_resent"] = time.time()
            if not el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x42"):
                log(f"まだ電気が余っているので、沸き増しを続けます（いま {eco.get('remain')}L・目標 {day_t}L：{day_how}）")
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


GROUP_DOMAINS = ("switch", "light", "fan", "input_boolean", "climate", "media_player", "cover", "humidifier")


def clean_groups(v):
    if not isinstance(v, list) or len(v) > 30:
        return None
    out = []
    for g in v:
        if not isinstance(g, dict):
            return None
        ents = [str(e) for e in g.get("entities") or []]
        if len(ents) > 60 or not all(re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", e) and e.split(".")[0] in GROUP_DOMAINS for e in ents):
            return None
        act = g.get("action") if g.get("action") in ("on", "off", "toggle") else "off"
        out.append({"id": str(g.get("id") or uuid.uuid4().hex[:8])[:16], "name": str(g.get("name") or "まとめて")[:30],
                    "icon": str(g.get("icon") or "")[:4], "action": act, "entities": ents})
    return out


def run_group(g, action):
    """ボタン1つで、いくつもの機器をまとめてつける・消す（同時に送る）"""
    if action not in ("on", "off"):
        action = "off"
    res = {}

    def one(eid):
        try:
            dom = eid.split(".")[0]
            if is_el_entity(eid):
                el_call(eid, "close_cover" if dom == "cover" and action == "off" else "open_cover" if dom == "cover" else "turn_" + action)
            elif dom == "cover":
                ha_call("cover", "open_cover" if action == "on" else "close_cover", {"entity_id": eid})
            else:
                ha_call("homeassistant", "turn_" + action, {"entity_id": eid})
            res[eid] = "ok"
        except Exception as e:
            res[eid] = str(e)[:80]
    th = [threading.Thread(target=one, args=(e,), daemon=True) for e in g.get("entities") or []]
    for t in th:
        t.start()
    for t in th:
        t.join(15)
    ng = [e for e in g.get("entities") or [] if res.get(e) != "ok"]
    log(f"まとめて操作「{g.get('name')}」：{len(res) - len(ng)}台を{'つけ' if action == 'on' else '消し'}ました" + (f"（できなかった：{len(ng)}台）" if ng else ""))
    return {"ok": not ng, "done": len(res) - len(ng), "failed": ng, "results": res}


def server_setup_info():
    """サーバーのセットアップの版と、自動で実行し直した最後の結果（画面に出す）"""
    out = {}
    try:
        with open("/etc/homepanel/setup_version", encoding="utf-8") as f:
            out["version"] = int(f.read().strip() or 0)
    except Exception:
        out["version"] = None
    try:
        p_ = "/var/lib/homepanel-setup/last.log"
        out["auto_at"] = os.path.getmtime(p_)
        with open(p_, encoding="utf-8", errors="replace") as f:
            tail = f.read()[-2000:].strip().splitlines()
        out["auto_ok"] = bool(tail) and tail[-1].strip() == "終了コード 0"
        out["auto_last"] = tail[-1][:200] if tail else ""
    except Exception:
        pass
    return out


def hot_plan_safe():
    try:
        return hot_plan_public()
    except Exception as e:
        return {"error": str(e)}


def circuits_now_safe():
    try:
        return circuits_now()
    except Exception:
        return []


def live_state():
    """いまの発電・消費・売買電とエコキュートの様子（画面のリアルタイム表示用）。古ければ AiSEG2 から読み直す"""
    p = state.get("power") or {}
    if conf.get("aiseg_url") and time.time() - state.get("power_at", 0) > 8:
        try:
            if aiseg_surplus_w() is not None:
                state["power_at"] = time.time()
                p = state.get("power") or {}
        except Exception:
            pass
    eco = state.get("eco") or {}
    today = (energy.get("days", {}).get(date.today().isoformat()) or {})
    return {"at": state.get("power_at") or time.time(), "generation_w": p.get("generation_w"), "consumption_w": p.get("consumption_w"),
            "surplus_w": (p.get("generation_w") or 0) - (p.get("consumption_w") or 0) if p.get("consumption_w") is not None else state.get("surplus_w"),
            "eco": {k: eco.get(k) for k in ("online", "heating", "manual", "remain", "tank")},
            "eco_today_kwh": (today.get("circuits_kwh") or {}).get("エコキュート"),
            "away_until": conf.get("away_until") or 0, "away": state.get("away"),
            "demand": demand_state.get("last"), "demand_alert": state.get("demand_alert"), "heat_guard": guard_public(),
            "circuits": circuits_now_safe(), "selfsuff": selfsuff_public(), "auto_now": auto_now()}


def public_state():
    with lock:
        c = {k: v for k, v in conf.items() if k not in SECRET_CONF}
        c["aiseg_password_set"] = bool(conf.get("aiseg_password"))
        c["ha_token_set"] = bool(conf.get("ha_token"))
        hot = [p for p in energy.get("hot", []) if p[0] > time.time() - 86400]
        return {"night_pending": bool(state.get("night_pending")), "daily": dict(sorted(energy.get("days", {}).items())[-10:]),
                "hot_history": hot, "hot_hourly": state.get("hot_hourly"), "cost": cost_summary_safe(), "forecast": state.get("forecast"),
                "away_until": conf.get("away_until") or 0, "away": state.get("away"), "demand": demand_state.get("last"),
                "hot_plan": hot_plan_safe(), "heat_guard": guard_public(), "selfsuff": selfsuff_public(), "auto_now": auto_now(),
                "el_rescue": {"on": bool(conf.get("el_rescue", True)), "targets": el_rescue_targets(), "sent": el_rescue_state["sent"],
                              "every_min": float(conf.get("el_rescue_min") or 3),
                              "at": el_rescue_state["last"] or None, "error": el_rescue_state["error"]},
                "server_setup": server_setup_info(),
                "aiseg_daily": {"ok": aiseg_daily["ok"], "pages": aiseg_daily["pages"]},
                "night_stop": bool(state.get("night_stop")), "power": state.get("power"), "surplus_from": state.get("surplus_from"), "aiseg_error": state.get("aiseg_error"), "config": c, "plan": state.get("plan"), "shift_unsupported": bool(state.get("shift_unsupported")), "log": state["log"][:40], "status": state["status"],
                "surplus_w": state.get("surplus_w") if state["history"] and time.time() - state["history"][-1][0] < 120 else None,
                "history": state["history"], "eco": state["eco"], "by_us": state["by_us"], "server_time": time.time()}



# ================= 画面からの操作の確認（Home Assistant にログインしている人だけ） =================
# 家の中のネットワークの、ほかの機器（来客のスマートフォン・家電など）から勝手に操作されないように、
# 画面（アプリ・iPad）が送ってくる Home Assistant のアクセストークンを、Home Assistant に問い合わせて確かめる。
HA_BASE_ENV = os.environ.get("HOMEPANEL_HA_URL", "").rstrip("/")
_auth_cache = {}


def ha_base_url():
    return HA_BASE_ENV or str(conf.get("ha_url") or "").rstrip("/")


# 認証の結果を覚えておく時間（秒）。一般の操作は2分、管理の操作は1分（Home Assistant でトークンを消したら、すぐ使えなくするため）
AUTH_TTL_USER = 120
AUTH_TTL_ADMIN = 60
_admin_cache = {}


def _ha_get_status(path, tok):
    """Home Assistant に path を GET して、HTTP の状態コードを返す（読めなければ 0）。本文は読まない"""
    try:
        req = urllib.request.Request(ha_base_url() + path, headers={"Authorization": "Bearer " + tok})
        with urllib.request.urlopen(req, timeout=6) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def _cache_put(cache, k):
    if len(cache) >= 100:
        # 古いものから消す
        for old in sorted(cache, key=cache.get)[:50]:
            cache.pop(old, None)
    cache[k] = time.time()


def check_ha_token(tok, admin=False):
    """Home Assistant のアクセストークンが使えるか（admin=True なら、Home Assistant の管理者のものか）を確かめる。
    使えるかは /api/ が 200 を返すか、管理者かは管理者だけが読める /api/error_log が 200 を返すかで決める"""
    import hashlib
    if not tok or len(tok) > 4096 or not ha_base_url():
        return False
    k = hashlib.sha256(tok.encode()).hexdigest()
    t = _auth_cache.get(k)
    if not (t and time.time() - t < AUTH_TTL_USER):
        if _ha_get_status("/api/", tok) != 200:
            _auth_cache.pop(k, None)
            _admin_cache.pop(k, None)
            return False
        _cache_put(_auth_cache, k)
    if not admin:
        return True
    t = _admin_cache.get(k)
    if t and time.time() - t < AUTH_TTL_ADMIN:
        return True
    if _ha_get_status("/api/error_log", tok) == 200:
        _cache_put(_admin_cache, k)
        return True
    return False


# このサーバーの中での作業（コンソールでの確認など）用のトークン。root と このサービスだけが読める（0600）
LOCAL_TOKEN_PATH = os.path.join(DIR, "local_token")


def local_token():
    try:
        with open(LOCAL_TOKEN_PATH, encoding="utf-8") as f:
            v = f.read().strip()
        if len(v) >= 32:
            return v
    except Exception:
        pass
    import secrets
    v = secrets.token_urlsafe(32)
    os.makedirs(DIR, exist_ok=True)
    fd = os.open(LOCAL_TOKEN_PATH + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(v)
    os.replace(LOCAL_TOKEN_PATH + ".tmp", LOCAL_TOKEN_PATH)
    return v


def bearer(handler):
    """Authorization: Bearer のトークン。URL のクエリのトークンは受け付けない（履歴・記録に残るため）"""
    h = handler.headers.get("Authorization") or ""
    return h[7:].strip() if h.lower().startswith("bearer ") else ""


def request_authorized(handler, admin=False):
    """全部の窓口で認証が要る（転送のヘッダーの有無では決めない）。
    使えるのは、Home Assistant のアクセストークン（admin=True のときは管理者のもの）か、このサーバーの中だけで読めるローカルのトークン"""
    import hmac
    tok = bearer(handler)
    if not tok:
        return False
    if hmac.compare_digest(tok.encode(), local_token().encode()):
        return True
    return check_ha_token(tok, admin)


class BodyError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code
        self.msg = msg


# 変えるのに Home Assistant の管理者の権限が要る設定（接続先・パスワード・鍵・操作する機器のアドレス）
ADMIN_KEYS = {"eco_ip", "eco_eoj", "meter_ip", "meter_eoj", "aiseg_url", "aiseg_user", "aiseg_password", "ha_url", "ha_token", "power_devices"}
# 管理者だけが使える窓口（AiSEG2・機器の調べ物）
ADMIN_PATHS = {"/aiseg-test", "/aiseg-circuits", "/aiseg-probe", "/aiseg-flow-probe", "/aiseg-raw", "/discover", "/diagnose"}


def needs_admin(p, b):
    if p in ADMIN_PATHS:
        return True
    if p == "/config":
        # 同じ値をそのまま送ってくる（アプリが設定をまとめて送る）だけなら、一般の人でもよい
        for k in ADMIN_KEYS & set(b):
            if k in ("aiseg_password", "ha_token"):
                if b[k]:
                    return True
            elif b[k] != conf.get(k):
                return True
    return False


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

    # 1回のリクエストで受け付ける大きさ（設定の JSON は数十KB まで）と、読み取りの待ち時間
    MAX_BODY = 256 * 1024
    timeout = 20

    def _body(self):
        """POST の本文（JSON のオブジェクト）。大きすぎる・形が違うときは BodyError"""
        raw = self.headers.get("Content-Length")
        try:
            n = int(raw or 0)
        except ValueError:
            raise BodyError(400, "Content-Length が正しくありません")
        if n < 0:
            raise BodyError(400, "Content-Length が正しくありません")
        if n > self.MAX_BODY:
            raise BodyError(413, "送られてきたデータが大きすぎます")
        if not n:
            return {}
        try:
            b = json.loads(self.rfile.read(n))
        except Exception:
            raise BodyError(400, "JSON を読めません")
        if not isinstance(b, dict):
            raise BodyError(400, "JSON のオブジェクトを送ってください")
        return b

    def _path(self):
        return urllib.parse.urlparse(self.path).path.replace("/solar-api", "", 1).rstrip("/") or "/"

    def do_GET(self):
        if not request_authorized(self):
            return self._ok({"error": "Home Assistant にログインしているアプリ・画面からだけ使えます"}, 401)
        p = self._path()
        if p in ("/", "/status"):
            return self._ok(public_state())
        if p == "/climate":
            return self._ok(climate_public())
        if p == "/timers":
            return self._ok(timers_public())
        if p == "/auth-check":
            # Caddy の forward_auth から：Android のアプリの配布（/android/）を、Home Assistant にログインしたアプリにだけ渡す
            return self._ok({"ok": True})
        if p == "/live":
            return self._ok(live_state())
        if p == "/demand":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            return self._ok(demand_report((q.get("date") or [date.today().isoformat()])[0]))
        if p == "/notify-services":
            return self._ok({"services": ha_notify_services()})
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
        if not request_authorized(self):
            return self._ok({"error": "Home Assistant にログインしているアプリ・画面からだけ使えます"}, 401)
        p = self._path()
        try:
            b = self._body()
        except BodyError as e:
            return self._ok({"error": e.msg}, e.code)
        if needs_admin(p, b) and not request_authorized(self, admin=True):
            return self._ok({"error": "この設定・操作は Home Assistant の管理者だけができます"}, 403)
        if p == "/config":
            # Home Assistant のアドレスは画面からは変えない（変えられると、鍵（トークン）をほかの場所に送らされてしまうため）
            allowed = set(DEFAULT_CONF) - ({"ha_url"} if HA_BASE_ENV else set())
            with lock:
                # AiSEG2 のアドレスを変えるときは、パスワードも入れ直してもらう（前のパスワードをほかの機器に送らないため）
                if "aiseg_url" in b and str(b["aiseg_url"]).strip() != str(conf.get("aiseg_url") or "").strip() and "aiseg_password" not in b:
                    conf["aiseg_password"] = ""
                if "ha_url" in b and "ha_url" in allowed and str(b["ha_url"]).rstrip("/") != str(conf.get("ha_url") or "").rstrip("/") and "ha_token" not in b:
                    conf["ha_token"] = ""
                for r in b.get("climate_rules") or [] if isinstance(b.get("climate_rules"), list) else []:
                    if not str(r.get("entity", "")).startswith("climate.") or (r.get("source") and not str(r["source"]).startswith("sensor.")):
                        return self._ok({"error": "温度調整の設定が正しくありません"}, 400)
                if "groups" in b:
                    g = clean_groups(b["groups"])
                    if g is None:
                        return self._ok({"error": "まとめて操作の設定が正しくありません"}, 400)
                    b["groups"] = g
                if "hot_extra" in b:
                    ex = b["hot_extra"]
                    if not isinstance(ex, list) or len(ex) > 60 or not all(isinstance(x, dict) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(x.get("date", ""))) for x in ex):
                        return self._ok({"error": "多めの日の設定が正しくありません"}, 400)
                    b["hot_extra"] = [{"date": x["date"], "liters": max(0, min(500, int(x.get("liters") or 0))),
                                       "part": "morning" if x.get("part") == "morning" else "evening", "note": str(x.get("note") or "")[:30]}
                                      for x in ex if x["date"] >= (date.today() - timedelta(days=1)).isoformat()]
                if "hot_margin_l" in b:
                    b["hot_margin_l"] = max(0, min(500, int(float(b["hot_margin_l"] or 0))))
                if "start_hour" in b:
                    b["start_hour"] = max(6, min(14, int(b["start_hour"])))
                if "el_devices" in b:
                    ed = b["el_devices"]
                    if not isinstance(ed, list) or len(ed) > 100 or not all(isinstance(x, dict) and is_el_entity(x.get("id")) for x in ed):
                        return self._ok({"error": "ECHONET Lite の機器の一覧が正しくありません"}, 400)
                    b["el_devices"] = [{"id": x["id"], "name": str(x.get("name") or "")[:40], "room": str(x.get("room") or "")[:20]} for x in ed]
                for k_ in ("el_rescue_ips", "el_known_ips"):
                    if k_ not in b:
                        continue
                    v = b[k_]
                    if not isinstance(v, list) or len(v) > 20 or not all(re.fullmatch(r"(192\.168|10\.\d{1,3})\.\d{1,3}\.\d{1,3}", str(x)) for x in v):
                        return self._ok({"error": "IP アドレスは家の中のもの（192.168.〇.〇 など）を入れてください"}, 400)
                if "ss_aircon_entities" in b:
                    v = b["ss_aircon_entities"]
                    if not isinstance(v, list) or len(v) > 20 or not all(re.fullmatch(r"climate\.[a-z0-9_]+", str(x)) for x in v):
                        return self._ok({"error": "エアコンの選び方が正しくありません（climate.〇〇）"}, 400)
                if "ss_loads_items" in b:
                    v = b["ss_loads_items"]
                    if not isinstance(v, list) or len(v) > 10 or not all(isinstance(x, dict) and re.fullmatch(r"(switch|input_boolean)\.[a-z0-9_]+", str(x.get("entity", ""))) for x in v):
                        return self._ok({"error": "余剰で動かす機器は、スイッチ（switch.〇〇 / input_boolean.〇〇）を選んでください"}, 400)
                    b["ss_loads_items"] = [{"entity": x["entity"], "name": str(x.get("name") or "")[:30],
                                            "on_kw": max(0.3, min(10.0, float(x.get("on_kw") or 1.5))),
                                            "off_kw": max(0.1, min(10.0, float(x.get("off_kw") or 0.3)))} for x in v]
                for k_, lo, hi in (("ss_target_pct", 0, 100), ("ss_notify_kw", 0.3, 10), ("ss_notify_min", 5, 60), ("ss_aircon_kw", 0.3, 10),
                                   ("ss_aircon_offset", 0.5, 3), ("ss_loads_min", 5, 60), ("ss_notify_from", 0, 23), ("ss_notify_to", 1, 24),
                                   ("ss_aircon_from", 0, 23), ("ss_aircon_to", 1, 24), ("ss_loads_from", 0, 23), ("ss_loads_to", 1, 24)):
                    if k_ in b:
                        try:
                            b[k_] = max(lo, min(hi, float(b[k_])))
                        except (TypeError, ValueError):
                            return self._ok({"error": f"{k_} の値が正しくありません"}, 400)
                if "demand_notify" in b:
                    if not isinstance(b["demand_notify"], list) or not all(re.fullmatch(r"notify\.[a-z0-9_]+", str(x)) for x in b["demand_notify"]):
                        return self._ok({"error": "通知先が正しくありません（notify.〇〇）"}, 400)
                for k, v in b.items():
                    if k in allowed:
                        conf[k] = v
                save_json(CONF_PATH, conf)
            eco_caps["set"] = None
            log("設定を変えました：" + "、".join(k for k in b if k in allowed))
            wake.set()
            res = public_state()
            ignored = [k for k in b if k not in allowed]
            if ignored:
                # 知らない項目は保存しない。画面が「保存できた」と思い込まないように、返す
                res["ignored"] = ignored
                print("保存しなかった設定の項目:", ignored, flush=True)
            return self._ok(res)
        if p == "/timers":
            # 追加：{entity, name, action, data, at（時刻・秒）または after_min}
            if not conf.get("ha_token"):
                return self._ok({"error": "サーバーに Home Assistant の鍵が登録されていません（アプリの「エアコンの自動温度調整」で登録してください）"})
            eid = str(b.get("entity") or "")
            if eid.split(".")[0] not in ("switch", "light", "fan", "input_boolean", "climate", "media_player") or not re.fullmatch(r"[a-z_]+\.[a-z0-9_]+", eid) \
                    or b.get("action") not in ("turn_on", "turn_off", "toggle", "set_hvac_mode"):
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
        if p == "/away":
            # {until: 時刻（秒）, reheat_hours} / {cancel: true}
            with lock:
                if b.get("cancel"):
                    conf["away_until"] = 0
                else:
                    u = float(b.get("until") or 0)
                    if not (time.time() < u < time.time() + 31 * 86400):
                        return self._ok({"error": "帰る日時が正しくありません（1か月以内）"})
                    conf["away_until"] = u
                    if b.get("reheat_hours") is not None:
                        conf["away_reheat_hours"] = max(0.0, min(24.0, float(b["reheat_hours"])))
                save_json(CONF_PATH, conf)
            if b.get("cancel"):
                state.pop("away", None)
                if conf.get("eco_ip"):
                    el.setc(conf["eco_ip"], conf["eco_eoj"], 0xB0, b"\x41")
                log("留守を取り消しました（沸き上げを自動に戻しました）")
            else:
                state.pop("away", None)
                log(f"留守を設定しました：{datetime.fromtimestamp(conf['away_until']).strftime('%m/%d %H:%M')} に帰る予定")
            wake.set()
            return self._ok(public_state())
        if p == "/groups/run":
            # {id, action（省略時はボタンの設定どおり：on / off）}
            g = next((x for x in conf.get("groups") or [] if x.get("id") == b.get("id")), None)
            if not g:
                return self._ok({"error": "ボタンが見つかりません"})
            if not conf.get("ha_token"):
                return self._ok({"error": "サーバーに Home Assistant の鍵が登録されていません"})
            return self._ok(run_group(g, b.get("action") or g.get("action") or "off"))
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
        if p == "/aiseg-probe":
            # 「回路ごとの使用電力量（日）」のページの読み方ごとに、読めた回路の名前を返す（200V の回路を探すため）
            rx = re.compile(r'<div class="a_label">(.*?)</div>\s*<div class="a_value">([0-9.,０-９．]*)\s*kWh</div>', re.S)
            res = {}
            for name, make in aiseg_page_patterns(date.today()):
                pages = []
                for i in range(1, 6):
                    path = make(i)
                    if path is None:
                        continue
                    try:
                        h = aiseg_open(path)
                        pages.append({"page": i, "circuits": [re.sub(r"<[^>]+>", "", a).strip() for a, _ in rx.findall(h)], "size": len(h)})
                    except Exception as e:
                        pages.append({"page": i, "error": str(e)[:120]})
                        break
                res[name] = pages
            return self._ok({"ok": True, "patterns": res, "inst": state.get("circuits_inst"), "now": circuits_now_safe(),
                             "daily": (energy.get("days", {}).get(date.today().isoformat()) or {}).get("circuits_kwh")})
        if p == "/aiseg-flow-probe":
            # 「いまの電力」の画面（電気の流れ）が、200V の回路の値をどこから取っているかを調べる
            out = {"pages": [], "scripts": {}, "endpoints": []}
            seen_js = set()
            for i in range(1, 6):
                try:
                    h = aiseg_open(f"/page/electricflow/1113?id={i}")
                except Exception as e:
                    out["pages"].append({"page": i, "error": str(e)[:120]})
                    break
                blocks = []
                for m_ in re.finditer(r'<div class="c_value">(.*?)</div>\s*<div class="c_device">(.*?)</div>', h, re.S):
                    a, b = m_.start(), m_.end()
                    blocks.append({"value": re.sub(r"\s+", " ", m_.group(1))[:80], "device": re.sub(r"<[^>]+>|\s+", " ", m_.group(2)).strip()[:60],
                                   "html": re.sub(r"\s+", " ", h[max(0, a - 300):b + 200])[:700]})
                srcs = re.findall(r'<script[^>]+src="([^"]+)"', h)
                inline = [re.sub(r"\s+", " ", x)[:1500] for x in re.findall(r"<script[^>]*>(.*?)</script>", h, re.S) if x.strip()]
                out["pages"].append({"page": i, "size": len(h), "circuits": blocks, "script_src": srcs, "inline": inline[:4],
                                     "ids": sorted(set(re.findall(r'id="([^"]*(?:value|watt|power|circuit|stage)[^"]*)"', h)))[:60]})
                for src in srcs:
                    if src in seen_js or len(seen_js) > 12:
                        continue
                    seen_js.add(src)
                    try:
                        js = aiseg_open(src if src.startswith("/") else "/" + src.lstrip("./"))
                        urls = sorted(set(re.findall(r"""["'](/(?:data|page|action)/[^"'\s]+)["']""", js)))
                        out["scripts"][src] = {"size": len(js), "urls": urls[:40],
                                               "hints": [re.sub(r"\s+", " ", js[max(0, m_.start() - 150):m_.end() + 250])[:500]
                                                         for m_ in re.finditer(r"electricflow|c_value|\.ajax|XMLHttpRequest|\$\.post|fetch\(", js)][:6]}
                        out["endpoints"] += urls
                    except Exception as e:
                        out["scripts"][src] = {"error": str(e)[:120]}
                if i > 1 and out["pages"][-1].get("circuits") == out["pages"][-2].get("circuits"):
                    break
            out["endpoints"] = sorted(set(out["endpoints"]))
            return self._ok(out)
        if p == "/aiseg-raw":
            # AiSEG2 のページ・データを、そのまま返す（調べるため。AiSEG2 の /page/・/data/ だけ）
            path = str(b.get("path") or "")
            if not re.match(r"^/(page|data)/[A-Za-z0-9_/?=&.,%-]+$", path):
                return self._ok({"error": "AiSEG2 の /page/ か /data/ のアドレスを指定してください"}, 400)
            try:
                return self._ok({"text": aiseg_open(path, post=bool(b.get("post")))[:200000]})
            except Exception as e:
                return self._ok({"error": str(e)})
        if p == "/discover":
            return self._ok({"devices": el.discover()})
        self._ok({"error": "not found"}, 404)


def main():
    global el
    os.makedirs(DIR, exist_ok=True)
    local_token()  # このサーバーの中での作業用のトークンを、先に作っておく（homepanel-api が読む）
    el = Echonet()
    threading.Thread(target=loop, daemon=True).start()
    threading.Thread(target=energy_loop, daemon=True).start()
    threading.Thread(target=weather_loop, daemon=True).start()
    threading.Thread(target=climate_loop, daemon=True).start()
    threading.Thread(target=device_timer_loop, daemon=True).start()
    threading.Thread(target=ha_ws_loop, daemon=True).start()
    threading.Thread(target=el_rescue_loop, daemon=True).start()
    print(f"おひさま沸き上げのサービスを開始（画面用 127.0.0.1:{API_PORT}）", flush=True)
    ThreadingHTTPServer(("127.0.0.1", API_PORT), Api).serve_forever()


if __name__ == "__main__":
    main()
