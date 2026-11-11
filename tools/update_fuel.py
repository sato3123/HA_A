#!/usr/bin/env python3
"""燃料費調整単価を電力会社の公表資料から読み、server/tariffs.json に書き足す（GitHub Actions で毎日動かす）。

いま対応している会社：関西電力（kepco）。
  資料：https://kepco.jp/~/media/Files/KepcoJp/Customer/pdf/seido/YYYYMM.ashx（YYYYMM 月分の燃料費調整単価のお知らせ・PDF）
  使う値：「電気供給条件（低圧）」の 1kWh あたりの単価（はぴｅタイムR などの自由料金のプラン。国の補助を差し引いた後の値）
  tariffs.json の by_usage_month のキーは「使った月」。請求の「○月分」は前の月に使った分なので、1か月前のキーに入れる。

  python3 tools/update_fuel.py            # 読み取って tariffs.json を書きかえる
  python3 tools/update_fuel.py --dry-run  # 書きかえずに、読めた値だけ表示する
  python3 tools/update_fuel.py --debug    # 資料の文字の様子も表示する（読み方を直すとき用）
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from datetime import date

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARIFFS = os.path.join(ROOT, "server", "tariffs.json")
KEPCO_URL = "https://kepco.jp/~/media/Files/KepcoJp/Customer/pdf/seido/{ym}.ashx"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
HEADERS = {"User-Agent": UA, "Accept": "application/pdf,text/html;q=0.9,*/*;q=0.8", "Accept-Language": "ja,en;q=0.8",
           "Referer": "https://kepco.jp/ryokin/fuelcost/"}


def add_month(y, m, n):
    m += n
    while m > 12:
        y, m = y + 1, m - 12
    while m < 1:
        y, m = y - 1, m + 12
    return y, m


def fetch_pdf_text(url):
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
    except Exception as e:
        return None, f"読めません（{e}）"
    if not data.startswith(b"%PDF"):
        return None, "PDF ではありません（まだ公表されていない月など）"
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        f.write(data)
        path = f.name
    try:
        out = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, timeout=60).stdout
    finally:
        os.unlink(path)
    return out, None


def yen(txt):
    """「▲1円26銭」「-1.26」「0円86銭」→ 数字（円）"""
    t = txt.replace(" ", "").replace("　", "")
    neg = t.startswith(("▲", "△", "-", "−", "－"))
    t = t.lstrip("▲△-−－")
    m = re.fullmatch(r"(\d+)円(\d+)銭", t)
    if m:
        v = int(m.group(1)) + int(m.group(2)) / 100
    else:
        try:
            v = float(t.replace("円", ""))
        except ValueError:
            return None
    return round(-v if neg else v, 2)


AMOUNT = r"[▲△\-−－]?\s*\d+\s*円\s*\d+\s*銭"


def parse_kepco(text, bill_month):
    """「電気供給条件（低圧）」の表から、bill_month 月分の 1kWh あたりの単価を読む。
    行の形：「10月分  12円84銭  0円86銭  0円86銭  53,500」（1契約あたり〔最初の15kWh〕・1kWh あたり・…）"""
    flat = text.replace("　", " ")
    start = flat.find("電気供給条件")
    if start < 0:
        return None, "「電気供給条件」の表が見つかりません"
    # 次の表（電気特定小売供給約款・選択約款など）の手前まで
    rest = flat[start:]
    ends = [i for i in (rest.find("特定小売供給約款", 10), rest.find("選択約款", 10)) if i > 0]
    sect = rest[: min(ends)] if ends else rest[:4000]
    for line in sect.splitlines():
        if re.search(rf"(^|\D){bill_month}\s*月分", line):
            nums = re.findall(AMOUNT, line)
            if len(nums) >= 2:
                return yen(nums[1]), None
            if len(nums) == 1:
                return yen(nums[0]), None
    return None, f"{bill_month}月分の行が見つかりません"


def main():
    dry = "--dry-run" in sys.argv or "--debug" in sys.argv
    debug = "--debug" in sys.argv
    with open(TARIFFS, encoding="utf-8") as f:
        tj = json.load(f)
    prov = next((p for p in tj["providers"] if p.get("id") == "kepco"), None)
    if prov is None:
        print("関西電力が単価表にありません")
        return 1
    fuel = prov.setdefault("fuel", {"source": "関西電力「電気供給条件（低圧）」の燃料費調整単価（国の補助を含む）", "includes_subsidy": True, "by_usage_month": {}})
    by = fuel.setdefault("by_usage_month", {})
    today = date.today()
    changed = []
    # 先月分〜3か月先の分まで（公表は2か月ほど前）
    for n in range(-1, 4):
        y, m = add_month(today.year, today.month, n)
        text, err = fetch_pdf_text(KEPCO_URL.format(ym=f"{y}{m:02d}"))
        if debug and text:
            i = text.find("電気供給条件")
            print(f"---- {y}-{m:02d} ----\n" + text[max(0, i - 200): i + 1500])
        if err:
            print(f"{y}年{m}月分：{err}")
            continue
        v, err = parse_kepco(text, m)
        if err or v is None:
            print(f"{y}年{m}月分：読めませんでした（{err}）")
            continue
        if not -30 <= v <= 30:
            print(f"{y}年{m}月分：値がおかしいので使いません（{v}）")
            continue
        uy, um = add_month(y, m, -1)
        key = f"{uy}-{um:02d}"
        print(f"{y}年{m}月分（{key} に使った分）：{v} 円/kWh" + ("" if by.get(key) == v else f"（これまで {by.get(key)}）"))
        if by.get(key) != v:
            by[key] = v
            changed.append(key)
    if changed and not dry:
        fuel["by_usage_month"] = dict(sorted(by.items()))
        tj["updated"] = today.isoformat()
        with open(TARIFFS, "w", encoding="utf-8") as f:
            json.dump(tj, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("tariffs.json を書きかえました：" + "、".join(changed))
    elif not changed:
        print("変わった値はありません")
    return 0


if __name__ == "__main__":
    sys.exit(main())
