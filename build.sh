#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=${BT:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}
JAR=${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)}
OUT=build; rm -rf $OUT; mkdir -p $OUT/stubs $OUT/classes $OUT/res

javac -source 8 -target 8 -Xlint:-options -cp "$JAR" -d $OUT/stubs $(find stubs -name '*.java')
$BT/aapt2 compile --dir res -o $OUT/res
$BT/aapt2 link -o $OUT/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 29 -A assets $OUT/res/*.flat
javac -source 8 -target 8 -Xlint:-options -cp "$JAR:$OUT/stubs" -d $OUT/classes $(find src -name '*.java')
$BT/d8 --lib "$JAR" --min-api 29 --output $OUT $(find $OUT/classes -name '*.class')   # stubs are compile-only
cp $OUT/base.apk $OUT/unsigned.apk && (cd $OUT && zip -q -j unsigned.apk classes.dex)

KS=${KEYSTORE:-debug.keystore}; export KEYSTORE_PASS=${KEYSTORE_PASS:-android}; KEY_ALIAS=${KEY_ALIAS:-dbg}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" \
  -dname "CN=debug" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign --ks "$KS" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASS --key-pass env:KEYSTORE_PASS --out softbinder-module.apk $OUT/aligned.apk
echo softbinder-module.apk
