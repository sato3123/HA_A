"""ホームパネルのサーバー（homepanel-solar.py / homepanel-camera.py）の単体テスト。
実機（AiSEG2・エコキュート・Home Assistant）には何も送らない：通信する関数はテストの中で差し替える。
  python3 -m unittest discover -s server/tests -v
"""
import io
import json
import os
import tempfile
import time
import unittest
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.dirname(HERE)


def load(name, env):
    """サービスのファイルを、サーバーを起動せずに読み込む（最後の main の呼び出しは除く）"""
    for k, v in env.items():
        os.environ[k] = v
    src = open(os.path.join(SERVER, name), encoding="utf-8").read()
    idx = src.rfind("\nif __name__")
    g = {"__file__": os.path.join(SERVER, name), "__name__": "hp_test"}
    exec(compile(src[:idx], name, "exec"), g)
    return g


class FakeHandler:
    def __init__(self, headers=None, path="/status"):
        self.headers = headers or {}
        self.path = path


class FakeEl:
    def __init__(self):
        self.sent = []

    def setc(self, ip, eoj, epc, edt):
        self.sent.append((ip, eoj, epc, edt))
        return None

    def get(self, *a, **k):
        return {}


class SolarTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.m = load("homepanel-solar.py", {"SOLAR_DIR": cls.dir, "HOMEPANEL_HA_URL": "http://ha.test:8123"})

    def setUp(self):
        self.m["_auth_cache"].clear()
        self.m["_admin_cache"].clear()
        # Home Assistant の代わり：good は管理者、user は一般の人
        calls = self.calls = []

        def fake_status(path, tok):
            calls.append((path, tok))
            if path == "/api/":
                return 200 if tok in ("good", "user") else 401
            if path == "/api/error_log":
                return 200 if tok == "good" else 401
            return 404
        self.m["_ha_get_status"] = fake_status


class TestAuth(SolarTestBase):
    def test_no_token_is_rejected_even_without_forwarded_header(self):
        self.assertFalse(self.m["request_authorized"](FakeHandler({})))

    def test_forwarded_header_does_not_matter(self):
        self.assertFalse(self.m["request_authorized"](FakeHandler({"X-Forwarded-For": "127.0.0.1"})))

    def test_query_token_is_not_accepted(self):
        self.assertFalse(self.m["request_authorized"](FakeHandler({}, "/status?token=good")))

    def test_bad_token(self):
        self.assertFalse(self.m["request_authorized"](FakeHandler({"Authorization": "Bearer bad"})))

    def test_user_and_admin(self):
        u = FakeHandler({"Authorization": "Bearer user"})
        a = FakeHandler({"Authorization": "Bearer good"})
        self.assertTrue(self.m["request_authorized"](u))
        self.assertFalse(self.m["request_authorized"](u, admin=True))
        self.assertTrue(self.m["request_authorized"](a, admin=True))

    def test_local_token(self):
        tok = self.m["local_token"]()
        self.assertGreaterEqual(len(tok), 32)
        self.assertEqual(os.stat(self.m["LOCAL_TOKEN_PATH"]).st_mode & 0o777, 0o600)
        self.assertTrue(self.m["request_authorized"](FakeHandler({"Authorization": "Bearer " + tok}), admin=True))
        self.assertEqual(tok, self.m["local_token"](), "ローカルのトークンは毎回同じ")

    def test_cache_expires(self):
        h = FakeHandler({"Authorization": "Bearer user"})
        self.assertTrue(self.m["request_authorized"](h))
        n = len(self.calls)
        self.assertTrue(self.m["request_authorized"](h))
        self.assertEqual(len(self.calls), n, "キャッシュの間は Home Assistant に聞き直さない")
        for k in list(self.m["_auth_cache"]):
            self.m["_auth_cache"][k] -= self.m["AUTH_TTL_USER"] + 1
        self.assertTrue(self.m["request_authorized"](h))
        self.assertGreater(len(self.calls), n, "期限が過ぎたら聞き直す")

    def test_revoked_token_drops_from_cache(self):
        h = FakeHandler({"Authorization": "Bearer user"})
        self.assertTrue(self.m["request_authorized"](h))
        for k in list(self.m["_auth_cache"]):
            self.m["_auth_cache"][k] -= self.m["AUTH_TTL_USER"] + 1
        self.m["_ha_get_status"] = lambda path, tok: 401  # Home Assistant でトークンを消した
        self.assertFalse(self.m["request_authorized"](h))

    def test_needs_admin(self):
        na = self.m["needs_admin"]
        conf = self.m["conf"]
        self.assertTrue(na("/aiseg-raw", {}))
        self.assertTrue(na("/discover", {}))
        self.assertFalse(na("/heat", {}))
        self.assertTrue(na("/config", {"aiseg_password": "x"}))
        self.assertFalse(na("/config", {"aiseg_password": ""}))
        self.assertFalse(na("/config", {"eco_ip": conf.get("eco_ip")}), "同じ値を送るだけなら一般の人でもよい")
        self.assertTrue(na("/config", {"eco_ip": "192.168.1.250"}))
        self.assertFalse(na("/config", {"hot_margin_l": 80, "groups": []}))


