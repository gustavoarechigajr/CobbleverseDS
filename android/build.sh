#!/bin/bash
# Builds the bottom-screen wrapper APK with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-/home/deck/android-dev/sdk}
BT=$SDK/build-tools/35.0.0
PLAT=$SDK/platforms/android-33/android.jar
export JAVA_HOME=${JAVA_HOME:-/home/deck/android-dev/jdk17}
export PATH=$JAVA_HOME/bin:$PATH
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$PLAT" --min-sdk-version 26 --target-sdk-version 33
javac -Xlint:-options --release 11 -classpath "$PLAT" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$PLAT" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -qj ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$OUT/cobbleverse-ds.apk" "$OUT/aligned.apk"
echo "$OUT/cobbleverse-ds.apk"
