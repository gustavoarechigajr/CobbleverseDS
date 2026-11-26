#!/bin/bash
# Builds the bottom-screen wrapper APK with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
# Any SDK with build-tools 34+ and a platform 33+ works; the newest installed of each is used.
SDK=${ANDROID_SDK:-${ANDROID_HOME:-}}
for d in "$SDK" "$HOME/android-dev/sdk" "$HOME/Android/Sdk"; do [ -n "$d" ] && [ -d "$d/build-tools" ] && { SDK=$d; break; }; done
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLAT=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
# JDK 17+: $JAVA_HOME, the old ~/android-dev/jdk17, or whatever java is on PATH.
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/android-dev/jdk17" ]; then export JAVA_HOME=$HOME/android-dev/jdk17; fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH=$JAVA_HOME/bin:$PATH; fi
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