class TestBody(SolarTestBase):
    def handler(self, body, length=None):
        h = self.m["Api"].__new__(self.m["Api"])
        h.headers = {"Content-Length": str(len(body) if length is None else length)}
        h.rfile = io.BytesIO(body)
        return h

    def test_ok(self):
        self.assertEqual(self.handler(b'{"a":1}')._body(), {"a": 1})

    def test_too_big(self):
        with self.assertRaises(self.m["BodyError"]) as c:
            self.handler(b"x", length=10 ** 7)._body()
        self.assertEqual(c.exception.code, 413)

    def test_negative(self):
        with self.assertRaises(self.m["BodyError"]) as c:
            self.handler(b"", length=-5)._body()
        self.assertEqual(c.exception.code, 400)

    def test_not_object(self):
        with self.assertRaises(self.m["BodyError"]) as c:
            self.handler(b"[1,2]")._body()
        self.assertEqual(c.exception.code, 400)

    def test_bad_json(self):
        with self.assertRaises(self.m["BodyError"]):
            self.handler(b"{x")._body()


class TestSaveJson(SolarTestBase):
    def test_atomic_and_private(self):
        p = os.path.join(self.dir, "t.json")
        self.m["save_json"](p, {"a": 1})
        with open(p) as f:
            self.assertEqual(json.load(f), {"a": 1})
        self.assertEqual(os.stat(p).st_mode & 0o777, 0o600)
        self.assertFalse(os.path.exists(p + ".tmp"))


class TestEchonet(SolarTestBase):
    def setUp(self):
        super().setUp()
        self.el = FakeEl()
        self.m["el"] = self.el

    def test_rejects_outside_addresses(self):
        self.assertFalse(self.m["is_el_entity"]("climate.el_8_8_8_8_013001"))
        self.assertTrue(self.m["is_el_entity"]("climate.el_192_168_1_50_013001"))
        with self.assertRaises(ValueError):
            self.m["el_call"]("climate.el_8_8_8_8_013001", "turn_on")
        self.assertEqual(self.el.sent, [])

    def test_climate_mode_temp_fan(self):
        self.m["el_call"]("climate.el_192_168_1_50_013001", "set_temperature", {"hvac_mode": "heat", "temperature": 25, "fan_mode": "3"})
        self.assertEqual([(e, d) for _, _, e, d in self.el.sent], [(0x80, b"\x30"), (0xB0, b"\x43"), (0xB3, bytes([25])), (0xA0, b"\x33")])

    def test_fan_only_ignores_temperature(self):
        self.m["el_call"]("climate.el_192_168_1_50_013001", "set_temperature", {"hvac_mode": "fan_only", "temperature": 25})
        self.assertNotIn(0xB3, [e for _, _, e, _ in self.el.sent])

    def test_unknown_service(self):
        with self.assertRaises(ValueError):
            self.m["el_call"]("switch.el_192_168_1_50_05fd01", "explode")


