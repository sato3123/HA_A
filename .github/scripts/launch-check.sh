#!/bin/bash
# エミュレーターにアプリを入れて起動し、止まらないことを確認する
set -u
PKG=jp.home.panel
fail() { echo "::error::$1"; exit 1; }

adb install -r app/build/outputs/apk/debug/app-debug.apk || fail "インストールできませんでした"
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb logcat -c

check() {
  local label=$1
  sleep "$2"
  adb exec-out screencap -p > "launch-$label.png" || true
  adb logcat -d > logcat.txt
  if grep -q "FATAL EXCEPTION" logcat.txt; then
    grep -A 30 "FATAL EXCEPTION" logcat.txt | head -40 | while IFS= read -r l; do echo "::error::$l"; done
    fail "アプリが止まりました（$label）"
  fi
  adb shell pidof $PKG >/dev/null || fail "アプリが動いていません（$label）"
  echo "::notice::$label：アプリは動いています"
}

# 1回目の起動（ログイン画面）
adb shell am start -W -n $PKG/.MainActivity || fail "起動できませんでした"
check "first" 12

# ECHONET Lite・スクリーンセーバーなどを有効にした設定で起動し直す
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ui_prefs.xml'" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="el_enabled" value="true" />
    <boolean name="ss_enabled" value="true" />
    <boolean name="wake_enabled" value="true" />
    <boolean name="help_shown" value="true" />
    <string name="theme">light_blue</string>
</map>
EOF
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity || fail "2回目の起動ができませんでした"
check "features-on" 15

# 回転しても止まらないか
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
check "rotated" 6
echo "::notice::起動テストはすべて成功しました"
