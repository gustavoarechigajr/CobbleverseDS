#!/bin/bash
# Builds the bottom-screen wrapper APK with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-${ANDROID_HOME:-$HOME/android-dev/sdk}}  # needs build-tools 35.0.0 + platforms/android-33
BT=$SDK/build-tools/35.0.0
PLAT=$SDK/platforms/android-33/android.jar
export JAVA_HOME=${JAVA_HOME:-$HOME/android-dev/jdk17}  # any JDK 17+
export PATH=$JAVA_HOME/bin:$PATH
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$PLAT" --min-sdk-version 26 --target-sdk-version 33
javac -Xlint:-options --release 11 -classpath "$PLAT" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$PLAT" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -qj ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=${CVDS_KEYSTORE:-$HOME/.android/debug.keystore}
if [ ! -f "$KS" ]; then # standard Android debug key, created on first build
	mkdir -p "$(dirname "$KS")"
	keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
		-keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/cobbleverse-ds.apk" "$OUT/aligned.apk"
echo "$OUT/cobbleverse-ds.apk"