class TestGroups(SolarTestBase):
    def test_clean_groups(self):
        cg = self.m["clean_groups"]
        self.assertIsNone(cg([{"entities": ["shell_command.x"]}]))
        g = cg([{"name": "おやすみ", "entities": ["light.a", "switch.b"], "action": "nope"}])
        self.assertEqual(g[0]["action"], "off")


class TestHotWater(SolarTestBase):
    def setUp(self):
        super().setUp()
        self.days = self.m["energy"].setdefault("days", {})
        self.days.clear()

    def test_hot_clean_removes_zero_and_extreme(self):
        items = [(100, 300, True)] * 6 + [(0, 0, True), (900, 2000, True), (10, 30, True)]
        ok, removed = self.m["hot_clean"](items)
        self.assertEqual(len(ok), 6)
        self.assertEqual(removed, 3)

    def test_learn_loss_from_records(self):
        temps = {}
        for i in range(1, 15):
            d = date.today() - timedelta(days=i)
            th = [10.0] * 24
            rh, r = [], 400.0
            for h in range(24):
                rh.append(round(r, 1))
                r -= 0.02 * (65 - th[h])
            self.days[d.isoformat()] = {"remain_h": rh, "heat_h": [False] * 24, "hot_h": [0.0] * 24, "t_h": th, "t_mean": 10.0}
        lo = self.m["learn_loss"]()
        self.assertAlmostEqual(lo["k"], 0.02, delta=0.003)

    def test_usage_temperature_regression(self):
        for i in range(1, 61):
            d = date.today() - timedelta(days=i)
            t = 2 + (i * 5 % 13)
            tot = 400 - 8 * t
            hh = [0.0] * 24
            hh[20] = tot
            self.days[d.isoformat()] = {"hot_h": hh, "hot_use_l": tot, "t_mean": float(t)}
        typ = self.m["hot_day_type"](date.today())
        lr = self.m["learn_usage_temp"](typ)
        self.assertIsNotNone(lr)
        self.assertAlmostEqual(lr["b"], -8, delta=0.5)


class TestNightPlan(SolarTestBase):
    def test_flat_temperature_prefers_late(self):
        m = self.m
        m["conf"].update(tariff_plan="kepco_hapie_r", tariff_contract=10)
        base = datetime.combine(date.today(), datetime.min.time())
        m["_temps_cache"].update(at=time.time() + 10 ** 6, data={(base + timedelta(hours=h)).strftime("%Y-%m-%dT%H"): 10.0 for h in range(48)})
        pl = m["plan_night_heat"](300, base + timedelta(hours=20))
        start = datetime.fromtimestamp(pl["start"])
        self.assertGreaterEqual(start.hour, 2, "気温が同じなら、朝に近い時刻を選ぶ")
        self.assertLessEqual(pl["yen"], pl["early_yen"] + m["conf"]["night_late_tol_yen"] + 0.01)


class TestGuard(SolarTestBase):
    def test_pause_and_resume(self):
        m = self.m
        el = FakeEl()
        m["el"] = el
        m["conf"].update(eco_ip="192.168.1.20", heat_guard=True, heat_guard_kw=3)
        g = m["guard"]
        g.update(active=False, over=0, under=0, cool_until=0)
        now = [1000.0]
        real = m["time"].time
        m["time"].time = lambda: now[0]
        try:
            eco = {"heating": True, "manual": False, "can_manual": True, "remain": 300, "tank": 460}
            for _ in range(5):
                m["guard_tick"](eco, -4000, 15)
                now[0] += 15
            self.assertTrue(g["active"])
            self.assertEqual(el.sent[-1][2:], (0xB0, b"\x43"))
            eco["heating"] = False
            # 止めた直後に 2.6kW に下がった → エコキュートは約 1.4kW と覚える
            for _ in range(10):
                m["guard_tick"](eco, -2600, 15)
                now[0] += 15
            self.assertTrue(g["active"], "まだ戻すと目安を超えるので止めたまま")
            # 買電が 0.8kW に下がって5分たったら戻す（0.8 + 1.4 < 3 × 0.9）
            for _ in range(30):
                m["guard_tick"](eco, -800, 15)
                now[0] += 15
            self.assertFalse(g["active"])
            self.assertEqual(el.sent[-1][2:], (0xB0, b"\x41"))
        finally:
            m["time"].time = real


