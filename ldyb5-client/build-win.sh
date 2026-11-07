#!/bin/bash
# ldyb5-client Windows 一键构建（无 WSL 依赖）
# 工具：build/wintools/（aapt2.exe、r8.jar、android.jar、pt/platform-tools）
# JDK：C:/Users/shaoh/WorkBuddy/R1/janus/dl/jdk-17.0.20.1+1
set -e
cd "$(dirname "$0")"

VER="${1:-v0.2.3-ldyb5}"
# V1.0.1 起支持「双轨版本号」：第 2 个参数可指定正式版 versionName，
# 第 3 个参数可选覆盖 versionCode（默认沿用 AndroidManifest.xml 里的值）。
# 主线内测版始终用 manifest 里的 0.x，不传第 2 个参数即可。
REL_NAME="${2:-}"
REL_CODE="${3:-}"
B=build/wbuild
WT=build/wintools
WJ="${JDK17_BIN:-C:/Users/shaoh/WorkBuddy/R1/janus/dl/jdk-17.0.20.1+1/bin}"
AJ="$WT/android.jar"

# 旧构建目录移走而非删除（沙箱 bulk-delete 保护 + 避免 stale class 混入）
if [ -d "$B" ]; then
  mv "$B" "${B}.bak.$(date +%H%M%S)"
fi
mkdir -p $B/classes $B/dex $B/gen

# 正式版：复制一份 manifest 只改 versionName（/versionCode），不动主线源码。
MF="AndroidManifest.xml"
if [ -n "$REL_NAME" ]; then
  MF="$B/AndroidManifest.release.xml"
  if [ -n "$REL_CODE" ]; then
    sed -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$REL_CODE\"/" \
        -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$REL_NAME\"/" \
        AndroidManifest.xml > "$MF"
  else
    sed -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$REL_NAME\"/" \
        AndroidManifest.xml > "$MF"
  fi
  echo "release manifest: $MF -> versionName=$REL_NAME ${REL_CODE:+versionCode=$REL_CODE}"
fi

echo "=== [1/5] aapt2 compile+link ==="
if [ -d res ]; then
  $WT/aapt2/aapt2.exe compile --dir res -o $B/res.zip
  $WT/aapt2/aapt2.exe link -I "$AJ" --manifest "$MF" -A assets \
    $B/res.zip --java $B/gen --debug-mode -o $B/base.apk
else
  $WT/aapt2/aapt2.exe link -I "$AJ" --manifest "$MF" -A assets \
    --java $B/gen --debug-mode -o $B/base.apk
fi

echo "=== [2/5] javac ==="
CP="$AJ;libs/sherpa-onnx-classes.jar;libs/kotlin-stdlib.jar;libs/okhttp.jar;libs/okio.jar;libs/media3-exoplayer.jar;libs/media3-common.jar;libs/media3-datasource.jar;libs/media3-extractor.jar;libs/media3-decoder.jar;libs/media3-container.jar;libs/media3-database.jar;libs/guava.jar;libs/failureaccess.jar;libs/annotation.jar;$B/gen"
find src $B/gen build/genstub -name "*.java" > $B/sources.txt
"$WJ/javac.exe" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options -proc:none \
  -classpath "$CP" -d $B/classes @$B/sources.txt
echo "javac OK: $(find $B/classes -name '*.class' | wc -l) classes"

echo "=== [3/5] d8 ==="
( cd $B/classes && "$WJ/jar.exe" cf ../app-classes.jar . )
"$WJ/java.exe" -cp $WT/r8.jar com.android.tools.r8.D8 \
  --release --min-api 21 --lib "$AJ" \
  --output $B/dex \
  $B/app-classes.jar \
  libs/sherpa-onnx-classes.jar libs/kotlin-stdlib.jar libs/okhttp.jar libs/okio.jar \
  libs/media3-exoplayer.jar libs/media3-common.jar libs/media3-datasource.jar \
  libs/media3-extractor.jar libs/media3-decoder.jar libs/media3-container.jar \
  libs/media3-database.jar \
  libs/guava.jar libs/failureaccess.jar libs/annotation.jar 2>&1 \
  | grep -viE "^Warning|not found|desugaring" || true

echo "=== [4/5] 合入 dex+so ==="
cp $B/base.apk $B/unsigned.apk
"${PYTHON_EXE:-C:/Users/shaoh/.workbuddy/binaries/python/versions/3.13.12/python.exe}" - "$B/unsigned.apk" "$B" <<'PYEOF'
import sys, os, zipfile
apk, b = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(b, "dex", "classes.dex"), "classes.dex")
    so = os.path.join("jniLibs", "armeabi-v7a")
    for f in sorted(os.listdir(so)):
        if f.endswith(".so"):
            z.write(os.path.join(so, f), "lib/armeabi-v7a/" + f)
print("merged dex+so")
PYEOF

echo "=== [5/5] 签名 ==="
OUTAPK="ldyb5-client-$VER.apk"
cp $B/unsigned.apk "$OUTAPK"
"$WJ/jarsigner.exe" -keystore debug.keystore -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUTAPK" androiddebugkey >/dev/null 2>&1
unzip -l "$OUTAPK" | grep -q "META-INF/.*\.RSA" || { echo "!!! 签名失败"; exit 1; }

echo "=== 完成: $OUTAPK ==="
ls -la "$OUTAPK"
