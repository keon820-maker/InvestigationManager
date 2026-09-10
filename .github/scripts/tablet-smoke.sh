#!/usr/bin/env bash
set -eu
mkdir -p tablet-test-artifacts

test_status=0
gradle -PincludeEmulatorAbi=true testDebugUnitTest connectedDebugAndroidTest assembleDebug || test_status=$?
[ ! -d app/build/reports/androidTests/connected ] || cp -R app/build/reports/androidTests/connected tablet-test-artifacts/ocr-test-report
adb pull /sdcard/Android/data/kr.co.investigation.manager/files/ui-test-evidence tablet-test-artifacts/ui-test-evidence || true
if [ "$test_status" -ne 0 ]; then
  adb logcat -d -t 2000 > tablet-test-artifacts/test-logcat.txt
  exit "$test_status"
fi
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb shell wm size 2560x1600
adb shell wm density 280
adb shell wm size > tablet-test-artifacts/display-size.txt
adb shell wm density > tablet-test-artifacts/display-density.txt
adb shell settings put system accelerometer_rotation 0

adb shell settings put system user_rotation 0
adb shell am force-stop kr.co.investigation.manager
adb shell am start -W -n kr.co.investigation.manager/.MainActivity > tablet-test-artifacts/launch-landscape.txt
cat tablet-test-artifacts/launch-landscape.txt
sleep 5
adb shell pidof kr.co.investigation.manager
adb exec-out screencap -p > tablet-test-artifacts/landscape.png

adb shell settings put system user_rotation 1
sleep 5
adb shell pidof kr.co.investigation.manager
adb exec-out screencap -p > tablet-test-artifacts/portrait.png

adb shell dumpsys window windows > tablet-test-artifacts/window-dump.txt
grep 'kr.co.investigation.manager/kr.co.investigation.manager.MainActivity' tablet-test-artifacts/window-dump.txt > tablet-test-artifacts/focused-window.txt
cat tablet-test-artifacts/focused-window.txt
grep -q 'kr.co.investigation.manager' tablet-test-artifacts/focused-window.txt
adb logcat -d -t 500 > tablet-test-artifacts/logcat.txt
file tablet-test-artifacts/portrait.png tablet-test-artifacts/landscape.png > tablet-test-artifacts/screenshot-sizes.txt
cat tablet-test-artifacts/screenshot-sizes.txt
grep -q 'landscape.png: PNG image data, 2560 x 1600' tablet-test-artifacts/screenshot-sizes.txt
grep -q 'portrait.png:  PNG image data, 1600 x 2560' tablet-test-artifacts/screenshot-sizes.txt