class TestHourlyFill(SolarTestBase):
    def test_200v_fills_and_100v_not_double(self):
        m = self.m
        m["energy"]["hours"].clear()
        m["energy5"].clear()
        d = date(2026, 11, 7)
        # 起動したとき：その時点の不足分は数えない
        m["aiseg_daily"].update(date=None, offset={})
        self.assertEqual(m["hourly_fill_tick"](d, {"エアコン200V": 3.0, "照明": 1.0}, datetime(2026, 11, 7, 10, 0)), {})
        # 照明はいまの電力で 200Wh 記録済み。エアコンは記録なし
        h = m["energy"]["hours"].setdefault("2026-11-07T10", {"use": 0.0, "gen": 0.0, "buy": 0.0, "sell": 0.0, "circuits": {}})
        h["circuits"]["照明"] = 200.0
        added = m["hourly_fill_tick"](d, {"エアコン200V": 3.5, "照明": 1.1}, datetime(2026, 11, 7, 10, 30))
        self.assertAlmostEqual(added["エアコン200V"], 500.0)
        self.assertNotIn("照明", added)
        self.assertAlmostEqual(h["circuits"]["エアコン200V"], 500.0)
        # 次の時間：増えた分だけ、その時間に入る
        added = m["hourly_fill_tick"](d, {"エアコン200V": 4.2, "照明": 1.1}, datetime(2026, 11, 7, 11, 5))
        self.assertAlmostEqual(added["エアコン200V"], 700.0)
        self.assertAlmostEqual(m["energy"]["hours"]["2026-11-07T11"]["circuits"]["エアコン200V"], 700.0)
        # 日が変わったとき：0 から数える
        added = m["hourly_fill_tick"](date(2026, 11, 8), {"エアコン200V": 0.3}, datetime(2026, 11, 8, 0, 10))
        self.assertAlmostEqual(added["エアコン200V"], 300.0)


class TestHotTrack(SolarTestBase):
    def test_jitter_not_counted_and_balance_cap(self):
        m = self.m
        m["energy"].setdefault("days", {}).pop("2026-11-05", None)
        m["hot_last"].update(remain=None, ref=None)
        m["state"]["hot_from_aiseg"] = False
        t0 = datetime(2026, 11, 5, 1, 0)
        seq = [200, 205, 200, 206, 199, 204, 200]  # 表示のゆれだけ
        for i, r in enumerate(seq):
            m["hot_water_track"]({"remain": r, "tank": 370}, t0 + timedelta(minutes=i))
        day = m["energy"]["days"]["2026-11-05"]
        self.assertEqual(day.get("hot_heat_l", 0), 0)
        self.assertEqual(day.get("hot_use_l", 0), 0)
        # 本当の沸き上げ：200 → 350
        for i, r in enumerate(range(210, 351, 10)):
            m["hot_water_track"]({"remain": r, "tank": 370}, t0 + timedelta(minutes=10 + i))
        self.assertAlmostEqual(day["hot_heat_l"], 150, delta=10)
        # AiSEG2 の使った量があるとき：ゆれで上がった分は収支の上限で切る
        m["state"]["hot_from_aiseg"] = True
        g = m["loss_liters"]
        m["loss_liters"] = lambda a, b: 0.0
        try:
            for i, r in enumerate([340, 352, 341, 353, 340, 354] * 5):
                m["hot_water_track"]({"remain": r, "tank": 370}, t0 + timedelta(minutes=40 + i))
            self.assertLessEqual(day["hot_heat_l"], 200 + 20)
        finally:
            m["loss_liters"] = g
            m["state"]["hot_from_aiseg"] = False


