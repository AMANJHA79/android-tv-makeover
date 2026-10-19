#!/bin/sh
# Builds the launcher and installs it on the TV as the default Home. Edit launcher/app/src/main/res/values/config.xml first.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
(cd launcher && ./gradlew -q assembleDebug)
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
tools/tv push "$APK" /data/local/tmp/atvhome.apk
tools/tv shell "pm install -r -t /data/local/tmp/atvhome.apk"
tools/tv shell "rm /data/local/tmp/atvhome.apk"
tools/tv shell "pm grant com.kulwant.atvhome android.permission.ACCESS_COARSE_LOCATION" || true
tools/tv shell "cmd package set-home-activity com.kulwant.atvhome/.HomeActivity"
tools/tv shell "am start -n com.kulwant.atvhome/.HomeActivity"
echo "Installed and set as home."
