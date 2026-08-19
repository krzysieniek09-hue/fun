#!/usr/bin/env bash
# ============================================================================
# Builds playwave-debug.apk without the Android SDK — only a JDK (11+) and
# curl are required. Tooling is fetched once into .tools/:
#   apktool  (bundles aapt + framework res)  -> packages APK, compiles manifest
#   dalvik-dx (repackaged AOSP dx)           -> .class files -> classes.dex
#   android 4.1.1.4 jar (Maven Central)      -> compile-time android classpath
#   apksig                                   -> v1+v2 signing & verification
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=.tools
BUILD=build
mkdir -p "$TOOLS"

fetch() {
  [ -f "$TOOLS/$1" ] && return
  echo "fetching $1 ..."
  curl -fsSL -o "$TOOLS/$1" "$2"
}

fetch apktool.jar      https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar
fetch android-stub.jar https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar
fetch dx.jar           https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar
fetch apksig.jar       https://repo1.maven.org/maven2/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/stubs" "$BUILD/proj/assets/www"

# Compile-time stubs shadow classes in the (API 16) android jar that gained
# methods we need in later APIs; they are never dexed or packaged.
echo "compiling stubs + MainActivity ..."
javac --release 8 -nowarn -cp "$TOOLS/android-stub.jar" -d "$BUILD/stubs" \
  $(find src-stubs -name '*.java')
javac --release 8 -nowarn -cp "$BUILD/stubs:$TOOLS/android-stub.jar" \
  -d "$BUILD/classes" src/MainActivity.java

echo "dexing ..."
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main \
  --dex --min-sdk-version=24 --output="$BUILD/proj/classes.dex" "$BUILD/classes"

echo "staging apk project ..."
cp AndroidManifest.xml apktool.yml "$BUILD/proj/"
cp -r res "$BUILD/proj/res"
cp ../index.html "$BUILD/proj/assets/www/"
cp -r ../css ../js ../demo "$BUILD/proj/assets/www/"

echo "packaging with apktool ..."
java -jar "$TOOLS/apktool.jar" b "$BUILD/proj" -o "$BUILD/playwave-unsigned.apk"

if [ ! -f playwave-debug.keystore ]; then
  echo "generating debug keystore ..."
  keytool -genkeypair -keystore playwave-debug.keystore -alias playwave \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass playwave -keypass playwave -dname "CN=Playwave Debug"
fi

echo "signing ..."
javac -nowarn -cp "$TOOLS/apksig.jar" -d "$BUILD/signer" src/Signer.java
# --add-exports: apksig 2.3.0 predates the JDK module system and reads
# sun.security.* internals directly.
java --add-exports java.base/sun.security.x509=ALL-UNNAMED \
     --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
     -cp "$TOOLS/apksig.jar:$BUILD/signer" Signer \
  playwave-debug.keystore playwave playwave \
  "$BUILD/playwave-unsigned.apk" playwave-debug.apk

echo
echo "done: $(pwd)/playwave-debug.apk"