class TestSelfSuff(SolarTestBase):
    def setUp(self):
        super().setUp()
        m = self.m
        self.saved = {k: m["conf"].get(k) for k in m["DEFAULT_CONF"] if k.startswith("ss_")}
        self.saved["ha_token"] = m["conf"].get("ha_token")
        self.saved["demand_notify"] = m["conf"].get("demand_notify")
        m["ss"].update(sell_since=None, buy_since=None, notified_at=0, aircon={}, loads={})
        self.calls_ha = []
        self.states = {"climate.living": {"state": "cool", "attributes": {"temperature": 27}},
                       "switch.ev": {"state": "off", "attributes": {}}}
        self.g = {k: m[k] for k in ("ha_call", "ha_state")}
        m["ha_call"] = lambda d, sv, data: self.calls_ha.append((d, sv, data))
        m["ha_state"] = lambda e: self.states.get(e)
        m["conf"]["ha_token"] = "x"

    def tearDown(self):
        m = self.m
        m.update(self.g)
        for k, v in self.saved.items():
            m["conf"][k] = v

    def run_for(self, w, start, minutes):
        for i in range(minutes + 1):
            self.m["selfsuff_tick"](w, start + timedelta(minutes=i))
        return start + timedelta(minutes=minutes)

    def test_all_off_by_default(self):
        m = self.m
        for k in ("ss_notify", "ss_aircon", "ss_loads", "ss_partial", "ss_show"):
            self.assertFalse(m["DEFAULT_CONF"][k])
        self.run_for(3000, datetime(2026, 11, 7, 11, 0), 30)
        self.assertEqual(self.calls_ha, [])

    def test_notify_aircon_loads(self):
        m = self.m
        m["conf"].update(ss_notify=True, demand_notify=["notify.mobile_app_x"], ss_aircon=True, ss_aircon_entities=["climate.living"],
                         ss_loads=True, ss_loads_items=[{"entity": "switch.ev", "name": "EV", "on_kw": 1.5, "off_kw": 0.3}])
        t = self.run_for(2000, datetime(2026, 11, 7, 11, 0), 12)
        kinds = [(d, sv) for d, sv, _ in self.calls_ha]
        self.assertIn(("notify", "mobile_app_x"), kinds)
        self.assertIn(("climate", "set_temperature"), kinds)
        self.assertIn(("switch", "turn_on"), kinds)
        self.assertEqual([c for c in self.calls_ha if c[1] == "set_temperature"][0][2]["temperature"], 26.0)
        # 2時間以内は2回目の通知をしない
        n = sum(1 for d, _, _ in self.calls_ha if d == "notify")
        self.states["climate.living"]["attributes"]["temperature"] = 26.0
        self.run_for(2000, t, 20)
        self.assertEqual(sum(1 for d, _, _ in self.calls_ha if d == "notify"), n)
        # 買電が続いたら戻す・切る
        self.calls_ha.clear()
        self.run_for(-800, t + timedelta(minutes=30), 12)
        self.assertIn(("climate", "set_temperature", {"entity_id": "climate.living", "temperature": 27.0}), self.calls_ha)
        self.assertIn(("switch", "turn_off", {"entity_id": "switch.ev"}), self.calls_ha)

    def test_aircon_left_alone_if_person_changed(self):
        m = self.m
        m["conf"].update(ss_aircon=True, ss_aircon_entities=["climate.living"])
        t = self.run_for(2000, datetime(2026, 11, 7, 11, 0), 11)
        self.states["climate.living"]["attributes"]["temperature"] = 24  # 人が変えた
        self.calls_ha.clear()
        self.run_for(-800, t + timedelta(minutes=1), 12)
        self.assertEqual([c for c in self.calls_ha if c[1] == "set_temperature"], [])

    def test_partial_night_target(self):
        m = self.m
        d = date(2026, 11, 8)
        m["conf"]["hot_auto"], keep = False, m["conf"].get("hot_auto")
        m["state"]["solar_partial"] = {"date": d.isoformat(), "frac": 0.5}
        try:
            base = int(m["conf"].get("night_heat_pct", 50))
            self.assertEqual(m["night_target_pct"](d), min(100, int(round(base + (100 - base) * 0.5))))
            self.assertEqual(m["night_target_pct"](date(2026, 11, 9)), base)
        finally:
            m["state"]["solar_partial"] = None
            m["conf"]["hot_auto"] = keep


