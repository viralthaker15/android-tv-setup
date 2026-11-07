#!/bin/bash
# Builds build/tv-keys.apk with the plain SDK tools (no Gradle): resources, Java, dex, align, sign.
# Needs JDK 17 and Android SDK platform 34 + build-tools 34.0.0 (both installed with Homebrew on this Mac).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"   # the same local debug key the launcher is signed with

rm -rf build
mkdir -p build/classes build/dex build/gen

# 1. Resources + manifest -> an APK without code, plus R.java
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link build/res.zip -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 30 --target-sdk-version 34 --version-code 5 --version-name 1.3.1 \
    --java build/gen -o build/base.apk

# 2. Java -> .class -> classes.dex
javac --release 11 -Xlint:all -Werror -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

# 3. Add the dex, align, sign
cp build/base.apk build/unaligned.apk
(cd build/dex && zip -q ../unaligned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --key-pass pass:android --out build/tv-keys.apk build/aligned.apk
"$BT/apksigner" verify build/tv-keys.apk

ls -l build/tv-keys.apk
shasum -a 256 build/tv-keys.apk
