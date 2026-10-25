#!/bin/bash
# エミュレーターにアプリを入れて起動し、止まらないことと、画面のレイアウトを確認する
set -u
PKG=jp.home.panel
fail() { echo "::error::$1"; exit 1; }
shot() { adb exec-out screencap -p > "launch-$1.png" || true; }

adb install -r app/build/outputs/apk/debug/app-debug.apk || fail "インストールできませんでした"
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb logcat -c

check() {
  local label=$1
  sleep "$2"
  shot "$label"
  adb logcat -d > logcat.txt
  if grep -q "FATAL EXCEPTION" logcat.txt; then
    grep -A 30 "FATAL EXCEPTION" logcat.txt | head -40 | while IFS= read -r l; do echo "::error::$l"; done
    fail "アプリが止まりました（$label）"
  fi
  adb shell pidof $PKG >/dev/null || fail "アプリが動いていません（$label）"
  echo "::notice::$label：アプリは動いています"
}

prefs() {
  adb shell am force-stop $PKG
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ui_prefs.xml'"
}

# 1. 初回起動（ログイン画面）
adb shell am start -W -n $PKG/.MainActivity || fail "起動できませんでした"
check "first" 12

# 2. ECHONET Lite・スクリーンセーバー・呼びかけをオンにして起動
prefs <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="el_enabled" value="true" />
    <boolean name="ss_enabled" value="true" />
    <boolean name="wake_enabled" value="true" />
    <boolean name="help_shown" value="true" />
    <string name="theme">light_blue</string>
</map>
X
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity || fail "2回目の起動ができませんでした"
check "features-on" 15

# 3. デモ表示でホーム画面のレイアウトを確認（スマートフォンの大きさ）
prefs <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="demo_mode" value="true" />
    <boolean name="help_shown" value="true" />
</map>
X
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell wm size 1080x2400
adb shell wm density 420
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity || fail "デモ表示で起動できませんでした"
check "phone-home" 10
adb shell input swipe 540 1900 540 500 400
check "phone-scrolled" 3
adb shell input swipe 540 1900 540 500 400
check "phone-scrolled2" 3

# 4. スマートフォンを横向きに
adb shell settings put system user_rotation 1
check "phone-landscape" 6

# 5. Fire HD 8 に近い大きさ（横向き 1280x800）
adb shell settings put system user_rotation 0
adb shell wm size 1280x800
adb shell wm density 160
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity || fail "タブレットの大きさで起動できませんでした"
check "tablet-home" 10

adb shell wm size reset
adb shell wm density reset
echo "::notice::起動テストはすべて成功しました"