class TestElRescue(SolarTestBase):
    def test_targets_frame_and_interval(self):
        m = self.m
        keep = {k: m["conf"].get(k) for k in ("el_devices", "el_rescue", "el_rescue_ips", "el_rescue_min")}
        try:
            m["conf"].update(el_rescue=True, el_rescue_min=3, el_rescue_ips=["8.8.8.8", "192.168.1.30"],
                             el_devices=[{"id": "climate.el_192_168_1_12_013001"}, {"id": "light.el_192_168_1_20_029001"},
                                         {"id": "climate.el_192_168_1_12_013001"}])
            self.assertEqual(m["el_rescue_targets"](), ["192.168.1.12", "192.168.1.30"])
            f = m["el_rescue_frame"](0x1234)
            self.assertEqual(f.hex(), "1081123405ff010ef0016301d500")
            got = []
            m["el_rescue_state"]["last"] = 0
            self.assertEqual(m["el_rescue_tick"](1000.0, lambda d, ip: got.append(ip)), ["192.168.1.12", "192.168.1.30"])
            self.assertEqual(m["el_rescue_tick"](1100.0, lambda d, ip: got.append(ip)), [])  # 3分たっていない
            self.assertEqual(len(m["el_rescue_tick"](1200.0, lambda d, ip: got.append(ip))), 2)
            m["conf"]["el_rescue"] = False
            self.assertEqual(m["el_rescue_tick"](5000.0, lambda d, ip: got.append(ip)), [])
        finally:
            m["conf"].update(keep)


class TestEveningPrep(SolarTestBase):
    def test_prep_targets_use_plus_margin_until_night_heat(self):
        m = self.m
        today = date.today()
        days = m["energy"].setdefault("days", {})
        saved_days = dict(days)
        keep_conf = {k: m["conf"].get(k) for k in ("hot_auto", "prep_hour", "night_start_hour", "hot_margin_l", "hot_extra", "eco_ip", "eco_eoj", "hot_temp_learn")}
        stubs = {k: m[k] for k in ("temp_factor", "loss_liters", "heat_rate", "el", "eco_auto", "eco_pause")}
        calls = []

        class FakeEl:
            def setc(self, ip, eoj, epc, edt):
                calls.append((epc, edt))
                return None
        try:
            days.clear()
            for i in range(1, 29):
                hh = [0.0] * 24
                for h in range(17, 23):
                    hh[h] = 30.0 + (i % 4) * 5  # 夕方〜夜に 180〜270L
                hh[1] = 10.0  # 深夜に少し
                days[(today - timedelta(days=i)).isoformat()] = {"hot_h": hh, "hot_use_l": sum(hh)}
            m["conf"].update(hot_auto=True, prep_hour=17, night_start_hour=23, hot_margin_l=100, hot_extra=[], eco_ip="192.168.1.9",
                             eco_eoj="026b01", hot_temp_learn=False)
            m["temp_factor"] = lambda d: (1.0, None)
            m["loss_liters"] = lambda a, b: 0.0
            m["heat_rate"] = lambda: (100.0, "test")
            m["el"] = FakeEl()
            m["eco_auto"] = lambda reason: calls.append(("auto", reason))
            m["eco_pause"] = lambda reason: calls.append(("pause", reason))
            m["state"]["night_heat"] = {"start": datetime.combine(today + timedelta(days=1), datetime.min.time()).replace(hour=3).timestamp()}
            m["state"]["prep"] = None
            m["state"]["night_stop"] = False
            m["guard"]["active"] = False
            # 今夜の沸き上げ（翌3時）まで：17〜24時の予想（上位25%寄り）＋ 0〜3時 ＋ 余裕100L
            need = m["evening_need"](today)
            self.assertEqual(need["to"], 27)
            self.assertGreaterEqual(need["predicted"], need["avg"])
            self.assertEqual(need["need"], need["predicted"] + 100)
            self.assertGreater(need["predicted"], 6 * 30 + 10 - 1)
            eco = {"remain": 150, "tank": 460, "heating": False, "can_manual": True}
            # 14時：まだ始めない（間に合う時刻を計算するだけ）
            m["prep_tick"](eco, datetime.combine(today, datetime.min.time()).replace(hour=14))
            self.assertFalse(m["state"]["prep"]["heating"])
            # 16時半：沸かし始める（買電の一時停止中でも）
            m["guard"]["active"] = True
            m["guard"]["mode"] = b"\x41"
            m["prep_tick"](eco, datetime.combine(today, datetime.min.time()).replace(hour=16, minute=30))
            self.assertTrue(m["state"]["prep"]["heating"])
            self.assertIn((0xB0, b"\x42"), calls)
            # 目標まで沸いたらやめる
            eco["remain"] = m["state"]["prep"]["target"]
            m["prep_tick"](eco, datetime.combine(today, datetime.min.time()).replace(hour=17, minute=5))
            self.assertFalse(m["state"]["prep"]["heating"])
            # 20時：たくさん使って、残りが「そこからの予想＋余裕」を下回った → 30分たっていれば沸かし足す
            m["state"]["prep"]["stopped_at"] = 0
            eco["remain"] = 120
            m["prep_tick"](eco, datetime.combine(today, datetime.min.time()).replace(hour=20))
            self.assertTrue(m["state"]["prep"]["heating"])
            rest = m["evening_need"](today, 20)
            self.assertLess(rest["need"], need["need"])
            # 昼に余った電気で沸かす目標：今夜の分＋翌日分（タンクの95%まで）
            m["state"]["eco"] = {"tank": 460}
            m["conf"]["hot_store_tomorrow"] = True
            t, how = m["day_store_target"](datetime.combine(today, datetime.min.time()).replace(hour=11))
            self.assertIn("翌日分", how)
            self.assertLessEqual(t, int(460 * 0.95))
            self.assertGreater(t, need["need"] - 1)
            m["conf"]["hot_store_tomorrow"] = False
            self.assertEqual(m["day_store_target"]()[0], int(460 * 0.95))
        finally:
            days.clear(); days.update(saved_days)
            m["conf"].update(keep_conf)
            m.update(stubs)
            m["state"]["night_heat"] = None
            m["state"]["prep"] = None
            m["state"]["eco"] = None
            m["conf"]["hot_store_tomorrow"] = True
            m["guard"]["active"] = False


class TestDrawRatio(SolarTestBase):
    def test_ratio_learned_from_remaining_drop(self):
        m = self.m
        days = m["energy"].setdefault("days", {})
        saved = dict(days)
        g = m["learn_loss"]
        try:
            days.clear()
            m["learn_loss"] = lambda: {"k": 0.0, "n": 0, "how": ""}
            self.assertEqual(m["learn_draw_ratio"]()["r"], 1.0)  # 記録なし
            for i in range(1, 8):
                rh, uh = [None] * 24, [0.0] * 24
                rem = 400.0
                for h in range(24):
                    rh[h] = rem
                    if 18 <= h <= 21:
                        uh[h] = 50.0
                        rem -= 30.0  # 使った 50L で、残りは 30L 減る
                days[(date.today() - timedelta(days=i)).isoformat()] = {"remain_h": rh, "heat_h": [False] * 24, "hot_h": uh, "t_h": [10.0] * 24}
            self.assertAlmostEqual(m["learn_draw_ratio"]()["r"], 0.6, places=2)
        finally:
            days.clear(); days.update(saved)
            m["learn_loss"] = g


class TestCamera(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.m = load("homepanel-camera.py", {"CAMERA_DIR": cls.dir, "HOMEPANEL_HA_URL": "http://ha.test:8123"})
        cls.m["_ha_get_status"] = lambda path, tok: 200 if tok == "good" else 401

    def test_no_bypass(self):
        self.assertFalse(self.m["request_authorized"](FakeHandler({})))
        self.assertFalse(self.m["request_authorized"](FakeHandler({}, "/frame?token=good")))
        self.assertTrue(self.m["request_authorized"](FakeHandler({"Authorization": "Bearer good"})))

    def test_cam_dir_cannot_escape(self):
        p = self.m["cam_dir"]("../../etc")
        self.assertTrue(os.path.abspath(p).startswith(os.path.abspath(self.dir)))


if __name__ == "__main__":
    unittest.main()
